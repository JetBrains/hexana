package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.hexana.mcp.tools.HexanaTools
import org.jetbrains.hexana.mcp.tools.ToolSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 4) {
        System.err.println("Usage: mcpb-manifest-generator <template> <output> <version> <platform>")
        exitProcess(1)
    }

    generateMcpbManifest(
        template = Path.of(args[0]),
        output = Path.of(args[1]),
        version = args[2],
        platform = args[3],
    )
}

internal fun generateMcpbManifest(template: Path, output: Path, version: String, platform: String) {
    val templateJson = Json.parseToJsonElement(Files.readString(template)).jsonObject
    val manifest = buildJsonObject {
        for ((key, value) in templateJson) {
            when (key) {
                "version" -> put(key, JsonPrimitive(version))
                "compatibility" -> put(key, renderCompatibility(value.jsonObject, platform))
                "tools_generated" -> put(key, JsonPrimitive(true))
                "tools" -> put(key, renderTools())
                else -> put(key, value)
            }
        }
    }

    output.parent?.let(Files::createDirectories)
    Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), manifest) + "\n")
    println("Wrote Claude Desktop MCPB manifest with ${mcpbManifestTools().size} tools to $output")
}

private fun renderCompatibility(template: JsonObject, platform: String): JsonObject = buildJsonObject {
    for ((key, value) in template) {
        if (key == "platforms") {
            put(key, JsonArray(listOf(JsonPrimitive(platform))))
        } else {
            put(key, value)
        }
    }
}

private fun renderTools(): JsonArray = JsonArray(
    mcpbManifestTools().map { tool ->
        buildJsonObject {
            put("name", JsonPrimitive(tool.name))
            put("description", JsonPrimitive(tool.summary.trim()))
        }
    }
)

internal val mcpbManifestToolNames = listOf(
    "summarize_binary",
    "summarize_module",
    "list_enabled_features",
    "get_entrypoint_policy",
    "list_required_proposals",
    "diff_required_proposals",
    "check_runtime_compatibility",
    "check_component_compatibility",
    "plan_abi_binding_regeneration",
    "check_memory_contract",
    "audit_global_state_policy",
    "audit_local_stack_budget",
    "list_imports",
    "list_exports",
    "search_exports",
    "list_exported_functions",
    "list_functions",
    "resolve_function_signature",
    "disassemble_function",
    "get_function_call_graph",
    "search_functions_by_name",
    "resolve_wasm_frames",
    "resolve_function_symbols",
    "resolve_source_locations",
    "list_memory",
    "list_globals",
    "list_element_segments",
    "list_data_segments",
    "audit_data_payload_policy",
)

internal fun mcpbManifestTools(): List<ToolSpec> {
    val toolsByName = HexanaTools.associateBy { it.name }
    return mcpbManifestToolNames.map { name ->
        toolsByName[name] ?: error("MCPB manifest tool `$name` is missing from the shared Hexana tool catalog.")
    }
}
