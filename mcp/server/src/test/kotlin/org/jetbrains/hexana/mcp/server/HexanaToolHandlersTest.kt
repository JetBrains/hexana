package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.*
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.Logger
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaToolHandlersTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val handlers = HexanaToolHandlers()

    @Test
    fun summarizeModuleReturnsStructuredSuccessPayload() {
        val wasm = createWasmFile(
            "summary.wasm",
            """
            (module
              (memory 1)
              (func (export "main")))
            """.trimIndent()
        )

        val result = handlers.summarizeModule(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
            }
        )

        assertTrue(result.isError != true)
        val textPayload = result.content.single() as TextContent
        val structured = result.structuredContent ?: error("Expected structured content")
        assertEquals(structured, Json.parseToJsonElement(textPayload.text).jsonObject)
        assertEquals(1, structured["functions"]!!.jsonPrimitive.int)
    }

    @Test
    fun listFunctionsReturnsToolErrorForMissingFile() {
        val result = handlers.listFunctions(
            buildJsonObject {
                put("path", JsonPrimitive("/missing/module.wasm"))
                put("offset", JsonPrimitive(0))
                put("limit", JsonPrimitive(10))
            }
        )

        assertEquals(true, result.isError)
        val textPayload = result.content.single() as TextContent
        assertEquals("File not found", textPayload.text)
        assertEquals("FILE_NOT_FOUND", result.structuredContent?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun listImportsAndExportsReuseCurrentQueryShapes() {
        val wasm = createWasmFile(
            "imports_exports.wasm",
            """
            (module
              (import "env" "print" (func (param i32)))
              (func (export "main")
                i32.const 1
                call 0))
            """.trimIndent()
        )

        val imports = handlers.listImports(jsonPath(wasm.toString()))
        val exports = handlers.listExports(jsonPath(wasm.toString()))

        assertFalse(imports.isError == true)
        assertFalse(exports.isError == true)
        assertEquals(
            imports.structuredContent,
            Json.parseToJsonElement((imports.content.single() as TextContent).text).jsonObject
        )
        assertEquals(
            exports.structuredContent,
            Json.parseToJsonElement((exports.content.single() as TextContent).text).jsonObject
        )
        assertEquals(
            "env",
            imports.structuredContent!!["imports"]!!.jsonArray[0].jsonObject["module"]!!.jsonPrimitive.content
        )
        assertEquals(
            "main",
            exports.structuredContent!!["exports"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun unexpectedExceptionReturnsClearInternalErrorMessage() {
        val logger = RecordingLogger()
        val handlers = HexanaToolHandlers(
            analysisService = object : HexanaAnalysisService {
                override fun summarizeModule(input: WasmInput): SummarizeModuleResult = error("boom")
                override fun listImports(input: WasmInput): ListImportsResult = error("boom")
                override fun listExports(input: WasmInput): ListExportsResult = error("boom")
                override fun listFunctions(input: WasmInput, offset: Int, limit: Int): FunctionsResult = error("boom")
            },
            logger = logger
        )

        val result = handlers.listImports(jsonPath("/tmp/module.wasm"))

        assertEquals(true, result.isError)
        assertEquals("INTERNAL_ERROR", result.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals(
            "Internal error while executing `list_imports`. See server logs for details.",
            (result.content.single() as TextContent).text
        )
        assertEquals(1, logger.errorEvents.size)
        assertTrue(logger.errorEvents.single().message.contains("list_imports"))
        assertEquals("boom", logger.errorEvents.single().throwable?.message)
    }

    @Test
    fun internalAnalysisExceptionKeepsUserMessageAndLogsCause() {
        val logger = RecordingLogger()
        val handlers = HexanaToolHandlers(
            analysisService = object : HexanaAnalysisService {
                override fun summarizeModule(input: WasmInput): SummarizeModuleResult {
                    throw HexanaAnalysisException(
                        HexanaAnalysisErrorCode.INTERNAL_ERROR,
                        "Failed to read .wasm file",
                        IllegalStateException("disk unavailable")
                    )
                }

                override fun listImports(input: WasmInput): ListImportsResult = error("unused")
                override fun listExports(input: WasmInput): ListExportsResult = error("unused")
                override fun listFunctions(input: WasmInput, offset: Int, limit: Int): FunctionsResult = error("unused")
            },
            logger = logger
        )

        val result = handlers.summarizeModule(jsonPath("/tmp/module.wasm"))

        assertEquals(true, result.isError)
        assertEquals("INTERNAL_ERROR", result.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("Failed to read .wasm file", (result.content.single() as TextContent).text)
        assertEquals(1, logger.errorEvents.size)
        assertEquals(
            "disk unavailable",
            logger.errorEvents.single().throwable?.cause?.message ?: logger.errorEvents.single().throwable?.message
        )
    }

    private fun jsonPath(path: String): JsonObject = buildJsonObject {
        put("path", JsonPrimitive(path))
    }

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
    }

    private class RecordingLogger : Logger by LoggerStub() {
        val errorEvents = mutableListOf<ErrorEvent>()

        override fun error(format: String?, arg: Any?, arg2: Any?) {
            errorEvents += ErrorEvent(
                message = listOf(format, arg?.toString(), arg2?.toString()).joinToString(" "),
                throwable = when {
                    arg is Throwable -> arg
                    arg2 is Throwable -> arg2
                    else -> null
                }
            )
        }

        override fun error(format: String?, vararg arguments: Any?) {
            errorEvents += ErrorEvent(
                message = buildList {
                    add(format)
                    arguments.forEach { add(it?.toString()) }
                }.joinToString(" "),
                throwable = when {
                    arguments.lastOrNull() is Throwable -> arguments.last() as Throwable
                    else -> null
                }
            )
        }
    }

    private data class ErrorEvent(
        val message: String,
        val throwable: Throwable?
    )
}
