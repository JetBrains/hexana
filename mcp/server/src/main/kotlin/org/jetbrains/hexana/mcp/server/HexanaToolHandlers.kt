package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.*
import org.jetbrains.hexana.mcp.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class HexanaToolHandlers(
    private val analysisService: HexanaAnalysisService = HexanaAnalysisServices.default,
    private val logger: Logger = LoggerFactory.getLogger(HexanaToolHandlers::class.java)
) {
    fun summarizeModule(arguments: JsonObject?): CallToolResult {
        return execute("summarize_module") {
            analysisService.summarizeModule(WasmInput.FilePath(arguments.requireString("path")))
        }
    }

    fun listImports(arguments: JsonObject?): CallToolResult {
        return execute("list_imports") {
            analysisService.listImports(WasmInput.FilePath(arguments.requireString("path")))
        }
    }

    fun listExports(arguments: JsonObject?): CallToolResult {
        return execute("list_exports") {
            analysisService.listExports(WasmInput.FilePath(arguments.requireString("path")))
        }
    }

    fun listFunctions(arguments: JsonObject?): CallToolResult {
        return execute("list_functions") {
            analysisService.listFunctions(
                input = WasmInput.FilePath(arguments.requireString("path")),
                offset = arguments.intOrDefault("offset", 0),
                limit = arguments.intOrDefault("limit", 200)
            )
        }
    }

    fun listExportedFunctions(arguments: JsonObject?): CallToolResult {
        return execute("list_exported_functions") {
            analysisService.listExportedFunctions(
                input = pathInput(arguments),
                entriesRange = arguments.requireClosedRange("entriesRange")
            )
        }
    }

    fun functionsForIndices(arguments: JsonObject?): CallToolResult {
        return execute("functions_for_indices") {
            analysisService.functionsForIndices(
                input = pathInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun listGlobals(arguments: JsonObject?): CallToolResult {
        return execute("list_globals") {
            analysisService.listGlobals(pathInput(arguments))
        }
    }

    fun listTypes(arguments: JsonObject?): CallToolResult {
        return execute("list_types") {
            analysisService.listTypes(pathInput(arguments))
        }
    }

    fun listMemory(arguments: JsonObject?): CallToolResult {
        return execute("list_memory") {
            analysisService.listMemory(pathInput(arguments))
        }
    }

    fun getGlobalsForIndices(arguments: JsonObject?): CallToolResult {
        return execute("get_globals_for_indices") {
            analysisService.getGlobalsForIndices(
                input = pathInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun listData(arguments: JsonObject?): CallToolResult {
        return execute("list_data") {
            analysisService.listData(
                input = pathInput(arguments),
                entriesRange = arguments.requireClosedRange("entriesRange")
            )
        }
    }

    fun listDataSegments(arguments: JsonObject?): CallToolResult {
        return execute("list_data_segments") {
            val (from, toExclusive) = arguments.requireHalfOpenRange("segmentRange")
            analysisService.listDataSegments(
                input = pathInput(arguments),
                segmentStartInclusive = from,
                segmentEndExclusive = toExclusive,
                includeExports = arguments.booleanOrDefault("includeExports", false),
                includeGlobals = arguments.booleanOrDefault("includeGlobals", true),
                minByteLength = arguments.nullableInt("minByteLength")
            )
        }
    }

    fun getMemoryForIndices(arguments: JsonObject?): CallToolResult {
        return execute("get_memory_for_indices") {
            analysisService.getMemoryForIndices(
                input = pathInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun getTypesForIndices(arguments: JsonObject?): CallToolResult {
        return execute("get_types_for_indices") {
            analysisService.getTypesForIndices(
                input = pathInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun getLocalsForFunctions(arguments: JsonObject?): CallToolResult {
        return execute("get_locals_for_functions") {
            analysisService.getLocalsForFunctions(
                input = pathInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun getInstructionsForFunctions(arguments: JsonObject?): CallToolResult {
        return execute("get_instructions_for_functions") {
            analysisService.getInstructionsForFunctions(
                input = pathInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun listElementSegments(arguments: JsonObject?): CallToolResult {
        return execute("list_element_segments") {
            analysisService.listElementSegments(pathInput(arguments))
        }
    }

    private inline fun <reified T> execute(toolName: String, block: () -> T): CallToolResult {
        return try {
            val result = block()
            val jsonResult = encodeHexanaJson(result)
            val structuredContent = encodeHexanaJsonElement(result) as? JsonObject
            CallToolResult(
                content = listOf(TextContent(jsonResult)),
                structuredContent = structuredContent
            )
        } catch (error: HexanaAnalysisException) {
            logAnalysisFailure(toolName, error)
            errorResult(error.code, error.message)
        } catch (error: ToolInputException) {
            errorResult(HexanaAnalysisErrorCode.INVALID_INPUT, error.message ?: "Invalid input")
        } catch (error: Exception) {
            logger.error("Unexpected failure while executing MCP tool `{}`", toolName, error)
            errorResult(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Internal error while executing `$toolName`. See server logs for details."
            )
        }
    }

    private fun errorResult(code: HexanaAnalysisErrorCode, message: String): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(message)),
            isError = true,
            structuredContent = buildJsonObject {
                put("code", JsonPrimitive(code.name))
                put("message", JsonPrimitive(message))
            }
        )
    }

    private fun logAnalysisFailure(toolName: String, error: HexanaAnalysisException) {
        if (error.code == HexanaAnalysisErrorCode.INTERNAL_ERROR || error.cause != null) {
            logger.error(
                "MCP tool `{}` failed with {}: {}",
                toolName,
                error.code.name,
                error.message,
                error
            )
        }
    }

    private fun pathInput(arguments: JsonObject?): WasmInput.FilePath {
        return WasmInput.FilePath(arguments.requireString("path"))
    }

    private fun JsonObject?.requireString(name: String): String {
        val value = this?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return value ?: throw ToolInputException("`$name` is required")
    }

    private fun JsonObject?.intOrDefault(name: String, default: Int): Int {
        val element = this?.get(name) ?: return default
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
    }

    private fun JsonObject?.requireObject(name: String): JsonObject {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        return element as? JsonObject ?: throw ToolInputException("`$name` must be an object")
    }

    private fun JsonObject.requireInt(name: String): Int {
        val element = get(name) ?: throw ToolInputException("`$name` is required")
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        val element = get(name) ?: return null
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
    }

    private fun JsonObject?.nullableInt(name: String): Int? {
        val element = this?.get(name) ?: return null
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
    }

    private fun JsonObject?.booleanOrDefault(name: String, default: Boolean): Boolean {
        val element = this?.get(name) ?: return default
        return element.jsonPrimitive.booleanOrNull
            ?: throw ToolInputException("`$name` must be a boolean")
    }

    private fun JsonObject?.requireIntList(name: String): List<Int> {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        return array.mapIndexed { index, value ->
            value.jsonPrimitive.intOrNull
                ?: throw ToolInputException("`$name[$index]` must be an integer")
        }
    }

    private fun JsonObject?.requireClosedRange(name: String): IntRange {
        val value = requireObject(name)
        return value.requireInt("from")..value.requireInt("to")
    }

    private fun JsonObject?.requireHalfOpenRange(name: String): Pair<Int, Int> {
        val value = requireObject(name)
        val from = value.requireInt("from")
        val toExclusive = value.optionalInt("to") ?: Int.MAX_VALUE
        return from to toExclusive
    }
}

private class ToolInputException(
    override val message: String
) : RuntimeException(message)
