package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import org.jetbrains.hexana.shared.TreemapData
import org.jetbrains.hexana.shared.TreemapNode
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * A treemap visualization that displays [TreemapData] as nested rectangles
 * proportional to each node's size, similar to disk usage utilities.
 *
 * Clicking on a rectangle invokes [onNodeClicked] with the clicked node's
 * [TreemapNode.treePath], which the caller uses to expand and select the
 * corresponding row in the tree table.
 */
@Composable
internal fun SizeTreemap(
    treemapData: TreemapData,
    onNodeClicked: (treePath: List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val isDark = JewelTheme.isDark
    val textColor = JewelTheme.globalColors.text.normal
    val borderColor = JewelTheme.globalColors.borders.normal

    // Layout rectangles are computed once per data/size change.
    var layoutRects by remember(treemapData) { mutableStateOf<List<LayoutRect>>(emptyList()) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(treemapData) {
                detectTapGestures { offset ->
                    // Find the deepest (smallest) rectangle containing the click point.
                    val clicked = layoutRects
                        .filter { it.rect.contains(offset) }
                        .maxByOrNull { it.depth }
                    if (clicked != null) {
                        onNodeClicked(clicked.node.treePath)
                    }
                }
            }
    ) {
        val canvasRect = Rect(Offset.Zero, Size(size.width, size.height))
        if (canvasRect.width <= 0f || canvasRect.height <= 0f) return@Canvas

        val rects = mutableListOf<LayoutRect>()
        layoutSquarified(treemapData.root.children, canvasRect, 0, rects)
        layoutRects = rects

        for (lr in rects) {
            val color = treemapColor(lr.colorIndex, lr.depth, isDark)
            drawRect(color, topLeft = lr.rect.topLeft, size = lr.rect.size)
            drawRect(borderColor, topLeft = lr.rect.topLeft, size = lr.rect.size, style = Stroke(1f))
        }

        // Draw labels on rectangles large enough to fit text.
        // Skip labels for group nodes that have visible children to avoid overlap.
        for (lr in rects) {
            // Deeper leaves (inside large nested modules/components) only get a label when roomy,
            // so dense levels such as individual functions stay unlabelled.
            if (lr.depth > 1 && (lr.rect.width < MIN_DEEP_LABEL_WIDTH || lr.rect.height < MIN_DEEP_LABEL_HEIGHT)) continue
            if (lr.node.children.isNotEmpty()) continue
            drawNodeLabel(lr, textMeasurer, textColor)
        }
    }
}

/**
 * A laid-out rectangle in the treemap, linking back to its [TreemapNode].
 */
private class LayoutRect(
    val node: TreemapNode,
    val rect: Rect,
    val depth: Int,
    val colorIndex: Int,
)

private const val MIN_CLICKABLE_SIZE = 4f
private const val MIN_LABEL_WIDTH = 24f
private const val MIN_LABEL_HEIGHT = 14f
private const val LABEL_PADDING = 3f
private const val MIN_DEEP_LABEL_WIDTH = 80f
private const val MIN_DEEP_LABEL_HEIGHT = 18f

private fun DrawScope.drawNodeLabel(
    lr: LayoutRect,
    textMeasurer: TextMeasurer,
    textColor: Color,
) {
    if (lr.rect.width < MIN_LABEL_WIDTH || lr.rect.height < MIN_LABEL_HEIGHT) return
    val maxWidth = (lr.rect.width - LABEL_PADDING * 2).toInt().coerceAtLeast(1)
    val maxHeight = (lr.rect.height - LABEL_PADDING * 2).toInt().coerceAtLeast(1)
    val style = TextStyle(color = textColor, fontSize = 11.sp)
    val result = textMeasurer.measure(
        text = lr.node.name,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxWidth, maxHeight = maxHeight),
    )
    // If the full name doesn't fit without ellipsis, try showing just the numeric
    // suffix (e.g. "453" instead of "func 453") for repeated elements.
    val measured = if (result.hasVisualOverflow) {
        val numberSuffix = lr.node.name.trimEnd().let { name ->
            val lastSpace = name.lastIndexOf(' ')
            // Only a numeric suffix ("func 453" -> "f#453"); "Custom: .debug_line" must not become "C#.debug_line".
            if (lastSpace >= 0) name.substring(lastSpace + 1).takeIf { it.isNotEmpty() && it.all(Char::isDigit) } else null
        }
        if (numberSuffix != null) {
            val shortResult = textMeasurer.measure(
                text = lr.node.name.substring(0, 1) + "#" + numberSuffix,
                style = style,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                constraints = Constraints(maxWidth = maxWidth, maxHeight = maxHeight),
            )
            if (!shortResult.hasVisualOverflow && shortResult.size.width <= maxWidth) shortResult else return
        } else result
    } else result
    if (measured.size.width > maxWidth || measured.size.height > maxHeight) return
    drawText(
        measured,
        topLeft = Offset(lr.rect.left + LABEL_PADDING, lr.rect.top + LABEL_PADDING),
    )
}

/**
 * Squarified treemap layout algorithm.
 *
 * Partitions [nodes] into the given [bounds] rectangle, producing rectangles
 * with aspect ratios as close to 1:1 as possible. Recurses into children
 * for nodes that have them.
 */
