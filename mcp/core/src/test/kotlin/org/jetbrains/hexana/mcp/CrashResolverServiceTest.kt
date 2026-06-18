package org.jetbrains.hexana.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.mcp.model.functions.SourceLocationInput
import org.jetbrains.hexana.mcp.model.functions.WasmFrameInput
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
            crashReportInput = null,
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

        assertTrue(result.applicationFrameSummary?.contains("frame_target") == true)
        assertTrue(result.reportSummary?.contains("Top application frame resolves to frame_target") == true)
        assertEquals("frame_target", result.frameHighlights.topApplicationFrame?.symbol)
        assertEquals(listOf("frame_target", "frame_target"), result.frameHighlights.applicationFrames.map { it.symbol })
    }

    @Test
    fun resolveWasmFramesLoadsCrashReportFramesAndDiscoversAdjacentSourceMap() {
        val wasm = createWasmFile(
            "report-frames.wasm",
            """
            (module
              (type ${'$'}log_type (func (param i32)))
              (import "env" "host_log" (func ${'$'}host_log (type ${'$'}log_type)))
              (func ${'$'}mapped_export (export "mapped_export") (result i32)
                i32.const 42)
              (func ${'$'}tail_frame (result i32)
                i32.const 7))
            """.trimIndent()
        )
        val mappedRange = service.listCodeSectionOffsets(WasmInput.FilePath(wasm.toString()))
            .functions
            .single { it.funcidx == 1 }
        val sourceMap = createSourceMapFile(
            "report-frames.wasm.map",
            source = "src/report.rs",
            offset = mappedRange.offset.toInt(),
            line = 19,
            column = 3,
        )
        val crashReport = temporaryFolder.newFile("crash_report.json").toPath().also {
            Files.writeString(
                it,
                """
                {
                  "incident_id": "incident-1",
                  "crash_event_id": "event-1",
                  "build_id": "build-1",
                  "module": "dist/runtime/report-frames.wasm",
                  "frames": [
                    {"depth": 0, "funcidx": 0, "raw_label": "imported function 0"},
                    {"depth": 1, "funcidx": 1, "raw_label": "wasm-function[1]+0x04", "code_offset": ${mappedRange.offset}},
                    {"depth": 2, "funcidx": 99, "raw_label": "wasm-function[99]"}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = service.resolveWasmFrames(
            input = WasmInput.FilePath(wasm.toString()),
            frames = emptyList(),
            sourceMapInput = null,
            crashReportInput = TextInput.FilePath(crashReport.toString()),
        )

        assertEquals(crashReport.toString(), result.crashReport?.path)
        assertEquals("incident-1", result.crashReport?.incidentId)
        assertEquals("event-1", result.crashReport?.crashEventId)
        assertEquals("build-1", result.crashReport?.buildId)
        assertEquals("dist/runtime/report-frames.wasm", result.crashReport?.module)
        assertEquals(3, result.crashReport?.frameCount)
        assertEquals(sourceMap.toString(), result.sourceMapPath)

        val imported = result.frames[0]
        assertEquals("import", imported.origin)
        assertEquals("host_log", imported.symbol)
        assertEquals("import", imported.symbolSource)
        assertEquals("env", imported.importInfo?.module)
        assertEquals("imported function 0", imported.rawLabel)
        assertEquals(emptyList(), imported.exportedAs)
        assertEquals(listOf("i32"), imported.signature?.params)
        assertEquals(emptyList(), imported.signature?.results)
        assertEquals(emptyList(), imported.signature?.ret)
        assertNull(imported.codeRange)
        assertNull(imported.sourceLocation)
        assertEquals("import_has_no_code_range", imported.sourceFallbackReason)

        val mapped = result.frames[1]
        assertEquals("local", mapped.origin)
        assertEquals("mapped_export", mapped.symbol)
        assertEquals("name_section", mapped.symbolSource)
        assertEquals(listOf("mapped_export"), mapped.exportedAs)
        assertEquals(listOf(), mapped.signature?.params)
        assertEquals(listOf("i32"), mapped.signature?.results)
        assertEquals(listOf("i32"), mapped.signature?.ret)
        assertEquals(mappedRange.offset, mapped.codeRange?.offset)
        assertEquals(sourceMap.resolveSibling("src/report.rs").toString(), mapped.sourceLocation?.file)
        assertNull(mapped.sourceFallbackReason)

        val invalid = result.frames[2]
        assertEquals("unknown", invalid.origin)
        assertEquals("func99", invalid.symbol)
        assertEquals("funcidx_fallback", invalid.symbolSource)
        assertTrue("invalid_funcidx" in invalid.warnings)

        assertEquals(mapped, result.topApplicationFrame)
        assertTrue(result.applicationFrameSummary?.contains("mapped_export") == true)
        assertTrue(result.reportSummary?.contains("mapped_export") == true)
        assertTrue(result.reportSummary?.contains("host_log") == true)
        assertTrue(result.reportSummary?.contains("func99") == true)
        assertEquals("mapped_export", result.frameHighlights.topApplicationFrame?.symbol)
        assertEquals("host_log", result.frameHighlights.importFrames.single().symbol)
        assertEquals("func99", result.frameHighlights.fallbackFrames.single().symbol)
        assertEquals(listOf(0, 2), result.skippedFrames.map { it.frameIndex })
        assertEquals(listOf("imported_frame", "unknown_funcidx"), result.skippedFrames.map { it.reason })

        val serialized = encodeHexanaJsonElement(result).jsonObject
        val serializedFrames = serialized
            .getValue("frames")
            .jsonArray
            .map { it.jsonObject }
        val serializedImported = serializedFrames[0]
        val importedSignature = serializedImported.getValue("signature").jsonObject
        assertEquals(listOf("i32"), importedSignature.stringArray("params"))
        assertEquals(emptyList(), importedSignature.stringArray("results"))
        assertEquals(emptyList(), importedSignature.stringArray("return"))
        assertEquals("import_has_no_code_range", serializedImported.getValue("sourceFallbackReason").jsonPrimitive.content)

        val serializedMappedSignature = serializedFrames[1].getValue("signature").jsonObject
        assertEquals(listOf("i32"), serializedMappedSignature.stringArray("results"))
        assertEquals(listOf("i32"), serializedMappedSignature.stringArray("return"))

        val serializedHighlights = serialized.getValue("frameHighlights").jsonObject
        assertEquals(
            "host_log",
            serializedHighlights.getValue("importFrames").jsonArray.single().jsonObject.getValue("symbol").jsonPrimitive.content,
        )
        assertEquals(
            "func99",
            serializedHighlights.getValue("fallbackFrames").jsonArray.single().jsonObject.getValue("symbol").jsonPrimitive.content,
        )
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

private fun JsonObject.stringArray(name: String): List<String> =
    getValue(name).jsonArray.map { it.jsonPrimitive.content }
