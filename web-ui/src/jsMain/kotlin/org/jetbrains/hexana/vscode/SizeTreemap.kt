package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.hexana.shared.TreemapData
import org.jetbrains.hexana.shared.TreemapNode
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent

/**
 * A treemap visualization rendered on an HTML Canvas, displaying [TreemapData]
 * as nested rectangles proportional to each node's size.
 *
 * Clicking on a rectangle invokes [onNodeClicked] with the clicked node's
 * [TreemapNode.treePath].
 */
@Composable
internal fun SizeTreemapCanvas(
    treemapData: TreemapData,
    onNodeClicked: (treePath: List<Int>) -> Unit,
) {
    var layoutRects by remember(treemapData) { mutableStateOf<List<LayoutRect>>(emptyList()) }

    Div({
        style {
            width(100.percent)
            height(100.percent)
            property("position", "relative")
        }
        ref { container ->
            val canvas = kotlinx.browser.document.createElement("canvas") as HTMLCanvasElement
            canvas.style.width = "100%"
            canvas.style.height = "100%"
            canvas.style.display = "block"
            container.appendChild(canvas)

            fun render() {
                val dpr = window.devicePixelRatio
                val rect = canvas.getBoundingClientRect()
                val w = rect.width
                val h = rect.height
                if (w <= 0 || h <= 0) return
                canvas.width = (w * dpr).toInt()
                canvas.height = (h * dpr).toInt()
                val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
                ctx.scale(dpr, dpr)
                ctx.clearRect(0.0, 0.0, w, h)

                // Resolve CSS variables for border and text colors.
                val computedStyle = window.getComputedStyle(canvas)
                val borderColor = computedStyle.getPropertyValue("--hexana-border").ifBlank { "#555" }
                val fgColor = computedStyle.getPropertyValue("--hexana-fg").ifBlank { "#ccc" }

                val rects = mutableListOf<LayoutRect>()
                layoutSquarified(
                    treemapData.root.children,
                    RectBounds(0f, 0f, w.toFloat(), h.toFloat()),
                    0, rects,
                )
                layoutRects = rects

                // Draw filled rectangles.
                for (lr in rects) {
                    val color = treemapColor(lr.colorIndex, lr.depth)
                    ctx.fillStyle = color
                    ctx.fillRect(
                        lr.bounds.x.toDouble(), lr.bounds.y.toDouble(),
                        lr.bounds.w.toDouble(), lr.bounds.h.toDouble(),
                    )
                    ctx.strokeStyle = borderColor
                    ctx.lineWidth = 1.0
                    ctx.strokeRect(
                        lr.bounds.x.toDouble(), lr.bounds.y.toDouble(),
                        lr.bounds.w.toDouble(), lr.bounds.h.toDouble(),
                    )
                }

                // Draw labels on sufficiently large rectangles.
                ctx.fillStyle = fgColor
                ctx.font = "11px sans-serif"
                ctx.asDynamic().textBaseline = "top"
                for (lr in rects) {
                    // Deeper leaves (inside large nested modules/components) only get a label when roomy,
                    // so dense levels such as individual functions stay unlabelled.
                    if (lr.depth > 1 && (lr.bounds.w < MIN_DEEP_LABEL_WIDTH || lr.bounds.h < MIN_DEEP_LABEL_HEIGHT)) continue
                    if (lr.node.children.isNotEmpty()) continue
                    drawNodeLabel(ctx, lr, fgColor)
                }
            }

            render()

            val obs = createResizeObserver { render() }
            obs.observe(container)

            val clickHandler: (Event) -> Unit = { event ->
                val me = event as MouseEvent
                val canvasRect = canvas.getBoundingClientRect()
                val x = me.clientX - canvasRect.left
                val y = me.clientY - canvasRect.top
                val clicked = layoutRects
                    .filter { it.bounds.contains(x.toFloat(), y.toFloat()) }
                    .maxByOrNull { it.depth }
                if (clicked != null) {
                    onNodeClicked(clicked.node.treePath)
                }
            }
            canvas.addEventListener("click", clickHandler)

            onDispose {
                obs.disconnect()
                canvas.removeEventListener("click", clickHandler)
                container.removeChild(canvas)
            }
        }
    })
}

