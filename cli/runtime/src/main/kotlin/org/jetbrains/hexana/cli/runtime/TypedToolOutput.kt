package org.jetbrains.hexana.cli.runtime

import kotlinx.serialization.KSerializer
import java.util.Collections

/** A type-safe, framework-neutral projection of a semantic tool result. */
public data class TypedToolOutputProjection<T>(
    val format: ToolOutputFormat,
    val project: (T, ToolRenderOptions) -> TypedToolOutput,
) {
    init {
        require(format != ToolOutputFormat.JSON) { "JSON output is rendered from the semantic JSON result directly." }
    }
}

public sealed interface TypedToolOutput {
    public data class Text(
        val text: String,
        val contentType: String = RenderedToolOutput.UTF8_TEXT_CONTENT_TYPE,
    ) : TypedToolOutput

    public data class Table(val table: CliTable) : TypedToolOutput

    public class Binary(
        bytes: ByteArray,
        val contentType: String,
    ) : TypedToolOutput {
        private val bytesCopy = bytes.copyOf()

        public fun bytes(): ByteArray = bytesCopy.copyOf()
    }
}

public class TypedToolOutputPlan<T>(
    public val serializer: KSerializer<T>,
    projections: List<TypedToolOutputProjection<T>>,
) {
    private val projectionsByFormat = projections.associateBy { it.format }

    init {
        require(projectionsByFormat.size == projections.size) { "A typed output plan cannot project one format twice." }
    }

    public val supportedFormats: Set<ToolOutputFormat> = setOf(ToolOutputFormat.JSON) + projectionsByFormat.keys

    public fun projection(format: ToolOutputFormat): TypedToolOutputProjection<T> =
        requireNotNull(projectionsByFormat[format]) { "Output format '${format.cliName}' is not supported." }
}

/** Immutable plain-text table data. Layout and terminal adaptation belong to the consumer. */
public class CliTable(headers: List<String>, rows: List<List<String>>) {
    public val headers: List<String> = Collections.unmodifiableList(headers.toList())
    public val rows: List<List<String>> = Collections.unmodifiableList(rows.map { Collections.unmodifiableList(it.toList()) })

    init {
        require(this.headers.isNotEmpty()) { "A CLI table must have at least one header." }
        require(this.headers.all { it.isNotBlank() && '\n' !in it && '\r' !in it }) { "CLI table headers must be single-line and non-blank." }
        require(this.rows.all { it.size == this.headers.size }) { "Every CLI table row must match the header width." }
        require(this.rows.flatten().none { '\n' in it || '\r' in it }) { "CLI table cells must be single-line plain text." }
    }
}
