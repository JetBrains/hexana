package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
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
    fun rejectsMissingFile() {
        val error = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath("/definitely/missing/module.wasm"))
        }

        assertEquals(HexanaAnalysisErrorCode.FILE_NOT_FOUND, error.code)
        assertEquals("File not found", error.message)
    }

    @Test
    fun rejectsUnsupportedInputKinds() {
        val uriError = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.Uri("file:///tmp/module.wasm"))
        }
        val inlineError = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.InlineBytes("AGFzbQ=="))
        }

        assertEquals(HexanaAnalysisErrorCode.UNSUPPORTED_INPUT_KIND, uriError.code)
        assertEquals(HexanaAnalysisErrorCode.UNSUPPORTED_INPUT_KIND, inlineError.code)
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

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
    }
}