/** Simple JS interop to create a ResizeObserver. */
private fun createResizeObserver(callback: () -> Unit): dynamic {
    val cb: (dynamic) -> Unit = { callback() }
    return js("new ResizeObserver(cb)")
}

// -----------------------------------------------------------------------
// Layout data structures
// -----------------------------------------------------------------------

private class RectBounds(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
}

private class LayoutRect(
    val node: TreemapNode,
    val bounds: RectBounds,
    val depth: Int,
    val colorIndex: Int,
)

private const val MIN_CLICKABLE_SIZE = 4f
private const val MIN_LABEL_WIDTH = 24f
private const val MIN_LABEL_HEIGHT = 14f
private const val LABEL_PADDING = 3f
private const val MIN_DEEP_LABEL_WIDTH = 80f
private const val MIN_DEEP_LABEL_HEIGHT = 18f

// -----------------------------------------------------------------------
// Label drawing
// -----------------------------------------------------------------------

private fun drawNodeLabel(ctx: CanvasRenderingContext2D, lr: LayoutRect, fgColor: String) {
    ctx.fillStyle = fgColor
    if (lr.bounds.w < MIN_LABEL_WIDTH || lr.bounds.h < MIN_LABEL_HEIGHT) return
    val maxWidth = (lr.bounds.w - LABEL_PADDING * 2).toDouble()
    if (maxWidth <= 0) return

    val name = lr.node.name
    val metrics = ctx.measureText(name)
    val label = if (metrics.width > maxWidth) {
        // Try showing just the numeric suffix for repeated elements.
        val trimmed = name.trimEnd()
        val lastSpace = trimmed.lastIndexOf(' ')
        val number = if (lastSpace >= 0) trimmed.substring(lastSpace + 1) else ""
        // Only a numeric suffix ("func 453" -> "f#453"); "Custom: .debug_line" must not become "C#.debug_line".
        if (number.isNotEmpty() && number.all(Char::isDigit)) {
            val suffix = name.substring(0, 1) + "#" + number
            val suffixMetrics = ctx.measureText(suffix)
            if (suffixMetrics.width <= maxWidth) suffix else return
        } else return
    } else name

    ctx.fillText(
        label,
        (lr.bounds.x + LABEL_PADDING).toDouble(),
        (lr.bounds.y + LABEL_PADDING).toDouble(),
        maxWidth,
    )
}

// -----------------------------------------------------------------------
// Squarified treemap layout (ported from IntelliJ SizeTreemap.kt)
// -----------------------------------------------------------------------

