package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

private const val BYTES_PER_ROW = 16
internal const val ROW_HEIGHT_PX = 20
private const val OVERSCAN_ROWS = 5

/**
 * Selection state for the hex viewer, tracking a range of selected bytes
 * by absolute byte index.
 */
class HexSelectionState {
    var anchorIndex by mutableStateOf(-1)
    var activeIndex by mutableStateOf(-1)
    var cursorIndex by mutableStateOf(-1)

    val hasSelection: Boolean get() = anchorIndex >= 0 && activeIndex >= 0 && anchorIndex != activeIndex

    val selectionStart: Int get() = if (anchorIndex <= activeIndex) anchorIndex else activeIndex
    val selectionEnd: Int get() = if (anchorIndex <= activeIndex) activeIndex else anchorIndex

    fun contains(absoluteIndex: Int): Boolean {
        if (anchorIndex < 0 || activeIndex < 0) return false
        return absoluteIndex in selectionStart..selectionEnd
    }

    fun reset() {
        anchorIndex = -1
        activeIndex = -1
    }

    fun startSelection(index: Int) {
        anchorIndex = index
        activeIndex = index
        cursorIndex = index
    }

    fun extendSelection(index: Int) {
        activeIndex = index
        cursorIndex = index
    }
}

/**
 * Compose for Web hex viewer panel with virtual scrolling and byte selection.
 *
 * Renders the raw binary content in a classic hex-dump layout:
 * offset | hex bytes | ASCII decode.
 *
 * Only rows visible in the viewport (plus a small overscan) are rendered.
 * Selection is supported via click, shift-click, drag, and keyboard navigation.
 */
