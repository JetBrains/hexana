package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaAnalysisServices

fun createHexanaMcpServer(
    analysisService: HexanaAnalysisService = HexanaAnalysisServices.default
): Server {
    val toolHandlers = HexanaToolHandlers(analysisService)

    return Server(
        serverInfo = Implementation("hexana", hexanaServerVersion()),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    ) {
        addTool(
            tool = Tool(
                name = "summarize_module",
                description = "Summarize a local WebAssembly module from a `.wasm` file path.",
                inputSchema = pathToolSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.summarizeModule(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "list_imports",
                description = "List imported functions declared by a local `.wasm` module.",
                inputSchema = pathToolSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.listImports(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "list_exports",
                description = "List exports declared by a local `.wasm` module.",
                inputSchema = pathToolSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.listExports(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "list_functions",
                description = "List functions from a local `.wasm` module using offset/limit pagination.",
                inputSchema = listFunctionsSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.listFunctions(request.arguments)
        }
    }
}

private fun pathToolSchema(): ToolSchema {
    return ToolSchema(
        properties = buildJsonObject {
            put(
                "path",
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Path to a local `.wasm` file readable by the Hexana MCP server."))
                }
            )
        },
        required = listOf("path")
    )
}

private fun listFunctionsSchema(): ToolSchema {
    return ToolSchema(
        properties = buildJsonObject {
            put(
                "path",
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Path to a local `.wasm` file readable by the Hexana MCP server."))
                }
            )
            put(
                "offset",
                buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Zero-based offset into the unified function index space."))
                    put("minimum", JsonPrimitive(0))
                }
            )
            put(
                "limit",
                buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum number of functions to return."))
                    put("minimum", JsonPrimitive(1))
                }
            )
        },
        required = listOf("path")
    )
}

internal fun hexanaServerVersion(): String {
    return System.getProperty("hexana.version")
        ?: object {}.javaClass.`package`.implementationVersion
        ?: "dev"
}
