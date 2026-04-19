package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaAnalysisServices
import org.jetbrains.hexana.mcp.server.gen.registerHexanaTools

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
        registerHexanaTools(toolHandlers)
    }
}

private const val WASM_INPUT_RUNTIME_VALIDATION =
    "Runtime validation requires exactly one of `path`, `bytesBase64`, or `bytes`."

// The `*Schema()` helpers below are `internal` (not `private`) because `HexanaToolDispatcher.kt` —
// generated into the same package from `mcp/tools-generator` — calls them at registration time.
// Keep them `internal` and in-package; do not inline into the generated dispatcher.
internal fun wasmInputToolSchema(
    required: List<String> = emptyList(),
    extraProperties: JsonObjectBuilder.() -> Unit = {}
): ToolSchema {
    return ToolSchema(
        properties = buildJsonObject {
            putWasmInputProperties()
            extraProperties()
        },
        required = required
    )
}

internal fun listFunctionsSchema(): ToolSchema {
    return wasmInputToolSchema {
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
    }
}

internal fun listExportedFunctionsSchema(): ToolSchema {
    return wasmInputToolSchema(required = listOf("entriesRange")) {
        put(
            "entriesRange",
            closedRangePropertySchema(
                "Closed zero-based export slice. Both `from` and `to` are required and inclusive."
            )
        )
    }
}

internal fun pathAndIndicesSchema(indicesDescription: String): ToolSchema {
    return wasmInputToolSchema(required = listOf("indices")) {
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
    }
}

internal fun listDataSchema(): ToolSchema {
    return wasmInputToolSchema(required = listOf("entriesRange")) {
        put(
            "entriesRange",
            closedRangePropertySchema(
                "Closed zero-based data-segment slice. Both `from` and `to` are required and inclusive."
            )
        )
    }
}

internal fun listDataSegmentsSchema(): ToolSchema {
    return wasmInputToolSchema(required = listOf("segmentRange")) {
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
    }
}

private fun JsonObjectBuilder.putWasmInputProperties() {
    put("path", pathPropertySchema())
    put("bytesBase64", bytesBase64PropertySchema())
    put("bytes", bytesPropertySchema())
}

private fun pathPropertySchema() = buildJsonObject {
    put("type", JsonPrimitive("string"))
    put(
        "description",
        JsonPrimitive(
            "Path to a local `.wasm` file readable by the Hexana MCP server. $WASM_INPUT_RUNTIME_VALIDATION"
        )
    )
}

private fun bytesBase64PropertySchema() = buildJsonObject {
    put("type", JsonPrimitive("string"))
    put(
        "description",
        JsonPrimitive("Base64-encoded `.wasm` bytes. $WASM_INPUT_RUNTIME_VALIDATION")
    )
    put("maxLength", JsonPrimitive(maxBase64Length(HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES)))
}

private fun bytesPropertySchema() = buildJsonObject {
    put("type", JsonPrimitive("array"))
    put(
        "description",
        JsonPrimitive("Raw `.wasm` bytes as integers in the range 0..255. $WASM_INPUT_RUNTIME_VALIDATION")
    )
    put(
        "items",
        buildJsonObject {
            put("type", JsonPrimitive("integer"))
            put("minimum", JsonPrimitive(0))
            put("maximum", JsonPrimitive(255))
        }
    )
    put("maxItems", JsonPrimitive(HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES))
}

private fun maxBase64Length(maxBytes: Int): Int = ((maxBytes + 2) / 3) * 4

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