private fun layoutSquarified(
    nodes: List<TreemapNode>,
    bounds: Rect,
    depth: Int,
    out: MutableList<LayoutRect>,
    parentColorIndex: Int = -1,
) {
    if (nodes.isEmpty() || bounds.width <= 0f || bounds.height <= 0f) return

    val totalSize = nodes.sumOf { it.size.coerceAtLeast(0) }.toFloat()
    if (totalSize <= 0f) return

    // Scale node sizes to fill the bounds area.
    val area = bounds.width * bounds.height
    data class Item(val node: TreemapNode, val scaledArea: Float, val originalIndex: Int)

    // Pre-filter negligible items before layout so their space is redistributed
    // to larger elements instead of leaving empty gaps.
    val minFraction = 0.002f // items smaller than 0.2% of total are negligible
    val significantNodes = nodes.mapIndexed { i, n -> Pair(i, n) }
        .filter { it.second.size.coerceAtLeast(0).toFloat() / totalSize >= minFraction }
    val significantTotal = significantNodes.sumOf { it.second.size.coerceAtLeast(0) }.toFloat()
    if (significantTotal <= 0f) return

    val minArea = MIN_CLICKABLE_SIZE * MIN_CLICKABLE_SIZE
    val items = significantNodes.map { (i, n) ->
        Item(n, (n.size.coerceAtLeast(0).toFloat() / significantTotal) * area, i)
    }.filter { it.scaledArea >= minArea }.sortedByDescending { it.scaledArea }

    var remaining = items.toList()
    var currentBounds = bounds

    while (remaining.isNotEmpty()) {
        val isWide = currentBounds.width >= currentBounds.height
        val sideLength = if (isWide) currentBounds.height else currentBounds.width

        if (sideLength <= 0f) break

        // Greedily add items to the current row while the worst aspect ratio improves.
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
            } else {
                break
            }
        }

        // Lay out the row.
        val rowLength = rowArea / sideLength
        var offset = 0f

        for (item in row) {
            val itemLength = if (rowLength > 0f) item.scaledArea / rowLength else 0f
            val colorIdx = if (parentColorIndex >= 0) parentColorIndex else item.originalIndex

            val rect = if (isWide) {
                Rect(
                    left = currentBounds.left,
                    top = currentBounds.top + offset,
                    right = currentBounds.left + rowLength,
                    bottom = currentBounds.top + offset + itemLength,
                )
            } else {
                Rect(
                    left = currentBounds.left + offset,
                    top = currentBounds.top,
                    right = currentBounds.left + offset + itemLength,
                    bottom = currentBounds.top + rowLength,
                )
            }

            // Skip elements too small to be reasonably clickable.
            if (rect.width < MIN_CLICKABLE_SIZE || rect.height < MIN_CLICKABLE_SIZE) {
                offset += itemLength
                continue
            }

            out.add(LayoutRect(item.node, rect, depth, colorIdx))

            // Recurse into children.
            if (item.node.children.isNotEmpty() && rect.width > MIN_CLICKABLE_SIZE && rect.height > MIN_CLICKABLE_SIZE) {
                val innerRect = Rect(
                    left = rect.left + 1f,
                    top = rect.top + 1f,
                    right = rect.right - 1f,
                    bottom = rect.bottom - 1f,
                )
                layoutSquarified(item.node.children, innerRect, depth + 1, out, colorIdx)
            }

            offset += itemLength
        }

        // Shrink bounds for the next row.
        currentBounds = if (isWide) {
            Rect(
                left = currentBounds.left + rowLength,
                top = currentBounds.top,
                right = currentBounds.right,
                bottom = currentBounds.bottom,
            )
        } else {
            Rect(
                left = currentBounds.left,
                top = currentBounds.top + rowLength,
                right = currentBounds.right,
                bottom = currentBounds.bottom,
            )
        }
    }
}

/** Palette of muted colors for top-level treemap sections (light theme). */
private val treemapPaletteLight = listOf(
    Color(0xFF4E79A7),
    Color(0xFFF28E2B),
    Color(0xFFE15759),
    Color(0xFF76B7B2),
    Color(0xFF59A14F),
    Color(0xFFEDC948),
    Color(0xFFB07AA1),
    Color(0xFFFF9DA7),
    Color(0xFF9C755F),
    Color(0xFFBAB0AC),
)

/** Darker palette for dark theme — lower brightness to avoid glare on dark backgrounds. */
private val treemapPaletteDark = listOf(
    Color(0xFF2E4F73),
    Color(0xFF9A5C1B),
    Color(0xFF8C3537),
    Color(0xFF4A7A76),
    Color(0xFF376832),
    Color(0xFF9A8430),
    Color(0xFF6E4D66),
    Color(0xFF9A636A),
    Color(0xFF63493C),
    Color(0xFF706B68),
)

/**
 * Returns a color for a treemap rectangle based on its top-level section
 * index, nesting depth, and whether the dark theme is active.
 */
private fun treemapColor(colorIndex: Int, depth: Int, isDark: Boolean): Color {
    val palette = if (isDark) treemapPaletteDark else treemapPaletteLight
    val base = palette[colorIndex % palette.size]
    return when (depth) {
        0 -> base
        1 -> base.copy(alpha = if (isDark) 0.65f else 0.55f)
        else -> base.copy(alpha = if (isDark) 0.45f else 0.35f)
    }
}
