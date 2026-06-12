package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.mcp.model.functions.SourceLocationInput
import org.jetbrains.hexana.mcp.model.functions.WasmFrameInput
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashResolverServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val service = DefaultHexanaAnalysisService()

    @Test
    fun resolveFunctionSymbolsUsesNameSectionExportImportAndFuncidxFallback() {
        val wasm = createWasmFile(
            "symbols.wasm",
            """
            (module
              (type ${'$'}id (func (param i32) (result i32)))
              (import "env" "host_log" (func ${'$'}host_log (param i32)))
              (func ${'$'}named_local (type ${'$'}id) (param i32) (result i32)
                local.get 0)
              (func (export "exported_only") (result i32)
                i32.const 7))
            """.trimIndent()
        )

        val result = service.resolveFunctionSymbols(
            input = WasmInput.FilePath(wasm.toString()),
            indices = listOf(0, 1, 2, 99),
            sourceMapInput = null,
        )

        val imported = result.symbols.single { it.funcidx == 0 }
        assertEquals("import", imported.origin)
        assertEquals("host_log", imported.symbol)
        assertEquals("import", imported.symbolSource)
        assertEquals("env", imported.importInfo?.module)

        val named = result.symbols.single { it.funcidx == 1 }
        assertEquals("local", named.origin)
        assertEquals("named_local", named.symbol)
        assertEquals("name_section", named.symbolSource)
        assertEquals(listOf("i32"), named.signature?.params)
        assertEquals(listOf("i32"), named.signature?.ret)

        val exported = result.symbols.single { it.funcidx == 2 }
        assertEquals("exported_only", exported.symbol)
        assertEquals("export", exported.symbolSource)
        assertEquals(listOf("exported_only"), exported.exportedAs)

        val invalid = result.symbols.single { it.funcidx == 99 }
        assertEquals("unknown", invalid.origin)
        assertEquals("func99", invalid.symbol)
        assertEquals("funcidx_fallback", invalid.symbolSource)
        assertEquals(listOf("invalid_funcidx"), invalid.warnings)
    }

    @Test
    fun resolveSourceLocationsMapsCodeOffsetsThroughSourceMap() {
        val wasm = createWasmFile(
            "source-map-target.wasm",
            """
            (module
              (func ${'$'}mapped (export "mapped") (result i32)
                i32.const 42))
            """.trimIndent()
        )
        val offset = service.listCodeSectionOffsets(WasmInput.FilePath(wasm.toString()))
            .functions
            .single()
            .offset
        val sourceMap = createSourceMapFile(
            "source-map-target.wasm.map",
            source = "src/module.rs",
            offset = offset.toInt(),
            line = 12,
            column = 4,
        )

        val result = service.resolveSourceLocations(
            input = WasmInput.FilePath(wasm.toString()),
            locations = listOf(
                SourceLocationInput(codeOffset = offset),
                SourceLocationInput(funcidx = 0),
            ),
            sourceMapInput = TextInput.FilePath(sourceMap.toString()),
        )

        val byOffset = result.locations[0]
        assertEquals(0, byOffset.funcidx)
        assertEquals(offset, byOffset.codeOffset)
        assertEquals("source_map", byOffset.source)
        assertEquals(sourceMap.resolveSibling("src/module.rs").toString(), byOffset.sourceLocation?.file)
        assertEquals(12, byOffset.sourceLocation?.line)
        assertEquals(4, byOffset.sourceLocation?.column)
        assertEquals(emptyList(), byOffset.warnings)

        val byFuncidx = result.locations[1]
        assertEquals(0, byFuncidx.funcidx)
        assertEquals(offset, byFuncidx.codeOffset)
        assertEquals("source_map", byFuncidx.source)
        assertEquals(sourceMap.resolveSibling("src/module.rs").toString(), byFuncidx.sourceLocation?.file)
    }

    @Test
    fun resolveWasmFramesReturnsCompactFrameMetadataAndWarnings() {
        val wasm = createWasmFile(
            "frames.wasm",
            """
            (module
              (func ${'$'}frame_target (export "frame_target") (result i32)
                i32.const 1))
            """.trimIndent()
        )
        val codeRange = service.listCodeSectionOffsets(WasmInput.FilePath(wasm.toString())).functions.single()
        val sourceMap = createSourceMapFile(
            "frames.wasm.map",
            source = "src/frame.rs",
            offset = codeRange.offset.toInt(),
            line = 7,
            column = 2,
        )

        val result = service.resolveWasmFrames(
            input = WasmInput.FilePath(wasm.toString()),
            frames = listOf(
                WasmFrameInput(funcidx = 0, codeOffset = codeRange.offset, module = "runtime", label = "func0"),
                WasmFrameInput(funcidx = 0, codeOffset = codeRange.offset + codeRange.size + 1),
            ),
            sourceMapInput = TextInput.FilePath(sourceMap.toString()),
        )

        val resolved = result.frames[0]
        assertEquals(0, resolved.frameIndex)
        assertEquals("frame_target", resolved.symbol)
        assertEquals("name_section", resolved.symbolSource)
        assertEquals("local", resolved.origin)
        assertEquals("runtime", resolved.module)
        assertEquals("func0", resolved.rawLabel)
        assertEquals(codeRange.offset, resolved.codeRange?.offset)
        assertEquals(sourceMap.resolveSibling("src/frame.rs").toString(), resolved.sourceLocation?.file)
        assertEquals(emptyList(), resolved.warnings)

        val mismatched = result.frames[1]
        assertTrue("code_offset_outside_function_range" in mismatched.warnings)
    }

    @Test
    fun resolveSourceLocationsReportsMissingQueryWithoutThrowing() {
        val wasm = createWasmFile(
            "missing-location-input.wasm",
            """
            (module
              (func (export "main")))
            """.trimIndent()
        )

        val result = service.resolveSourceLocations(
            input = WasmInput.FilePath(wasm.toString()),
            locations = listOf(SourceLocationInput()),
            sourceMapInput = null,
        )

        val location = result.locations.single()
        assertEquals(0, location.queryIndex)
        assertEquals(null, location.sourceLocation)
        assertEquals(listOf("missing_funcidx_or_code_offset"), location.warnings)
    }

    private fun createWasmFile(name: String, wat: String) =
        temporaryFolder.newFile(name).toPath().also {
            Files.write(it, wat2wasm(wat))
        }

    private fun createSourceMapFile(
        name: String,
        source: String,
        offset: Int,
        line: Int,
        column: Int,
    ) = temporaryFolder.newFile(name).toPath().also {
        val mapping = encodeVlq(offset) + encodeVlq(0) + encodeVlq(line) + encodeVlq(column)
        Files.writeString(
            it,
            """{"version":3,"sources":["$source"],"mappings":"$mapping"}""",
        )
        Files.createDirectories(it.resolveSibling(source).parent)
        Files.writeString(it.resolveSibling(source), "fn mapped() {}\n")
    }

    private fun encodeVlq(value: Int): String {
        var vlq = if (value < 0) ((-value) shl 1) + 1 else value shl 1
        val result = StringBuilder()
        do {
            var digit = vlq and 31
            vlq = vlq ushr 5
            if (vlq > 0) {
                digit = digit or 32
            }
            result.append(BASE64[digit])
        } while (vlq > 0)
        return result.toString()
    }

    companion object {
        private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }
}
