package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

        addTool(
            tool = Tool(
                name = "list_exported_functions",
                description = "List exported functions from a local `.wasm` module using a closed export-range slice.",
                inputSchema = listExportedFunctionsSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.listExportedFunctions(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "functions_for_indices",
                description = "Resolve imported and defined function entries for the requested unified function indices.",
                inputSchema = pathAndIndicesSchema("Unified function indices (`funcidx`) to resolve.")
            )
        ) { request: CallToolRequest ->
            toolHandlers.functionsForIndices(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "list_globals",
                description = "List imported and defined globals declared by a local `.wasm` module.",
                inputSchema = pathToolSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.listGlobals(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "list_types",
                description = "List type entries declared by a local `.wasm` module.",
                inputSchema = pathToolSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.listTypes(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "list_memory",
                description = "List imported and defined memories declared by a local `.wasm` module.",
                inputSchema = pathToolSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.listMemory(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "get_globals_for_indices",
                description = "Return only the requested globals from the unified global index space.",
                inputSchema = pathAndIndicesSchema("Unified global indices (`globalidx`) to return.")
            )
        ) { request: CallToolRequest ->
            toolHandlers.getGlobalsForIndices(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "list_data",
                description = "Return raw data-section entries for a closed data-segment range.",
                inputSchema = listDataSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.listData(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "list_data_segments",
                description = "Return data-segment metadata with range slicing and optional export/global context.",
                inputSchema = listDataSegmentsSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.listDataSegments(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "get_memory_for_indices",
                description = "Return only the requested memories from the unified memory index space.",
                inputSchema = pathAndIndicesSchema("Unified memory indices (`memidx`) to return.")
            )
        ) { request: CallToolRequest ->
            toolHandlers.getMemoryForIndices(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "get_types_for_indices",
                description = "Return only the requested type entries from the type index space.",
                inputSchema = pathAndIndicesSchema("Type indices (`typeidx`) to return.")
            )
        ) { request: CallToolRequest ->
            toolHandlers.getTypesForIndices(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "get_locals_for_functions",
                description = "Return grouped local declarations for the requested unified function indices.",
                inputSchema = pathAndIndicesSchema("Unified function indices (`funcidx`) whose locals should be returned.")
            )
        ) { request: CallToolRequest ->
            toolHandlers.getLocalsForFunctions(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "get_instructions_for_functions",
                description = "Return decoded instruction text for the requested unified function indices.",
                inputSchema = pathAndIndicesSchema("Unified function indices (`funcidx`) whose instructions should be returned.")
            )
        ) { request: CallToolRequest ->
            toolHandlers.getInstructionsForFunctions(request.arguments)
        }

        addTool(
            tool = Tool(
                name = "list_element_segments",
                description = "List element segments declared by a local `.wasm` module.",
                inputSchema = pathToolSchema()
            )
        ) { request: CallToolRequest ->
            toolHandlers.listElementSegments(request.arguments)
        }
    }
}

private fun pathToolSchema(): ToolSchema {
    return ToolSchema(
        properties = buildJsonObject {
            put("path", pathPropertySchema())
        },
        required = listOf("path")
    )
}

private fun listFunctionsSchema(): ToolSchema {
    return ToolSchema(
        properties = buildJsonObject {
            put("path", pathPropertySchema())
            put(
                "offset",
                integerPropertySchema(
                    description = "Zero-based offset into the unified function index space.",
                    minimum = 0
                )
            )
            put(
                "limit",
                integerPropertySchema(
                    description = "Maximum number of functions to return.",
                    minimum = 1
                )
            )
        },
        required = listOf("path")
    )
}

private fun listExportedFunctionsSchema(): ToolSchema {
    return ToolSchema(
        properties = buildJsonObject {
            put("path", pathPropertySchema())
            put(
                "entriesRange",
                closedRangePropertySchema(
                    "Closed zero-based export slice. Both `from` and `to` are required and inclusive."
                )
            )
        },
        required = listOf("path", "entriesRange")
    )
}

private fun pathAndIndicesSchema(indicesDescription: String): ToolSchema {
    return ToolSchema(
        properties = buildJsonObject {
            put("path", pathPropertySchema())
            put(
                "indices",
                buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive(indicesDescription))
                    put(
                        "items",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(0))
                        }
                    )
                }
            )
        },
        required = listOf("path", "indices")
    )
}

private fun listDataSchema(): ToolSchema {
    return ToolSchema(
        properties = buildJsonObject {
            put("path", pathPropertySchema())
            put(
                "entriesRange",
                closedRangePropertySchema(
                    "Closed zero-based data-segment slice. Both `from` and `to` are required and inclusive."
                )
            )
        },
        required = listOf("path", "entriesRange")
    )
}

private fun listDataSegmentsSchema(): ToolSchema {
    return ToolSchema(
        properties = buildJsonObject {
            put("path", pathPropertySchema())
            put(
                "segmentRange",
                halfOpenRangePropertySchema(
                    description = "Half-open zero-based data-segment slice. `from` is required; `to` is optional and treated as exclusive."
                )
            )
            put(
                "includeExports",
                booleanPropertySchema("Whether to include exported symbol names alongside segment metadata.")
            )
            put(
                "includeGlobals",
                booleanPropertySchema("Whether to include globals referenced by segment offset expressions.")
            )
            put(
                "minByteLength",
                integerPropertySchema(
                    description = "Optional minimum byte length filter for returned segments.",
                    minimum = 0
                )
            )
        },
        required = listOf("path", "segmentRange")
    )
}

private fun pathPropertySchema() = buildJsonObject {
    put("type", JsonPrimitive("string"))
    put("description", JsonPrimitive("Path to a local `.wasm` file readable by the Hexana MCP server."))
}

private fun integerPropertySchema(description: String, minimum: Int) = buildJsonObject {
    put("type", JsonPrimitive("integer"))
    put("description", JsonPrimitive(description))
    put("minimum", JsonPrimitive(minimum))
}

private fun booleanPropertySchema(description: String) = buildJsonObject {
    put("type", JsonPrimitive("boolean"))
    put("description", JsonPrimitive(description))
}

private fun closedRangePropertySchema(description: String) = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive(description))
    put(
        "properties",
        buildJsonObject {
            put("from", integerPropertySchema("Inclusive zero-based lower bound.", 0))
            put("to", integerPropertySchema("Inclusive zero-based upper bound.", 0))
        }
    )
    put("required", buildJsonArray {
        add(JsonPrimitive("from"))
        add(JsonPrimitive("to"))
    })
}

private fun halfOpenRangePropertySchema(description: String) = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive(description))
    put(
        "properties",
        buildJsonObject {
            put("from", integerPropertySchema("Inclusive zero-based lower bound.", 0))
            put("to", integerPropertySchema("Optional exclusive upper bound.", 0))
        }
    )
    put("required", buildJsonArray {
        add(JsonPrimitive("from"))
    })
}

internal fun hexanaServerVersion(): String {
    return System.getProperty("hexana.version")
        ?: object {}.javaClass.`package`.implementationVersion
        ?: "dev"
}
