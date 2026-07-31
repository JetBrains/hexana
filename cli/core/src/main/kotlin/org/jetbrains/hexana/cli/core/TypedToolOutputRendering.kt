package org.jetbrains.hexana.cli.core

import kotlinx.serialization.json.JsonElement
import org.jetbrains.hexana.cli.runtime.CliTable
import org.jetbrains.hexana.cli.runtime.FormatAwareToolOutputRenderer
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderRequest
import org.jetbrains.hexana.cli.runtime.ToolRenderOptions
import org.jetbrains.hexana.cli.runtime.TypedToolOutput
import org.jetbrains.hexana.cli.runtime.TypedToolOutputPlan
import org.jetbrains.hexana.mcp.HexanaJson

internal class JsonBackedTypedToolOutputRenderer<T>(
    private val toolName: String,
    private val plan: TypedToolOutputPlan<T>,
    private val renderJson: (JsonElement) -> RenderedToolOutput,
    private val renderTable: (CliTable, ToolRenderOptions) -> String,
) : FormatAwareToolOutputRenderer {
    override val supportedFormats: Set<ToolOutputFormat> = plan.supportedFormats

    override fun render(request: ToolOutputRenderRequest): RenderedToolOutput {
        require(request.context.toolName == toolName)
        if (request.options.format == ToolOutputFormat.JSON) {
            return renderJson(request.result)
        }
        val value = HexanaJson.compact.decodeFromJsonElement(plan.serializer, request.result)
        return when (val output = plan.projection(request.options.format).project(value, request.options)) {
            is TypedToolOutput.Text -> RenderedToolOutput.Text(output.text, output.contentType)
            is TypedToolOutput.Table -> RenderedToolOutput.Text(renderTable(output.table, request.options))
            is TypedToolOutput.Binary -> RenderedToolOutput.Binary(output.bytes(), output.contentType)
        }
    }
}

internal object CliTableRenderer {
    fun render(table: CliTable, @Suppress("UNUSED_PARAMETER") options: ToolRenderOptions): String {
        val widths = table.headers.indices.map { index ->
            maxOf(table.headers[index].length, table.rows.maxOfOrNull { it[index].length } ?: 0)
        }
        fun row(cells: List<String>): String = cells.mapIndexed { index, cell ->
            if (index == cells.lastIndex) cell else cell.padEnd(widths[index])
        }.joinToString(" | ")
        return buildString {
            appendLine(row(table.headers))
            appendLine(widths.joinToString("-+-") { "-".repeat(it) })
            table.rows.forEach { appendLine(row(it)) }
        }.trimEnd()
    }
}
