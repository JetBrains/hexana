package org.jetbrains.hexana.cli.core

import kotlinx.serialization.json.JsonElement
import org.jetbrains.hexana.cli.runtime.CliTable
import org.jetbrains.hexana.cli.runtime.CliTerminalWidthException
import org.jetbrains.hexana.cli.runtime.FormatAwareToolOutputRenderer
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderRequest
import org.jetbrains.hexana.cli.runtime.ToolRenderOptions
import org.jetbrains.hexana.cli.runtime.TypedToolOutput
import org.jetbrains.hexana.cli.runtime.TypedToolOutputPlan
import org.jetbrains.hexana.mcp.HexanaJson
import java.lang.Character.getType

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
    fun render(table: CliTable, options: ToolRenderOptions): String {
        val naturalWidths = table.headers.indices.map { index ->
            maxOf(displayWidth(table.headers[index]), table.rows.maxOfOrNull { displayWidth(it[index]) } ?: 0)
        }
        val targetWidth = options.terminalWidth
        if (targetWidth == null || naturalWidth(table, naturalWidths) <= targetWidth) {
            return renderUnbounded(table, naturalWidths)
        }
        val minimumWidth = minimumWidths(table).sum() + 3 * (table.headers.size - 1)
        if (targetWidth < minimumWidth) throw CliTerminalWidthException(targetWidth, minimumWidth)
        val widths = boundedWidths(table, naturalWidths, targetWidth)
        return renderWrapped(table, widths)
    }

    private fun renderUnbounded(table: CliTable, widths: List<Int>): String {
        fun row(cells: List<String>): String = cells.mapIndexed { index, cell ->
            if (index == cells.lastIndex) cell else cell + " ".repeat(widths[index] - displayWidth(cell))
        }.joinToString(" | ")
        return buildString {
            appendLine(row(table.headers))
            appendLine(widths.joinToString("-+-") { "-".repeat(it) })
            table.rows.forEach { appendLine(row(it)) }
        }.removeSuffix("\n")
    }

    private fun naturalWidth(table: CliTable, widths: List<Int>): Int =
        widths.sum() + 3 * (table.headers.size - 1)

    private fun boundedWidths(table: CliTable, naturalWidths: List<Int>, targetWidth: Int): List<Int> {
        val structureWidth = 3 * (naturalWidths.size - 1)
        val minimumWidths = minimumWidths(table)
        val available = maxOf(minimumWidths.sum(), targetWidth - structureWidth)
        val widths = minimumWidths.toMutableList()
        var remaining = available - widths.sum()
        while (remaining > 0) {
            var advanced = false
            naturalWidths.indices.forEach { index ->
                if (remaining > 0 && widths[index] < naturalWidths[index]) {
                    widths[index]++
                    remaining--
                    advanced = true
                }
            }
            if (!advanced) break
        }
        return widths
    }

    private fun minimumWidths(table: CliTable): List<Int> = table.headers.indices.map { index ->
        (listOf(table.headers[index]) + table.rows.map { it[index] }).maxOf { text ->
            maxOf(1, graphemes(text).maxOfOrNull(::clusterDisplayWidth) ?: 1)
        }
    }

    private fun renderWrapped(table: CliTable, widths: List<Int>): String = buildList {
        addAll(physicalLines(table.headers, widths))
        add(widths.joinToString("-+-") { "-".repeat(it) })
        table.rows.forEach { addAll(physicalLines(it, widths)) }
    }.joinToString("\n")

    private fun physicalLines(cells: List<String>, widths: List<Int>): List<String> {
        val wrapped = cells.indices.map { index -> wrap(cells[index], widths[index]) }
        return List(wrapped.maxOf { it.size }) { lineIndex ->
            cells.indices.joinToString(" | ") { column ->
                val text = wrapped[column].getOrElse(lineIndex) { "" }
                if (column == cells.lastIndex) text else text + " ".repeat(widths[column] - displayWidth(text))
            }
        }
    }

    private fun wrap(text: String, width: Int): List<String> {
        val clusters = graphemes(text)
        if (displayClustersWidth(clusters) <= width) return listOf(text)
        val lines = mutableListOf<String>()
        var current = mutableListOf<String>()
        var currentWidth = 0
        var lastSpace = -1
        var discardingBoundaryWhitespace = false
        fun flush(count: Int) {
            lines += current.take(count).joinToString("")
            current = current.drop(count).toMutableList()
            while (current.firstOrNull()?.isWhitespaceCluster() == true) current.removeFirst()
            currentWidth = displayClustersWidth(current)
            lastSpace = current.indexOfLast { it.isWhitespaceCluster() }
        }
        clusters.forEach { cluster ->
            if (discardingBoundaryWhitespace && cluster.isWhitespaceCluster()) return@forEach
            discardingBoundaryWhitespace = false
            val clusterWidth = clusterDisplayWidth(cluster)
            if (currentWidth + clusterWidth > width && current.isNotEmpty()) {
                if (lastSpace >= 0) flush(lastSpace) else flush(current.size)
                if (cluster.isWhitespaceCluster()) {
                    discardingBoundaryWhitespace = true
                    return@forEach
                }
            }
            current += cluster
            currentWidth += clusterWidth
            if (cluster.isWhitespaceCluster()) lastSpace = current.lastIndex
        }
        if (current.isNotEmpty()) lines += current.joinToString("")
        return lines
    }

    private fun String.isWhitespaceCluster(): Boolean = all(Char::isWhitespace)

    internal fun displayWidth(text: String): Int = displayClustersWidth(graphemes(text))
    private fun displayClustersWidth(clusters: List<String>): Int = clusters.sumOf(::clusterDisplayWidth)
    private fun clusterDisplayWidth(cluster: String): Int {
        val codePoints = cluster.codePoints().toArray()
        if (codePoints.any { it == 0x200D || it == 0xFE0F || it == 0x20E3 } || codePoints.any(::isEmoji)) return 2
        val first = codePoints.firstOrNull { !isCombining(it) } ?: return 0
        return if (isWide(first)) 2 else 1
    }

    private fun graphemes(text: String): List<String> {
        val codePoints = text.codePoints().toArray()
        val clusters = mutableListOf<StringBuilder>()
        var index = 0
        while (index < codePoints.size) {
            val cluster = StringBuilder().appendCodePoint(codePoints[index++])
            while (index < codePoints.size && (isCombining(codePoints[index]) || isVariation(codePoints[index]) || isEmojiModifier(codePoints[index]))) {
                cluster.appendCodePoint(codePoints[index++])
            }
            while (index < codePoints.size && codePoints[index] == 0x200D) {
                cluster.appendCodePoint(codePoints[index++])
                if (index < codePoints.size) cluster.appendCodePoint(codePoints[index++])
                while (index < codePoints.size && (isCombining(codePoints[index]) || isVariation(codePoints[index]) || isEmojiModifier(codePoints[index]))) {
                    cluster.appendCodePoint(codePoints[index++])
                }
            }
            if (isRegionalIndicator(codePoints[index - 1]) && index < codePoints.size && isRegionalIndicator(codePoints[index])) {
                cluster.appendCodePoint(codePoints[index++])
            }
            clusters += cluster
        }
        return clusters.map(StringBuilder::toString)
    }

    private fun isCombining(codePoint: Int): Boolean = when (getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }
    private fun isVariation(codePoint: Int): Boolean = codePoint in 0xFE00..0xFE0F
    private fun isEmojiModifier(codePoint: Int): Boolean = codePoint in 0x1F3FB..0x1F3FF
    private fun isEmoji(codePoint: Int): Boolean = codePoint in 0x1F000..0x1FAFF
    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF
    private fun isWide(codePoint: Int): Boolean = codePoint in 0x1100..0x115F || codePoint in 0x2E80..0xA4CF || codePoint in 0xAC00..0xD7A3 || codePoint in 0xF900..0xFAFF || codePoint in 0xFE10..0xFE19 || codePoint in 0xFF01..0xFF60 || codePoint in 0xFFE0..0xFFE6 || codePoint in 0x20000..0x3FFFD
}
