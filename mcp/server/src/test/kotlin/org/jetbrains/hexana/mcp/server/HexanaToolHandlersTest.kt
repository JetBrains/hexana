package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.*
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.data.ListDataSectionsResult
import org.jetbrains.hexana.mcp.model.data.ListDataSegmentsResult
import org.jetbrains.hexana.mcp.model.elements.ListElementSegmentsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportedFunctionsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetInstructionsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetLocalsForFunctionsResult
import org.jetbrains.hexana.mcp.model.globals.ListGlobalsResult
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.mcp.model.memory.ListMemoryResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.model.types.TypesResult
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
    fun listExportedFunctionsReturnsStructuredPaginatedPayload() {
        val wasm = createWasmFile(
            "exported_functions.wasm",
            """
            (module
              (import "env" "log" (func (param i32)))
              (func ${'$'}main (param i32) (result i32)
                local.get 0)
              (export "main" (func 1))
              (export "log_alias" (func 0)))
            """.trimIndent()
        )

        val result = handlers.listExportedFunctions(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(1))
                    put("to", JsonPrimitive(1))
                })
            }
        )

        assertFalse(result.isError == true)
        val structured = result.structuredContent ?: error("Expected structured content")
        assertEquals(1, structured["from"]!!.jsonPrimitive.int)
        assertEquals(1, structured["to"]!!.jsonPrimitive.int)
        assertEquals(1, structured["returned"]!!.jsonArray.size)
        assertEquals(
            "log_alias",
            structured["returned"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
        )
        assertEquals(
            "env",
            structured["returned"]!!.jsonArray[0].jsonObject["entry"]!!.jsonObject["module"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun listTypesAndMemoryReturnStructuredPayloads() {
        val wasm = createWasmFile(
            "types_memory.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "shared" (memory 1))
              (memory 2 4))
            """.trimIndent()
        )

        val types = handlers.listTypes(jsonPath(wasm.toString()))
        val memory = handlers.listMemory(jsonPath(wasm.toString()))

        assertFalse(types.isError == true)
        assertFalse(memory.isError == true)
        assertEquals(
            "func",
            types.structuredContent!!["types"]!!.jsonArray[0].jsonObject["kind"]!!.jsonPrimitive.content
        )
        assertEquals(2, memory.structuredContent!!["memories"]!!.jsonArray.size)
        assertEquals(
            "import",
            memory.structuredContent!!["memories"]!!.jsonArray[0].jsonObject["definedIn"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun listExportedFunctionsValidatesEntriesRangeInput() {
        val result = handlers.listExportedFunctions(
            buildJsonObject {
                put("path", JsonPrimitive("/tmp/module.wasm"))
            }
        )

        assertEquals(true, result.isError)
        assertEquals("INVALID_INPUT", result.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`entriesRange` is required", (result.content.single() as TextContent).text)
    }

    @Test
    fun indexAndSegmentToolsReturnStructuredPayloads() {
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

        val functions = handlers.functionsForIndices(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                })
            }
        )
        val globals = handlers.listGlobals(jsonPath(wasm.toString()))
        val selectedGlobals = handlers.getGlobalsForIndices(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val data = handlers.listData(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(0))
                })
            }
        )
        val dataSegments = handlers.listDataSegments(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("segmentRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(1))
                })
                put("includeExports", JsonPrimitive(false))
                put("includeGlobals", JsonPrimitive(true))
            }
        )
        val selectedMemory = handlers.getMemoryForIndices(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(0))
                })
            }
        )
        val selectedTypes = handlers.getTypesForIndices(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(0))
                })
            }
        )
        val locals = handlers.getLocalsForFunctions(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val instructions = handlers.getInstructionsForFunctions(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val elements = handlers.listElementSegments(jsonPath(wasm.toString()))

        assertFalse(functions.isError == true)
        assertEquals(2, functions.structuredContent!!["functions"]!!.jsonArray.size)
        assertEquals(
            "env",
            functions.structuredContent!!["functions"]!!.jsonArray[0].jsonObject["import"]!!.jsonObject["module"]!!.jsonPrimitive.content
        )

        assertFalse(globals.isError == true)
        assertEquals(2, globals.structuredContent!!["globals"]!!.jsonArray.size)
        assertEquals(
            "env",
            globals.structuredContent!!["globals"]!!.jsonArray[0].jsonObject["prefix"]!!.jsonPrimitive.content
        )

        assertFalse(selectedGlobals.isError == true)
        assertEquals(1, selectedGlobals.structuredContent!!["globals"]!!.jsonArray.size)
        assertEquals(
            1,
            selectedGlobals.structuredContent!!["globals"]!!.jsonArray[0].jsonObject["globalIdx"]!!.jsonPrimitive.int
        )

        assertFalse(data.isError == true)
        assertEquals(
            "SGVsbG8=",
            data.structuredContent!!["returned"]!!.jsonArray[0].jsonObject["data"]!!.jsonPrimitive.content
        )

        assertFalse(dataSegments.isError == true)
        assertEquals(
            "Hello",
            dataSegments.structuredContent!!["segments"]!!.jsonArray[0].jsonObject["utf8Preview"]!!.jsonPrimitive.content
        )
        assertEquals(1, dataSegments.structuredContent!!["summary"]!!.jsonObject["totalSegments"]!!.jsonPrimitive.int)

        assertFalse(selectedMemory.isError == true)
        assertEquals(1, selectedMemory.structuredContent!!["memories"]!!.jsonArray.size)
        assertEquals(
            0,
            selectedMemory.structuredContent!!["memories"]!!.jsonArray[0].jsonObject["memoryIdx"]!!.jsonPrimitive.int
        )

        assertFalse(selectedTypes.isError == true)
        assertEquals(
            "func",
            selectedTypes.structuredContent!!["types"]!!.jsonArray[0].jsonObject["kind"]!!.jsonPrimitive.content
        )

        assertFalse(locals.isError == true)
        assertEquals(
            "num:i32",
            locals.structuredContent!!["functions"]!!.jsonArray[0].jsonObject["locals"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content
        )

        assertFalse(instructions.isError == true)
        assertTrue(instructions.structuredContent!!["functions"]!!.jsonArray[0].jsonObject["instructions"]!!.jsonArray.any { it.jsonPrimitive.content == "call 0" })

        assertFalse(elements.isError == true)
        assertEquals(1, elements.structuredContent!!["segments"]!!.jsonArray.size)
        assertEquals(
            "1",
            elements.structuredContent!!["segments"]!!.jsonArray[0].jsonObject["elements"]!!.jsonArray[0].jsonPrimitive.content
        )
    }

    @Test
    fun indexToolsValidateArrayInput() {
        val result = handlers.getTypesForIndices(
            buildJsonObject {
                put("path", JsonPrimitive("/tmp/module.wasm"))
                put("indices", JsonPrimitive("nope"))
            }
        )

        assertEquals(true, result.isError)
        assertEquals("INVALID_INPUT", result.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`indices` must be an array", (result.content.single() as TextContent).text)
    }

    @Test
    fun listDataSegmentsValidatesSegmentRangeInput() {
        val result = handlers.listDataSegments(
            buildJsonObject {
                put("path", JsonPrimitive("/tmp/module.wasm"))
                put("segmentRange", buildJsonObject {
                    put("from", JsonPrimitive("bad"))
                })
            }
        )

        assertEquals(true, result.isError)
        assertEquals("INVALID_INPUT", result.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`from` must be an integer", (result.content.single() as TextContent).text)
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
                override fun listExportedFunctions(
                    input: WasmInput,
                    entriesRange: IntRange
                ): ListExportedFunctionsResult = error("boom")

                override fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult = error("boom")
                override fun listGlobals(input: WasmInput): ListGlobalsResult = error("boom")
                override fun listTypes(input: WasmInput): TypesResult = error("boom")
                override fun listMemory(input: WasmInput): ListMemoryResult = error("boom")
                override fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult =
                    error("boom")

                override fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult = error("boom")
                override fun listDataSegments(
                    input: WasmInput,
                    segmentStartInclusive: Int,
                    segmentEndExclusive: Int,
                    includeExports: Boolean,
                    includeGlobals: Boolean,
                    minByteLength: Int?
                ): ListDataSegmentsResult = error("boom")

                override fun getMemoryForIndices(input: WasmInput, indices: List<Int>): ListMemoryResult = error("boom")
                override fun getTypesForIndices(input: WasmInput, indices: List<Int>): TypesResult = error("boom")
                override fun getLocalsForFunctions(input: WasmInput, indices: List<Int>): GetLocalsForFunctionsResult =
                    error("boom")

                override fun getInstructionsForFunctions(
                    input: WasmInput,
                    indices: List<Int>
                ): GetInstructionsForFunctionsResult = error("boom")

                override fun listElementSegments(input: WasmInput): ListElementSegmentsResult = error("boom")
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
                override fun listExportedFunctions(
                    input: WasmInput,
                    entriesRange: IntRange
                ): ListExportedFunctionsResult = error("unused")

                override fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult =
                    error("unused")

                override fun listGlobals(input: WasmInput): ListGlobalsResult = error("unused")
                override fun listTypes(input: WasmInput): TypesResult = error("unused")
                override fun listMemory(input: WasmInput): ListMemoryResult = error("unused")
                override fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult =
                    error("unused")

                override fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult =
                    error("unused")

                override fun listDataSegments(
                    input: WasmInput,
                    segmentStartInclusive: Int,
                    segmentEndExclusive: Int,
                    includeExports: Boolean,
                    includeGlobals: Boolean,
                    minByteLength: Int?
                ): ListDataSegmentsResult = error("unused")

                override fun getMemoryForIndices(input: WasmInput, indices: List<Int>): ListMemoryResult =
                    error("unused")

                override fun getTypesForIndices(input: WasmInput, indices: List<Int>): TypesResult = error("unused")
                override fun getLocalsForFunctions(input: WasmInput, indices: List<Int>): GetLocalsForFunctionsResult =
                    error("unused")

                override fun getInstructionsForFunctions(
                    input: WasmInput,
                    indices: List<Int>
                ): GetInstructionsForFunctionsResult = error("unused")

                override fun listElementSegments(input: WasmInput): ListElementSegmentsResult = error("unused")
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