private fun layoutSquarified(
    nodes: List<TreemapNode>,
    bounds: RectBounds,
    depth: Int,
    out: MutableList<LayoutRect>,
    parentColorIndex: Int = -1,
) {
    if (nodes.isEmpty() || bounds.w <= 0f || bounds.h <= 0f) return

    val totalSize = nodes.sumOf { it.size.coerceAtLeast(0) }.toFloat()
    if (totalSize <= 0f) return

    val area = bounds.w * bounds.h

    // Pre-filter negligible items.
    val minFraction = 0.002f
    val significantNodes = nodes.mapIndexed { i, n -> Pair(i, n) }
        .filter { it.second.size.coerceAtLeast(0).toFloat() / totalSize >= minFraction }
    val significantTotal = significantNodes.sumOf { it.second.size.coerceAtLeast(0) }.toFloat()
    if (significantTotal <= 0f) return

    data class Item(val node: TreemapNode, val scaledArea: Float, val originalIndex: Int)

    val minArea = MIN_CLICKABLE_SIZE * MIN_CLICKABLE_SIZE
    val items = significantNodes.map { (i, n) ->
        Item(n, (n.size.coerceAtLeast(0).toFloat() / significantTotal) * area, i)
    }.filter { it.scaledArea >= minArea }.sortedByDescending { it.scaledArea }

    var remaining = items.toList()
    var cx = bounds.x
    var cy = bounds.y
    var cw = bounds.w
    var ch = bounds.h

    while (remaining.isNotEmpty()) {
        val isWide = cw >= ch
        val sideLength = if (isWide) ch else cw
        if (sideLength <= 0f) break

        val row = mutableListOf(remaining.first())
        var rowArea = remaining.first().scaledArea
        remaining = remaining.drop(1)

        fun worstAspectRatio(rowItems: List<Item>, rowTotalArea: Float): Float {
            val rowLength = rowTotalArea / sideLength
            if (rowLength <= 0f) return Float.MAX_VALUE
            return rowItems.maxOf { item ->
                val itemLength = item.scaledArea / rowLength
                maxOf(rowLength / itemLength, itemLength / rowLength)
            }
        }

        var currentWorst = worstAspectRatio(row, rowArea)

        while (remaining.isNotEmpty()) {
            val candidate = remaining.first()
            val newRowArea = rowArea + candidate.scaledArea
            val newWorst = worstAspectRatio(row + candidate, newRowArea)
            if (newWorst <= currentWorst) {
                row.add(candidate)
                rowArea = newRowArea
                currentWorst = newWorst
                remaining = remaining.drop(1)
            } else break
        }

        val rowLength = rowArea / sideLength
        var offset = 0f

        for (item in row) {
            val itemLength = if (rowLength > 0f) item.scaledArea / rowLength else 0f
            val colorIdx = if (parentColorIndex >= 0) parentColorIndex else item.originalIndex

            val rect = if (isWide) {
                RectBounds(cx, cy + offset, rowLength, itemLength)
            } else {
                RectBounds(cx + offset, cy, itemLength, rowLength)
            }

            if (rect.w < MIN_CLICKABLE_SIZE || rect.h < MIN_CLICKABLE_SIZE) {
                offset += itemLength
                continue
            }

            out.add(LayoutRect(item.node, rect, depth, colorIdx))

            if (item.node.children.isNotEmpty() && rect.w > MIN_CLICKABLE_SIZE && rect.h > MIN_CLICKABLE_SIZE) {
                val innerRect = RectBounds(
                    rect.x + 1f, rect.y + 1f,
                    rect.w - 2f, rect.h - 2f,
                )
                layoutSquarified(item.node.children, innerRect, depth + 1, out, colorIdx)
            }

            offset += itemLength
        }

        if (isWide) {
            cx += rowLength
            cw -= rowLength
        } else {
            cy += rowLength
            ch -= rowLength
        }
    }
}

// -----------------------------------------------------------------------
// Color palette — VSCode is always dark theme
// -----------------------------------------------------------------------

private val treemapPalette = listOf(
    "rgba(46, 79, 115, 1.0)",   // #2E4F73
    "rgba(154, 92, 27, 1.0)",   // #9A5C1B
    "rgba(140, 53, 55, 1.0)",   // #8C3537
    "rgba(74, 122, 118, 1.0)",  // #4A7A76
    "rgba(55, 104, 50, 1.0)",   // #376832
    "rgba(154, 132, 48, 1.0)",  // #9A8430
    "rgba(110, 77, 102, 1.0)",  // #6E4D66
    "rgba(154, 99, 106, 1.0)",  // #9A636A
    "rgba(99, 73, 60, 1.0)",    // #63493C
    "rgba(112, 107, 104, 1.0)", // #706B68
)

private val treemapPaletteAlpha065 = listOf(
    "rgba(46, 79, 115, 0.65)",
    "rgba(154, 92, 27, 0.65)",
    "rgba(140, 53, 55, 0.65)",
    "rgba(74, 122, 118, 0.65)",
    "rgba(55, 104, 50, 0.65)",
    "rgba(154, 132, 48, 0.65)",
    "rgba(110, 77, 102, 0.65)",
    "rgba(154, 99, 106, 0.65)",
    "rgba(99, 73, 60, 0.65)",
    "rgba(112, 107, 104, 0.65)",
)

private val treemapPaletteAlpha045 = listOf(
    "rgba(46, 79, 115, 0.45)",
    "rgba(154, 92, 27, 0.45)",
    "rgba(140, 53, 55, 0.45)",
    "rgba(74, 122, 118, 0.45)",
    "rgba(55, 104, 50, 0.45)",
    "rgba(154, 132, 48, 0.45)",
    "rgba(110, 77, 102, 0.45)",
    "rgba(154, 99, 106, 0.45)",
    "rgba(99, 73, 60, 0.45)",
    "rgba(112, 107, 104, 0.45)",
)

private fun treemapColor(colorIndex: Int, depth: Int): String {
    val idx = colorIndex % treemapPalette.size
    return when (depth) {
        0 -> treemapPalette[idx]
        1 -> treemapPaletteAlpha065[idx]
        else -> treemapPaletteAlpha045[idx]
    }
}
