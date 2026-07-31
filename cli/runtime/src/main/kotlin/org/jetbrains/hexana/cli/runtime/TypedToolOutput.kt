package org.jetbrains.hexana.cli.runtime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/** A framework-neutral, type-safe projection of a semantic tool result. */
public sealed interface ToolOutputProjection<T> {
    public val format: ToolOutputFormat

    public fun render(value: T): RenderedToolOutput
}

public class JsonToolOutputProjection<T>(
    private val serializer: KSerializer<T>,
    private val encode: (KSerializer<T>, T) -> String,
) : ToolOutputProjection<T> {
    override val format: ToolOutputFormat = ToolOutputFormat.JSON

    override fun render(value: T): RenderedToolOutput =
        RenderedToolOutput.Text(encode(serializer, value), JSON_CONTENT_TYPE)

    public companion object {
        public const val JSON_CONTENT_TYPE: String = "application/json; charset=utf-8"
    }
}

public class PrettyToolOutputProjection<T>(
    private val document: (T) -> String,
) : ToolOutputProjection<T> {
    override val format: ToolOutputFormat = ToolOutputFormat.PRETTY

    override fun render(value: T): RenderedToolOutput = RenderedToolOutput.Text(document(value))
}

public class TableToolOutputProjection<T>(
    private val table: (T) -> CliTable,
) : ToolOutputProjection<T> {
    override val format: ToolOutputFormat = ToolOutputFormat.TABLE

    override fun render(value: T): RenderedToolOutput = RenderedToolOutput.Text(CliTableRenderer.render(table(value)))
}

public class RawTextToolOutputProjection<T>(
    private val text: (T) -> String,
) : ToolOutputProjection<T> {
    override val format: ToolOutputFormat = ToolOutputFormat.RAW

    override fun render(value: T): RenderedToolOutput = RenderedToolOutput.Text(text(value))
}

public class RawBytesToolOutputProjection<T>(
    private val bytes: (T) -> ByteArray,
    private val contentType: (T) -> String,
) : ToolOutputProjection<T> {
    override val format: ToolOutputFormat = ToolOutputFormat.RAW

    override fun render(value: T): RenderedToolOutput = RenderedToolOutput.Binary(bytes(value), contentType(value))
}

public class TypedToolOutputPlan<T>(
    public val serializer: KSerializer<T>,
    projections: List<ToolOutputProjection<T>>,
) {
    private val projectionsByFormat = projections.associateBy { it.format }

    init {
        require(projectionsByFormat.size == projections.size) { "A typed output plan cannot project one format twice." }
    }

    public val supportedFormats: Set<ToolOutputFormat> = projectionsByFormat.keys

    public fun projection(format: ToolOutputFormat): ToolOutputProjection<T> =
        requireNotNull(projectionsByFormat[format]) { "Output format '${format.cliName}' is not supported." }
}

/** Bridges the current semantic-JSON executor seam to typed output projections without reflection. */
public class JsonBackedTypedToolOutputRenderer<T>(
    private val toolName: String,
    private val decode: (KSerializer<T>, JsonElement) -> T,
    private val plan: TypedToolOutputPlan<T>,
) : ToolOutputRenderer {
    override val supportedFormats: Set<ToolOutputFormat> = plan.supportedFormats

    override fun render(request: ToolOutputRenderRequest): RenderedToolOutput {
        require(request.context.toolName == toolName)
        val projection = plan.projection(request.options.format)
        return projection.render(decode(plan.serializer, request.result))
    }
}

public data class CliTable(
    val headers: List<String>,
    val rows: List<List<String>>,
) {
    init {
        require(headers.isNotEmpty()) { "A CLI table must have at least one header." }
        require(headers.all { it.isNotBlank() && '\n' !in it && '\r' !in it }) { "CLI table headers must be single-line and non-blank." }
        require(rows.all { it.size == headers.size }) { "Every CLI table row must match the header width." }
        require(rows.flatten().none { '\n' in it || '\r' in it }) { "CLI table cells must be single-line plain text." }
    }
}

public object CliTableRenderer {
    public fun render(table: CliTable): String {
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
