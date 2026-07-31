package org.jetbrains.hexana.cli.runtime

import kotlinx.serialization.KSerializer
import java.util.Collections
import java.lang.Character

public class CliTerminalWidthException(public val requestedWidth: Int, public val minimumWidth: Int) : RuntimeException(
    "Requested terminal width $requestedWidth is below this table's minimum width $minimumWidth.",
)

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
        require(this.headers.all { isSafeTableText(it, requireVisible = true) }) { "CLI table headers must be visible plain text." }
        require(this.rows.all { it.size == this.headers.size }) { "Every CLI table row must match the header width." }
        require(this.rows.flatten().all { isSafeTableText(it, requireVisible = false) }) { "CLI table cells must be plain text." }
    }

    private fun isSafeTableText(text: String, requireVisible: Boolean): Boolean {
        var visible = false
        var hasBase = false
        var pendingZwj = false
        for (codePoint in text.codePoints().toArray()) {
            val type = Character.getType(codePoint)
            if (Character.isISOControl(codePoint) || codePoint in 0x202A..0x202E || codePoint in 0x2060..0x2069 || codePoint in 0xE0000..0xE007F || type == Character.FORMAT.toInt() && codePoint != 0x200D) return false
            if (Character.isWhitespace(codePoint)) {
                if (pendingZwj) return false
                hasBase = false
                continue
            }
            if (codePoint == 0x200D) {
                if (!hasBase || pendingZwj) return false
                pendingZwj = true
            } else if (type in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) || codePoint in 0xFE00..0xFE0F) {
                if (!hasBase || pendingZwj) return false
            } else {
                visible = true
                hasBase = true
                pendingZwj = false
            }
        }
        return !pendingZwj && (!requireVisible || visible)
    }
}
