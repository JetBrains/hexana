package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultHexanaAnalysisServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val service = DefaultHexanaAnalysisService()

    @Test
    fun summarizeModuleReadsFilePathInput() {
        val wasm = createWasmFile(
            "summary.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "print" (func (param i32)))
              (memory 1)
              (func (export "main") (type 0) (param i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val result = service.summarizeModule(WasmInput.FilePath(wasm.toString()))

        assertEquals(1, result.functions)
        assertEquals(1, result.exports)
        assertEquals(1, result.memories)
        assertEquals(1, result.imports.functions)
        assertTrue(result.moduleSize > 0)
    }

    @Test
    fun summarizeModuleReadsInlineBytesInput() {
        val wasm = createWasmFile(
            "summary-inline.wasm",
            """
            (module
              (memory 1)
              (func (export "main")))
            """.trimIndent()
        )

        val result = service.summarizeModule(
            WasmInput.InlineBytes(Base64.getEncoder().encodeToString(Files.readAllBytes(wasm)))
        )

        assertEquals(1, result.functions)
        assertEquals(1, result.exports)
        assertEquals(1, result.memories)
        assertTrue(result.moduleSize > 0)
    }

    @Test
    fun summarizeModuleReadsRawBytesInput() {
        val wasm = createWasmFile(
            "summary-raw-bytes.wasm",
            """
            (module
              (memory 1)
              (func (export "main")))
            """.trimIndent()
        )

        val result = service.summarizeModule(WasmInput.RawBytes(Files.readAllBytes(wasm)))

        assertEquals(1, result.functions)
        assertEquals(1, result.exports)
        assertEquals(1, result.memories)
        assertTrue(result.moduleSize > 0)
    }

    @Test
    fun listImportsReturnsImportedFunctions() {
        val wasm = createWasmFile(
            "imports.wasm",
            """
            (module
              (import "env" "print" (func (param i32)))
              (func (export "main")
                i32.const 1
                call 0))
            """.trimIndent()
        )

        val result = service.listImports(WasmInput.FilePath(wasm.toString()))

        assertEquals(1, result.imports.size)
        assertEquals("env", result.imports.single().module)
        assertEquals("print", result.imports.single().name)
    }

    @Test
    fun listExportsAndFunctionsReuseCurrentQueryShapes() {
        val wasm = createWasmFile(
            "functions.wasm",
            """
            (module
              (import "env" "log" (func (param i32)))
              (func (export "increment") (param i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val exports = service.listExports(WasmInput.FilePath(wasm.toString()))
        val functions = service.listFunctions(WasmInput.FilePath(wasm.toString()), offset = 0, limit = 10)

        assertEquals(listOf("increment"), exports.exports.map { it.name })
        assertEquals(2, functions.functions.size)
        assertEquals("import", functions.functions.first().definedIn)
        assertEquals("function_section", functions.functions.last().definedIn)
    }

    @Test
    fun listExportedFunctionsReturnsPaginatedEntries() {
        val wasm = createWasmFile(
            "exported_functions.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "log" (func (param i32)))
              (func ${'$'}main (type 0) (param i32) (result i32)
                local.get 0)
              (export "log_alias" (func 0))
              (export "main" (func 1)))
            """.trimIndent()
        )

        val result = service.listExportedFunctions(WasmInput.FilePath(wasm.toString()), entriesRange = 0..0)

        assertEquals(0, result.from)
        assertEquals(0, result.to)
        assertEquals(2, result.total)
        assertEquals(1, result.returned.size)
        assertEquals("log_alias", result.returned.single().name)
        assertEquals("env", result.returned.single().entry.module)
        assertEquals("log", result.returned.single().entry.name)
    }

    @Test
    fun listTypesAndMemoryReuseCurrentQueryShapes() {
        val wasm = createWasmFile(
            "types_memory.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "shared" (memory 1))
              (memory 2 4))
            """.trimIndent()
        )

        val types = service.listTypes(WasmInput.FilePath(wasm.toString()))
        val memory = service.listMemory(WasmInput.FilePath(wasm.toString()))

        assertEquals(1, types.types.size)
        assertEquals("func", types.types.single().kind)
        assertEquals("i32", types.types.single().params!!.single())
        assertEquals("i32", types.types.single().retTypes!!.single())
        assertEquals(2, memory.memories.size)
        assertEquals("import", memory.memories.first().definedIn)
        assertEquals("memory_section", memory.memories.last().definedIn)
        assertEquals(1L, memory.memories.first().limit.min)
        assertEquals(4L, memory.memories.last().limit.max)
    }

    @Test
    fun remainingQueriesReuseCurrentQueryShapes() {
        val wasm = createWasmFile(
            "rich_fixture.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "print" (func (param i32)))
              (import "env" "seed" (global i32))
              (memory (export "memory") 1)
              (table 1 funcref)
              (func ${'$'}increment (type 0) (param i32) (result i32)
                (local i32)
                local.get 0
                local.set 1
                local.get 1
                call 0
                local.get 1)
              (global (export "counter") (mut i64) (i64.const 7))
              (elem (i32.const 0) func 1)
              (data (i32.const 0) "Hello"))
            """.trimIndent()
        )

        val functions = service.functionsForIndices(WasmInput.FilePath(wasm.toString()), indices = listOf(0, 1))
        val globals = service.listGlobals(WasmInput.FilePath(wasm.toString()))
        val selectedGlobals = service.getGlobalsForIndices(WasmInput.FilePath(wasm.toString()), indices = listOf(1))
        val data = service.listData(WasmInput.FilePath(wasm.toString()), entriesRange = 0..0)
        val dataSegments = service.listDataSegments(
            input = WasmInput.FilePath(wasm.toString()),
            segmentStartInclusive = 0,
            segmentEndExclusive = 1,
            includeExports = false,
            includeGlobals = true
        )
        val selectedMemory = service.getMemoryForIndices(WasmInput.FilePath(wasm.toString()), indices = listOf(0))
        val selectedTypes = service.getTypesForIndices(WasmInput.FilePath(wasm.toString()), indices = listOf(0))
        val locals = service.getLocalsForFunctions(WasmInput.FilePath(wasm.toString()), indices = listOf(1))
        val instructions = service.getInstructionsForFunctions(WasmInput.FilePath(wasm.toString()), indices = listOf(1))
        val elements = service.listElementSegments(WasmInput.FilePath(wasm.toString()))

        assertEquals(2, functions.functions.size)
        assertEquals("env", functions.functions.first().importInfo!!.module)
        assertEquals(2, globals.globals.size)
        assertEquals("env", globals.globals.first().prefix)
        assertEquals(1, selectedGlobals.globals.single().globalIdx)
        assertEquals("SGVsbG8=", java.util.Base64.getEncoder().encodeToString(data.returned.single().data))
        assertEquals("Hello", dataSegments.segments.single().utf8Preview)
        assertEquals(1, dataSegments.summary.totalSegments)
        assertEquals(0, selectedMemory.memories.single().memoryIdx)
        assertEquals("func", selectedTypes.types.single().kind)
        assertEquals("num:i32", locals.functions.single().locals.single().type)
        assertTrue(instructions.functions.single().instructions.contains("call 0"))
        assertEquals("1", elements.segments.single().elements!!.single())
    }

    @Test
    fun rejectsMissingFile() {
        val error = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath("/definitely/missing/module.wasm"))
        }

        assertEquals(HexanaAnalysisErrorCode.FILE_NOT_FOUND, error.code)
        assertEquals("File not found", error.message)
    }

    @Test
    fun rejectsUnsupportedUriInputKind() {
        val uriError = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.Uri("file:///tmp/module.wasm"))
        }

        assertEquals(HexanaAnalysisErrorCode.UNSUPPORTED_INPUT_KIND, uriError.code)
    }

    @Test
    fun rejectsInvalidInlineBytes() {
        val invalidBase64 = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.InlineBytes("%%%"))
        }
        val blankInlineBytes = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.InlineBytes("   "))
        }
        val malformedBase64 = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.InlineBytes("A"))
        }
        val oversizedInlineBytes = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(maxInlineInputBytes = 3)
                .summarizeModule(WasmInput.InlineBytes(Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, 3))))
        }
        val oversizedRawBytes = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(maxInlineInputBytes = 3)
                .summarizeModule(WasmInput.RawBytes(byteArrayOf(0, 1, 2, 3)))
        }
        val nonWasmInlineBytes = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(
                WasmInput.InlineBytes(Base64.getEncoder().encodeToString("not wasm".encodeToByteArray()))
            )
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, invalidBase64.code)
        assertEquals("Inline bytes must be valid base64", invalidBase64.message)
        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, blankInlineBytes.code)
        assertEquals("Inline bytes must not be blank", blankInlineBytes.message)
        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, malformedBase64.code)
        assertEquals("Inline bytes must be valid base64", malformedBase64.message)
        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, oversizedInlineBytes.code)
        assertEquals("Inline bytes exceed maximum size of 3 bytes", oversizedInlineBytes.message)
        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, oversizedRawBytes.code)
        assertEquals("Inline bytes exceed maximum size of 3 bytes", oversizedRawBytes.message)
        assertEquals(HexanaAnalysisErrorCode.WASM_PARSE_FAILED, nonWasmInlineBytes.code)
    }

    @Test
    fun acceptsInlineBytesAtExactConfiguredLimitWhenBase64IsPadded() {
        val service = DefaultHexanaAnalysisService(maxInlineInputBytes = 8)
        val minimalWasm = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

        val result = service.summarizeModule(
            WasmInput.InlineBytes(Base64.getEncoder().encodeToString(minimalWasm))
        )

        assertEquals(8, result.moduleSize)
    }

    @Test
    fun rejectsOversizedFileInputBeforeReadingBytes() {
        val oversizedFile = temporaryFolder.newFile("oversized.wasm").toPath()
        Files.write(oversizedFile, byteArrayOf(0, 1, 2, 3))

        val error = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(maxFileInputBytes = 3)
                .summarizeModule(WasmInput.FilePath(oversizedFile.toString()))
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertEquals("File exceeds maximum size of 3 bytes", error.message)
    }

    @Test
    fun rejectsNonWasmAndMalformedFiles() {
        val textFile = temporaryFolder.newFile("not_wasm.txt").toPath()
        Files.writeString(textFile, "not wasm")

        val malformedFile = temporaryFolder.newFile("malformed.wasm").toPath()
        Files.writeString(malformedFile, "not actually wasm")

        val nonWasmError = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath(textFile.toString()))
        }
        val malformedError = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath(malformedFile.toString()))
        }

        assertEquals(HexanaAnalysisErrorCode.NOT_WASM, nonWasmError.code)
        assertEquals(HexanaAnalysisErrorCode.WASM_PARSE_FAILED, malformedError.code)
    }

    @Test
    fun rejectsInvalidFunctionPagination() {
        val wasm = createWasmFile(
            "range.wasm",
            """
            (module
              (func (export "main")))
            """.trimIndent()
        )

        val negativeOffset = assertFailsWith<HexanaAnalysisException> {
            service.listFunctions(WasmInput.FilePath(wasm.toString()), offset = -1, limit = 1)
        }
        val nonPositiveLimit = assertFailsWith<HexanaAnalysisException> {
            service.listFunctions(WasmInput.FilePath(wasm.toString()), offset = 0, limit = 0)
        }

        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, negativeOffset.code)
        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, nonPositiveLimit.code)
    }

    @Test
    fun rejectsInvalidExportedFunctionRange() {
        val wasm = createWasmFile(
            "export_range.wasm",
            """
            (module
              (func (export "main")))
            """.trimIndent()
        )

        val negativeFrom = assertFailsWith<HexanaAnalysisException> {
            service.listExportedFunctions(WasmInput.FilePath(wasm.toString()), entriesRange = -1..0)
        }
        val invertedRange = assertFailsWith<HexanaAnalysisException> {
            service.listExportedFunctions(WasmInput.FilePath(wasm.toString()), entriesRange = 1..0)
        }

        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, negativeFrom.code)
        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, invertedRange.code)
    }

    @Test
    fun rejectsInvalidIndicesAndSegmentParameters() {
        val wasm = createWasmFile(
            "invalid_inputs.wasm",
            """
            (module
              (memory 1)
              (func (export "main")))
            """.trimIndent()
        )

        val negativeIndex = assertFailsWith<HexanaAnalysisException> {
            service.getTypesForIndices(WasmInput.FilePath(wasm.toString()), indices = listOf(-1))
        }
        val invertedSegmentRange = assertFailsWith<HexanaAnalysisException> {
            service.listDataSegments(
                input = WasmInput.FilePath(wasm.toString()),
                segmentStartInclusive = 5,
                segmentEndExclusive = 1
            )
        }
        val negativeMinByteLength = assertFailsWith<HexanaAnalysisException> {
            service.listDataSegments(
                input = WasmInput.FilePath(wasm.toString()),
                segmentStartInclusive = 0,
                segmentEndExclusive = 1,
                minByteLength = -1
            )
        }

        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, negativeIndex.code)
        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, invertedSegmentRange.code)
        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, negativeMinByteLength.code)
    }

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
    }
}