@Composable
fun HexViewerPanel(bytes: ByteArray, selection: HexSelectionState = remember { HexSelectionState() }) {
    val totalRows = (bytes.size + BYTES_PER_ROW - 1) / BYTES_PER_ROW
    val totalHeight = totalRows * ROW_HEIGHT_PX

    var scrollTop by remember { mutableStateOf(0) }
    var viewportHeight by remember { mutableStateOf(600) }
    var isDragging by remember { mutableStateOf(false) }

    val firstVisibleRow = (scrollTop / ROW_HEIGHT_PX - OVERSCAN_ROWS).coerceAtLeast(0)
    val lastVisibleRow = ((scrollTop + viewportHeight) / ROW_HEIGHT_PX + OVERSCAN_ROWS).coerceAtMost(totalRows - 1)
    val topSpacerHeight = firstVisibleRow * ROW_HEIGHT_PX
    val bottomSpacerHeight = ((totalRows - 1 - lastVisibleRow) * ROW_HEIGHT_PX).coerceAtLeast(0)

    // Keyboard handler
    DisposableEffect(Unit) {
        val handler: (Event) -> Unit = handler@{ event ->
            val ke = event as? KeyboardEvent ?: return@handler
            val key = ke.key
            if (key !in listOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight")) return@handler

            ke.preventDefault()
            val cur = if (selection.cursorIndex >= 0) selection.cursorIndex else 0
            val totalBytes = bytes.size

            val newIndex = when (key) {
                "ArrowRight" -> (cur + 1).coerceAtMost(totalBytes - 1)
                "ArrowLeft" -> (cur - 1).coerceAtLeast(0)
                "ArrowDown" -> (cur + BYTES_PER_ROW).coerceAtMost(totalBytes - 1)
                "ArrowUp" -> (cur - BYTES_PER_ROW).coerceAtLeast(0)
                else -> cur
            }

            if (ke.shiftKey) {
                if (selection.anchorIndex < 0) {
                    selection.anchorIndex = cur
                }
                selection.extendSelection(newIndex)
            } else {
                selection.startSelection(newIndex)
            }

            // Scroll cursor into view
            val cursorRow = newIndex / BYTES_PER_ROW
            val cursorTopPx = cursorRow * ROW_HEIGHT_PX
            if (cursorTopPx < scrollTop) {
                val container = document.querySelector(".hex-viewer-scroll") as? HTMLElement
                container?.scrollTop = cursorTopPx.toDouble()
            } else if (cursorTopPx + ROW_HEIGHT_PX > scrollTop + viewportHeight) {
                val container = document.querySelector(".hex-viewer-scroll") as? HTMLElement
                container?.scrollTop = (cursorTopPx + ROW_HEIGHT_PX - viewportHeight).toDouble()
            }
        }
        window.addEventListener("keydown", handler)
        onDispose { window.removeEventListener("keydown", handler) }
    }

    // Global mousemove/mouseup for drag selection
    DisposableEffect(isDragging) {
        if (!isDragging) return@DisposableEffect onDispose {}

        val moveHandler: (Event) -> Unit = { event ->
            val me = event as? MouseEvent
            if (me != null) {
                val container = document.querySelector(".hex-viewer-scroll") as? HTMLElement
                if (container != null) {
                    val rect = container.getBoundingClientRect()
                    // Subtract header row height since HexColumnHeader is inside the scroll container
                    val relY = me.clientY - rect.top + container.scrollTop - ROW_HEIGHT_PX
                    val relX = me.clientX - rect.left
                    val row = (relY / ROW_HEIGHT_PX).toInt().coerceIn(0, totalRows - 1)
                    val col = resolveColumn(relX, container.clientWidth.toDouble())
                    val idx = (row * BYTES_PER_ROW + col).coerceIn(0, bytes.size - 1)
                    selection.extendSelection(idx)

                    // Auto-scroll when dragging near edges
                    val edgeThreshold = 30.0
                    val scrollDelta = when {
                        me.clientY - rect.top < edgeThreshold -> -ROW_HEIGHT_PX.toDouble()
                        me.clientY - rect.top > rect.height - edgeThreshold -> ROW_HEIGHT_PX.toDouble()
                        else -> 0.0
                    }
                    if (scrollDelta != 0.0) {
                        container.scrollTop += scrollDelta
                    }
                }
            }
        }

        val upHandler: (Event) -> Unit = {
            isDragging = false
        }

        window.addEventListener("mousemove", moveHandler)
        window.addEventListener("mouseup", upHandler)
        onDispose {
            window.removeEventListener("mousemove", moveHandler)
            window.removeEventListener("mouseup", upHandler)
        }
    }

    Div({
        classes("hex-viewer-scroll")
        style {
            property("overflow-y", "scroll")
            width(100.percent)
            property("flex", "1 1 0%")
            property("min-height", "120px")
            property("font-family", "var(--hexana-font, monospace)")
            property("font-size", "13px")
            property("line-height", "${ROW_HEIGHT_PX}px")
            property("cursor", "default")
            property("user-select", "none")
            property("outline", "none")
        }
        tabIndex(0)
        addEventListener("scroll") { event ->
            val target = event.target as? HTMLElement ?: return@addEventListener
            scrollTop = target.scrollTop.toInt()
            viewportHeight = target.clientHeight
        }
    }) {
        // Column header
        HexColumnHeader()

        // Virtual scroll container
        Div({
            style {
                height(totalHeight.px)
                property("position", "relative")
            }
        }) {
            // Top spacer
            if (topSpacerHeight > 0) {
                Div({ style { height(topSpacerHeight.px) } })
            }

            // Visible rows
            for (row in firstVisibleRow..lastVisibleRow) {
                HexRow(bytes, row, selection, isDragging) { newDragging ->
                    isDragging = newDragging
                }
            }

            // Bottom spacer
            if (bottomSpacerHeight > 0) {
                Div({ style { height(bottomSpacerHeight.px) } })
            }
        }
    }
}

/**
 * Column header showing byte indices 00–0F.
 */
@Composable
private fun HexColumnHeader() {
    Div({
        classes("hex-row", "hex-header")
        style {
            display(DisplayStyle.Flex)
            property("gap", "0")
            height(ROW_HEIGHT_PX.px)
            property("position", "sticky")
            property("top", "0")
            property("z-index", "1")
            property("background", "var(--hexana-bg, #1e1e1e)")
            property("border-bottom", "1px solid var(--hexana-border, #333)")
            property("opacity", "0.6")
            property("padding-bottom", "2px")
        }
    }) {
        // Offset column placeholder
        Span({
            classes("hex-offset")
            style {
                property("min-width", "80px")
                property("padding-right", "12px")
                property("text-align", "right")
                color(Color("#6A9955"))
            }
        }) {
            Text("Offset")
        }

        // Hex column indices
        Span({
            classes("hex-bytes-header")
            style {
                display(DisplayStyle.Flex)
                property("gap", "0")
            }
        }) {
            for (i in 0 until BYTES_PER_ROW) {
                Span({
                    style {
                        property("width", "28px")
                        property("text-align", "center")
                    }
                }) {
                    Text(i.toString(16).padStart(2, '0').uppercase())
                }
            }
        }

        // ASCII column label
        Span({
            classes("hex-ascii-header")
            style {
                property("padding-left", "12px")
            }
        }) {
            Text("ASCII")
        }
    }
}

@Composable
private fun HexRow(
    bytes: ByteArray,
    row: Int,
    selection: HexSelectionState,
    isDragging: Boolean,
    onDragChange: (Boolean) -> Unit,
) {
    val offset = row * BYTES_PER_ROW
    val end = minOf(offset + BYTES_PER_ROW, bytes.size)

    Div({
        classes("hex-row")
        style {
            display(DisplayStyle.Flex)
            property("gap", "0")
            height(ROW_HEIGHT_PX.px)
        }
    }) {
        // Offset column
        Span({
            classes("hex-offset")
            style {
                property("min-width", "80px")
                property("padding-right", "12px")
                property("text-align", "right")
                color(Color("#6A9955"))
            }
        }) {
            Text(offset.toString(16).padStart(8, '0'))
        }

        // Hex bytes column
        Span({
            classes("hex-bytes")
            style {
                display(DisplayStyle.Flex)
                property("gap", "0")
            }
        }) {
            for (i in offset until end) {
                val byteIndex = i
                val byteVal = bytes[i].toUByte()
                val isSelected = selection.contains(byteIndex)
                val isCursor = selection.cursorIndex == byteIndex

                Span({
                    classes("hex-byte")
                    style {
                        property("width", "28px")
                        property("text-align", "center")
                        property("border-radius", "2px")
                        if (isSelected) {
                            property("background", "var(--hexana-selection, rgba(156, 183, 255, 0.4))")
                        }
                        if (isCursor) {
                            property("outline", "1px solid var(--vscode-editor-foreground, #ccc)")
                        }
                    }
                    onMouseDown { event ->
                        if (event.button.toInt() != 0) return@onMouseDown
                        event.preventDefault()
                        val nativeMouseEvent = event.nativeEvent as? MouseEvent

                        if (nativeMouseEvent?.shiftKey == true) {
                            if (selection.anchorIndex < 0) {
                                selection.startSelection(byteIndex)
                            } else {
                                selection.extendSelection(byteIndex)
                            }
                        } else {
                            selection.startSelection(byteIndex)
                            onDragChange(true)
                        }
                    }
                }) {
                    Text(byteVal.toString(16).padStart(2, '0'))
                }
            }
            // Pad incomplete last row
            val missing = BYTES_PER_ROW - (end - offset)
            if (missing > 0) {
                for (p in 0 until missing) {
                    Span({
                        style {
                            property("width", "28px")
                            property("text-align", "center")
                        }
                    }) {
                        Text("  ")
                    }
                }
            }
        }

        // ASCII column
        Span({
            classes("hex-ascii")
            style {
                property("padding-left", "12px")
                display(DisplayStyle.Flex)
                property("gap", "0")
            }
        }) {
            for (i in offset until end) {
                val byteIndex = i
                val c = bytes[i].toInt().and(0xFF).toChar()
                val display = if (c in ' '..'~') c.toString() else "."
                val isSelected = selection.contains(byteIndex)
                val isCursor = selection.cursorIndex == byteIndex

                Span({
                    classes("hex-ascii-char")
                    style {
                        property("width", "8.5px")
                        property("text-align", "center")
                        color(Color("#CE9178"))
                        property("border-radius", "1px")
                        if (isSelected) {
                            property("background", "var(--hexana-selection, rgba(156, 183, 255, 0.4))")
                        }
                        if (isCursor) {
                            property("outline", "1px solid var(--vscode-editor-foreground, #ccc)")
                        }
                    }
                    onMouseDown { event ->
                        if (event.button.toInt() != 0) return@onMouseDown
                        event.preventDefault()
                        val nativeMouseEvent = event.nativeEvent as? MouseEvent

                        if (nativeMouseEvent?.shiftKey == true) {
                            if (selection.anchorIndex < 0) {
                                selection.startSelection(byteIndex)
                            } else {
                                selection.extendSelection(byteIndex)
                            }
                        } else {
                            selection.startSelection(byteIndex)
                            onDragChange(true)
                        }
                    }
                }) {
                    Text(display)
                }
            }
        }
    }
}

private fun resolveColumn(relativeX: Double, containerWidth: Double): Int {
    // The offset column is ~80px + 12px gap, hex bytes area starts around 92px
    val hexAreaStart = 92.0
    val byteWidth = 28.0
    val col = ((relativeX - hexAreaStart) / byteWidth).toInt()
    return col.coerceIn(0, BYTES_PER_ROW - 1)
}
