package org.jetbrains.hexana.mcp.tooling

import com.intellij.mcpserver.McpToolSchema
import com.intellij.mcpserver.impl.util.asTools
import kotlinx.serialization.json.*
import org.jetbrains.hexana.mcp.HexanaToolset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.ceil

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val compactJson = Json {
    prettyPrint = false
    explicitNulls = false
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    explicitNulls = false
}

private data class ToolMetrics(
    val name: String,
    val descriptionChars: Int,
    val inputSchemaChars: Int,
    val outputSchemaChars: Int,
    val totalChars: Int,
    val totalBytes: Int,
    val estimatedTokens: Int,
    val toolJson: JsonObject,
)

fun main(args: Array<String>) {
    val outputDir = args.singleOrNull()?.let(Path::of)
        ?: Path.of("build/reports/mcp-tokens")

    val toolDescriptors = HexanaToolset()
        .asTools()
        .map { it.descriptor }
        .sortedBy { it.name }

    val tools = toolDescriptors.map(::measureTool)

    val combinedJson = buildJsonObject {
        put("tools", JsonArray(tools.map { it.toolJson }))
    }
    val combinedCompactJson = compactJson.encodeToString(JsonObject.serializer(), combinedJson)
    val combinedBytes = combinedCompactJson.toByteArray(Charsets.UTF_8).size

    val report = buildJsonObject {
        put("generatedAt", Instant.now().toString())
        put("note", "estimatedTokens is a rough estimate using ceil(characters / 4.0), not a model-specific tokenizer")
        put("toolCount", tools.size)
        put("combined", buildJsonObject {
            put("characters", combinedCompactJson.length)
            put("utf8Bytes", combinedBytes)
            put("estimatedTokens", estimateTokens(combinedCompactJson.length))
        })
        put("tools", JsonArray(tools.map { metrics ->
            buildJsonObject {
                put("name", metrics.name)
                put("descriptionChars", metrics.descriptionChars)
                put("inputSchemaChars", metrics.inputSchemaChars)
                put("outputSchemaChars", metrics.outputSchemaChars)
                put("totalChars", metrics.totalChars)
                put("utf8Bytes", metrics.totalBytes)
                put("estimatedTokens", metrics.estimatedTokens)
                put("tool", metrics.toolJson)
            }
        }))
    }

    val normalizedOutputDir = outputDir.toAbsolutePath().normalize()
    Files.createDirectories(normalizedOutputDir)

    val reportJsonPath = normalizedOutputDir.resolve("report.json")
    val toolsListJsonPath = normalizedOutputDir.resolve("tools-list.json")
    val summaryCsvPath = normalizedOutputDir.resolve("summary.csv")

    Files.writeString(reportJsonPath, prettyJson.encodeToString(JsonObject.serializer(), report))
    Files.writeString(
        toolsListJsonPath,
        prettyJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("tools", JsonArray(tools.map { it.toolJson }))
            }
        )
    )
    Files.writeString(summaryCsvPath, buildSummaryCsv(tools))

    println("Wrote MCP token reports to $normalizedOutputDir")
    println("  JSON summary: $reportJsonPath")
    println("  tools/list view: $toolsListJsonPath")
    println("  CSV summary: $summaryCsvPath")
    println("Tool count: ${tools.size}")
    println("Combined characters: ${combinedCompactJson.length}")
    println("Combined UTF-8 bytes: $combinedBytes")
    println("Combined estimated tokens: ${estimateTokens(combinedCompactJson.length)}")
    println()
    println("Top tools by estimated tokens:")
    tools.sortedByDescending { it.estimatedTokens }.take(10).forEach { metrics ->
        println("  ${metrics.name}: ${metrics.estimatedTokens} est tokens, ${metrics.totalChars} chars, ${metrics.totalBytes} bytes")
    }
}

private fun measureTool(descriptor: com.intellij.mcpserver.McpToolDescriptor): ToolMetrics {
    val inputSchemaJson = schemaJson(descriptor.inputSchema)
    val outputSchemaJson = descriptor.outputSchema?.let(::schemaJson)
    val toolJson = buildJsonObject {
        put("name", descriptor.name)
        put("description", descriptor.description)
        put("inputSchema", inputSchemaJson)
        outputSchemaJson?.let { put("outputSchema", it) }
    }
    val toolCompactJson = compactJson.encodeToString(JsonObject.serializer(), toolJson)
    return ToolMetrics(
        name = descriptor.name,
        descriptionChars = descriptor.description.length,
        inputSchemaChars = compactJson.encodeToString(JsonObject.serializer(), inputSchemaJson).length,
        outputSchemaChars = outputSchemaJson?.let { compactJson.encodeToString(JsonObject.serializer(), it).length }
            ?: 0,
        totalChars = toolCompactJson.length,
        totalBytes = toolCompactJson.toByteArray(Charsets.UTF_8).size,
        estimatedTokens = estimateTokens(toolCompactJson.length),
        toolJson = toolJson,
    )
}

private fun schemaJson(schema: McpToolSchema): JsonObject {
    return buildJsonObject {
        put("type", "object")
        put("properties", schema.propertiesSchema)
        put("required", JsonArray(schema.requiredProperties.toList().map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
}

private fun buildSummaryCsv(tools: List<ToolMetrics>): String {
    val header = "name,description_chars,input_schema_chars,output_schema_chars,total_chars,utf8_bytes,estimated_tokens"
    val rows = tools.map { metrics ->
        listOf(
            metrics.name,
            metrics.descriptionChars.toString(),
            metrics.inputSchemaChars.toString(),
            metrics.outputSchemaChars.toString(),
            metrics.totalChars.toString(),
            metrics.totalBytes.toString(),
            metrics.estimatedTokens.toString(),
        ).joinToString(",") { csvCell(it) }
    }
    return (listOf(header) + rows).joinToString(separator = System.lineSeparator())
}

private fun csvCell(value: String): String {
    val escaped = value.replace("\"", "\"\"")
    return "\"$escaped\""
}

private fun estimateTokens(characters: Int): Int = ceil(characters / 4.0).toInt()
