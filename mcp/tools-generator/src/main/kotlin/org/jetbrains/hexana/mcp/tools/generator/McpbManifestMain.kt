package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.hexana.mcp.tools.HexanaMcpbProfileBinding
import org.jetbrains.hexana.mcp.tools.McpProductProfileBinding
import org.jetbrains.hexana.mcp.tools.ToolSpec
import org.jetbrains.hexana.mcp.tools.mcpbTools
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

internal fun mcpbManifestTools(binding: McpProductProfileBinding = HexanaMcpbProfileBinding): List<ToolSpec> =
    mcpbTools(binding)
