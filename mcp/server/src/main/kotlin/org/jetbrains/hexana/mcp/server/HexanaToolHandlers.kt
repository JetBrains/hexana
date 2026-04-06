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

    private fun JsonObject?.requireString(name: String): String {
        val value = this?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return value ?: throw ToolInputException("`$name` is required")
    }

    private fun JsonObject?.intOrDefault(name: String, default: Int): Int {
        val element = this?.get(name) ?: return default
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
    }
}

private class ToolInputException(
    override val message: String
) : RuntimeException(message)
