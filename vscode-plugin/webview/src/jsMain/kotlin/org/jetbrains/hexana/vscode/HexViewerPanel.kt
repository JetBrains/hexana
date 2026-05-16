package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.CommonByteBuffer
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

private const val BYTES_PER_ROW = 16
internal const val ROW_HEIGHT_PX = 20
private const val OVERSCAN_ROWS = 5
private const val DRAG_THRESHOLD_PX = 3.0
private const val EDGE_THRESHOLD_PX = 30.0
/** Pixels per second when auto-scrolling near the edge during drag selection. */
private const val AUTO_SCROLL_PX_PER_SEC = 120.0

/** Search mode for the hex viewer search bar. */
enum class HexSearchMode { STRING, BYTES }

/**
 * Tracks search state for the hex viewer: the set of byte indices that belong
 * to any match and the list of match start offsets for prev/next navigation.
 */
class HexSearchState {
    var matchIndices by mutableStateOf(emptySet<Int>())
    var matchStarts by mutableStateOf(emptyList<Int>())
    var matchLength by mutableStateOf(0)
    var currentMatchIndex by mutableStateOf(-1)

    val currentMatchStart: Int get() = matchStarts.getOrElse(currentMatchIndex) { -1 }

    fun clear() {
        matchIndices = emptySet()
        matchStarts = emptyList()
        matchLength = 0
        currentMatchIndex = -1
    }

    fun goToNext() {
        if (matchStarts.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + 1) % matchStarts.size
    }

    fun goToPrevious() {
        if (matchStarts.isEmpty()) return
        currentMatchIndex = if (currentMatchIndex <= 0) matchStarts.size - 1 else currentMatchIndex - 1
    }

    fun isInCurrentMatch(byteIndex: Int): Boolean {
        val start = currentMatchStart
        if (start < 0) return false
        return byteIndex >= start && byteIndex < start + matchLength
    }
}

/**
 * Finds all occurrences of [pattern] in [bytes] and updates [state] with
 * the matching byte indices and match start positions.
 */
private fun computeSearchMatches(bytes: CommonByteBuffer, pattern: ByteArray, state: HexSearchState) {
    if (pattern.isEmpty()) {
        state.clear()
        return
    }
    val indices = mutableSetOf<Int>()
    val starts = mutableListOf<Int>()
    var i = 0
    while (i <= bytes.size - pattern.size) {
        var found = true
        for (j in pattern.indices) {
            if (bytes[i + j] != pattern[j]) {
                found = false
                break
            }
        }
        if (found) {
            starts.add(i)
            for (j in pattern.indices) indices.add(i + j)
            i += pattern.size
        } else {
            i++
        }
    }
    state.matchIndices = indices
    state.matchStarts = starts
    state.matchLength = pattern.size
    state.currentMatchIndex = if (starts.isNotEmpty()) 0 else -1
}

/**
 * Parses a hex byte-sequence query string (e.g. "4F 6B" or "4f6b") into a
 * [ByteArray], or returns `null` if the input is not valid hex.
 */
private fun parseHexQuery(query: String): ByteArray? {
    val cleaned = query.replace(" ", "").replace(",", "")
    if (cleaned.isEmpty()) return null
    if (cleaned.length % 2 != 0) return null
    return try {
        ByteArray(cleaned.length / 2) { idx ->
            cleaned.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    } catch (_: NumberFormatException) {
        null
    }
}

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
 * Plain (non-Compose) mutable holder for drag and auto-scroll state.
 * Keeping this outside of Compose `mutableStateOf` prevents recomposition from
 * tearing down the [DisposableEffect] that owns the auto-scroll timer.
 */
private class DragState {
    var isDragging = false
    var pendingOriginX: Double? = null
    var pendingOriginY: Double? = null
    var lastClientX = 0.0
    var lastClientY = 0.0
    /** Last observed MouseEvent.buttons bitmask — updated on every mousemove. */
    var lastButtons = 0

    /** Direction of auto-scroll: -1 (up), 0 (none), +1 (down). */
    var autoScrollDirection = 0
    /** requestAnimationFrame handle, or -1 when inactive. */
    var autoScrollRafId = -1
    /** Timestamp (ms) of the last auto-scroll frame, for time-based movement. */
    var autoScrollLastTime = 0.0

    /** Whether the primary mouse button was held in the most recent mousemove. */
    val isPrimaryHeld: Boolean get() = lastButtons and 1 != 0

    fun reset() {
        isDragging = false
        pendingOriginX = null
        pendingOriginY = null
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
fun HexViewerPanel(bytes: CommonByteBuffer, selection: HexSelectionState = remember { HexSelectionState() }) {
    val totalRows = (bytes.size + BYTES_PER_ROW - 1) / BYTES_PER_ROW
    val totalHeight = totalRows * ROW_HEIGHT_PX

    var scrollTop by remember { mutableStateOf(0) }
    var viewportHeight by remember { mutableStateOf(600) }

    // Drag state is tracked via plain JS variables (not Compose mutableStateOf) to avoid
    // recomposition-driven DisposableEffect teardown/rebuild cycles that would lose the
    // auto-scroll timer and create runaway scrolling feedback loops.
    val dragState = remember { DragState() }

    // --- Hex search state ---
    var hexSearchVisible by remember { mutableStateOf(false) }
    var hexSearchQuery by remember { mutableStateOf("") }
    var hexSearchMode by remember { mutableStateOf(HexSearchMode.BYTES) }
    val hexSearchState = remember { HexSearchState() }

    // Recompute matches when query, mode, or underlying bytes change.
    LaunchedEffect(hexSearchQuery, hexSearchMode, bytes) {
        if (hexSearchQuery.isEmpty()) {
            hexSearchState.clear()
        } else {
            when (hexSearchMode) {
                HexSearchMode.STRING -> {
                    val pattern = hexSearchQuery.encodeToByteArray()
                    computeSearchMatches(bytes, pattern, hexSearchState)
                }
                HexSearchMode.BYTES -> {
                    val pattern = parseHexQuery(hexSearchQuery)
                    if (pattern != null) {
                        computeSearchMatches(bytes, pattern, hexSearchState)
                    } else {
                        hexSearchState.clear()
                    }
                }
            }
        }
    }

    // Scroll to current match when it changes.
    LaunchedEffect(hexSearchState.currentMatchStart) {
        val matchStart = hexSearchState.currentMatchStart
        if (matchStart < 0) return@LaunchedEffect
        val container = document.querySelector(".hex-viewer-scroll") as? HTMLElement ?: return@LaunchedEffect
        val headerHeight = ROW_HEIGHT_PX
        val currentScroll = container.scrollTop.toInt()
        val currentVpHeight = container.clientHeight
        val matchRow = matchStart / BYTES_PER_ROW
        val matchTopInContainer = matchRow * ROW_HEIGHT_PX + headerHeight
        if (matchTopInContainer < currentScroll + headerHeight ||
            matchTopInContainer + ROW_HEIGHT_PX > currentScroll + currentVpHeight
        ) {
            container.scrollTop = (matchTopInContainer - headerHeight).toDouble()
        }
    }

    val firstVisibleRow = (scrollTop / ROW_HEIGHT_PX - OVERSCAN_ROWS).coerceAtLeast(0)
    val lastVisibleRow = ((scrollTop + viewportHeight) / ROW_HEIGHT_PX + OVERSCAN_ROWS).coerceAtMost(totalRows - 1)
    val topSpacerHeight = firstVisibleRow * ROW_HEIGHT_PX

    // Keyboard handler.
    // Reads scroll position directly from the DOM element (not from Compose state)
    // to avoid a feedback loop: setting container.scrollTop fires a scroll event
    // that updates Compose scrollTop state, which triggers recomposition, which
    // changes virtual-scroll spacer heights, which can shift scroll position again.
    DisposableEffect(Unit) {
        val handler: (Event) -> Unit = handler@{ event ->
            val ke = event as? KeyboardEvent ?: return@handler
            val key = ke.key

            // Cmd/Ctrl+F opens the hex search bar.
            if (key == "f" && (ke.metaKey || ke.ctrlKey)) {
                // Only intercept when focus is on the hex viewer or the body.
                val target = ke.target as? HTMLElement
                if (target?.closest(".hexana-panel-table") != null ||
                    target?.closest(".hexana-searchable-table") != null
                ) return@handler
                ke.preventDefault()
                ke.stopPropagation()
                hexSearchVisible = true
                window.requestAnimationFrame {
                    document.querySelector(".hexana-hex-search-input")
                        ?.let { (it as? HTMLInputElement)?.focus() }
                }
                return@handler
            }

            if (key !in listOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight")) return@handler

            // Skip ArrowUp/Down when focus is inside a panel table so that row navigation
            // works; ArrowLeft/Right still reach the hex viewer.
            val target = ke.target as? HTMLElement
            if (target?.closest(".hexana-panel-table") != null && (key == "ArrowUp" || key == "ArrowDown")) return@handler

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

            // Scroll cursor into view.
            // Read current scroll position from the DOM element directly to avoid
            // stale Compose state and recomposition-driven feedback loops.
            // The column header (ROW_HEIGHT_PX tall, sticky) sits inside the scroll
            // container, so the content rows start at an offset of ROW_HEIGHT_PX
            // within the scrollable area.  Account for that when computing whether
            // the cursor row is visible.
            val container = document.querySelector(".hex-viewer-scroll") as? HTMLElement
            if (container != null) {
                val headerHeight = ROW_HEIGHT_PX
                val currentScroll = container.scrollTop.toInt()
                val currentVpHeight = container.clientHeight
                val cursorRow = newIndex / BYTES_PER_ROW
                // Position of the cursor row inside the scroll container's
                // scrollable content (content starts after the header).
                val cursorTopInContainer = cursorRow * ROW_HEIGHT_PX + headerHeight
                if (cursorTopInContainer < currentScroll + headerHeight) {
                    container.scrollTop = (cursorTopInContainer - headerHeight).toDouble()
                } else if (cursorTopInContainer + ROW_HEIGHT_PX > currentScroll + currentVpHeight) {
                    container.scrollTop = (cursorTopInContainer + ROW_HEIGHT_PX - currentVpHeight).toDouble()
                }
            }
        }
        window.addEventListener("keydown", handler)
        onDispose { window.removeEventListener("keydown", handler) }
    }

    // Global mousemove/mouseup for pending drag detection and active drag selection.
    // Uses DisposableEffect(Unit) so the effect is never torn down during drag — all drag
    // and auto-scroll state lives as plain JS variables in the single stable scope.
    // Auto-scroll uses a requestAnimationFrame loop so it is frame-synchronised and
    // can never stack up behind slow Compose recompositions.
    DisposableEffect(Unit) {
        fun updateSelectionAt(clientX: Double, clientY: Double) {
            val container = document.querySelector(".hex-viewer-scroll") as? HTMLElement ?: return
            val rect = container.getBoundingClientRect()
            val relY = clientY - rect.top + container.scrollTop - ROW_HEIGHT_PX
            val relX = clientX - rect.left
            val row = (relY / ROW_HEIGHT_PX).toInt().coerceIn(0, totalRows - 1)
            val col = resolveColumn(relX, container.clientWidth.toDouble())
            val idx = (row * BYTES_PER_ROW + col).coerceIn(0, bytes.size - 1)
            selection.extendSelection(idx)
        }

        // --- requestAnimationFrame-based auto-scroll ---
        // Uses a single rAF loop instead of setInterval.  rAF is
        // frame-synchronised so it never stacks up behind slow Compose
        // recompositions the way setInterval can.
        fun autoScrollFrame(timestamp: Double) {
            // Safety: stop if drag ended or primary button released.
            if (!dragState.isDragging || !dragState.isPrimaryHeld || dragState.autoScrollDirection == 0) {
                dragState.autoScrollRafId = -1
                return
            }

            val dt = if (dragState.autoScrollLastTime == 0.0) 0.0 else timestamp - dragState.autoScrollLastTime
            dragState.autoScrollLastTime = timestamp

            if (dt > 0) {
                val px = AUTO_SCROLL_PX_PER_SEC * dragState.autoScrollDirection * (dt / 1000.0)
                val container = document.querySelector(".hex-viewer-scroll") as? HTMLElement
                if (container != null) {
                    container.scrollTop += px
                    updateSelectionAt(dragState.lastClientX, dragState.lastClientY)
                }
            }

            // Schedule the next frame.
            dragState.autoScrollRafId = window.requestAnimationFrame { ts -> autoScrollFrame(ts) }
        }

        fun stopAutoScroll() {
            if (dragState.autoScrollRafId != -1) {
                window.cancelAnimationFrame(dragState.autoScrollRafId)
                dragState.autoScrollRafId = -1
            }
            dragState.autoScrollDirection = 0
            dragState.autoScrollLastTime = 0.0
        }

        fun startAutoScroll(direction: Int) {
            if (dragState.autoScrollDirection == direction && dragState.autoScrollRafId != -1) return
            stopAutoScroll()
            dragState.autoScrollDirection = direction
            dragState.autoScrollLastTime = 0.0 // will be set on the first frame
            dragState.autoScrollRafId = window.requestAnimationFrame { ts -> autoScrollFrame(ts) }
        }

        val moveHandler: (Event) -> Unit = handler@{ event ->
            val me = event as? MouseEvent ?: return@handler

            // Track the latest button state so the auto-scroll timer can detect
            // a released button even if the mouseup event was swallowed.
            dragState.lastButtons = me.buttons.toInt()

            // Only perform selection while the primary mouse button is held down.
            if (!dragState.isPrimaryHeld) {
                stopAutoScroll()
                dragState.reset()
                return@handler
            }

            // If we have a pending drag, check if the mouse moved far enough to commit
            val ox = dragState.pendingOriginX
            val oy = dragState.pendingOriginY
            if (ox != null && oy != null && !dragState.isDragging) {
                val dx = me.clientX - ox
                val dy = me.clientY - oy
                if (dx * dx + dy * dy >= DRAG_THRESHOLD_PX * DRAG_THRESHOLD_PX) {
                    dragState.isDragging = true
                    dragState.pendingOriginX = null
                    dragState.pendingOriginY = null
                } else {
                    return@handler
                }
            }

            if (!dragState.isDragging) return@handler

            dragState.lastClientX = me.clientX.toDouble()
            dragState.lastClientY = me.clientY.toDouble()

            // Only extend selection from mousemove when auto-scroll is NOT active.
            // When auto-scroll is running, the rAF loop handles both scrolling and
            // selection extension — duplicating it here causes a feedback loop.
            if (dragState.autoScrollRafId == -1) {
                updateSelectionAt(dragState.lastClientX, dragState.lastClientY)
            }

            // Determine whether the cursor is near an edge and manage the auto-scroll loop.
            val container = document.querySelector(".hex-viewer-scroll") as? HTMLElement ?: return@handler
            val rect = container.getBoundingClientRect()
            val distTop = me.clientY - rect.top
            val distBottom = rect.bottom - me.clientY
            when {
                distTop < EDGE_THRESHOLD_PX -> startAutoScroll(-1)
                distBottom < EDGE_THRESHOLD_PX -> startAutoScroll(+1)
                else -> stopAutoScroll()
            }
        }

        val upHandler: (Event) -> Unit = {
            stopAutoScroll()
            dragState.reset()
        }

        // Also stop on mouseleave from the document — handles the cursor leaving
        // the VS Code webview iframe, where mouseup would fire in the parent frame
        // and never reach us.
        val leaveHandler: (Event) -> Unit = {
            stopAutoScroll()
            dragState.reset()
        }

        window.addEventListener("mousemove", moveHandler)
        window.addEventListener("mouseup", upHandler)
        document.addEventListener("mouseup", upHandler)
        document.addEventListener("mouseleave", leaveHandler)
        onDispose {
            stopAutoScroll()
            window.removeEventListener("mousemove", moveHandler)
            window.removeEventListener("mouseup", upHandler)
            document.removeEventListener("mouseup", upHandler)
            document.removeEventListener("mouseleave", leaveHandler)
        }
    }

    // Hex search bar (overlaid above the hex viewer)
    if (hexSearchVisible) {
        HexSearchBar(
            query = hexSearchQuery,
            onQueryChange = { hexSearchQuery = it },
            mode = hexSearchMode,
            onModeChange = { hexSearchMode = it },
            matchCount = hexSearchState.matchStarts.size,
            currentMatch = hexSearchState.currentMatchIndex,
            onNext = { hexSearchState.goToNext() },
            onPrevious = { hexSearchState.goToPrevious() },
            onClose = {
                hexSearchVisible = false
                hexSearchQuery = ""
                hexSearchState.clear()
            },
        )
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

        // Virtual scroll container — uses a fixed-height outer div with
        // position:relative and an absolutely-positioned inner div for the
        // visible rows.  This avoids variable-height top/bottom spacer divs
        // whose recalculation during recomposition would shift the browser's
        // scroll position and create a feedback loop.
        Div({
            style {
                height(totalHeight.px)
                property("position", "relative")
            }
        }) {
            // Visible rows positioned via translateY — no spacers needed.
            Div({
                style {
                    property("position", "absolute")
                    property("left", "0")
                    property("right", "0")
                    property("top", "0")
                    property("transform", "translateY(${topSpacerHeight}px)")
                }
            }) {
                for (row in firstVisibleRow..lastVisibleRow) {
                    HexRow(bytes, row, selection, hexSearchState) { ox, oy ->
                        dragState.pendingOriginX = ox
                        dragState.pendingOriginY = oy
                    }
                }
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

/**
 * Search bar for the hex viewer with a toggle between String and Bytes search modes.
 */
@Composable
private fun HexSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    mode: HexSearchMode,
    onModeChange: (HexSearchMode) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    Div({
        classes("hexana-hex-search-bar")
    }) {
        // Mode toggle button
        Span({
            classes("hexana-hex-search-mode")
            attr("title", "Switch between String and Byte sequence search")
            onClick {
                onModeChange(
                    if (mode == HexSearchMode.STRING) HexSearchMode.BYTES else HexSearchMode.STRING,
                )
            }
        }) {
            Text(if (mode == HexSearchMode.STRING) "Abc" else "Hex")
        }

        Input(InputType.Text) {
            classes("hexana-hex-search-input")
            value(query)
            attr(
                "placeholder",
                if (mode == HexSearchMode.STRING) "Search text\u2026" else "Hex bytes (e.g. 4F 6B)\u2026",
            )
            onInput { event ->
                onQueryChange(event.value)
            }
            onKeyDown { event ->
                val ke = event.nativeEvent as? KeyboardEvent
                if (ke != null) {
                    when (ke.key) {
                        "Escape" -> {
                            ke.preventDefault()
                            onClose()
                        }
                        "Enter" -> {
                            ke.preventDefault()
                            if (ke.shiftKey) onPrevious() else onNext()
                        }
                    }
                }
            }
            ref { input ->
                input.focus()
                onDispose { }
            }
        }

        // Match count indicator
        if (query.isNotEmpty()) {
            Span({
                classes("hexana-hex-search-count")
            }) {
                if (matchCount > 0) {
                    Text("${currentMatch + 1} of $matchCount")
                } else {
                    Text("No results")
                }
            }
        }

        // Previous / Next buttons
        Span({
            classes("hexana-hex-search-nav")
            attr("title", "Previous match (Shift+Enter)")
            onClick { onPrevious() }
        }) {
            Text("\u25B2") // ▲
        }
        Span({
            classes("hexana-hex-search-nav")
            attr("title", "Next match (Enter)")
            onClick { onNext() }
        }) {
            Text("\u25BC") // ▼
        }

        // Close button
        Span({
            classes("hexana-hex-search-close")
            attr("title", "Close search (Esc)")
            onClick { onClose() }
        }) {
            Text("\u2715")
        }
    }
}

@Composable
private fun HexRow(
    bytes: CommonByteBuffer,
    row: Int,
    selection: HexSelectionState,
    searchState: HexSearchState,
    onPendingDrag: (Double, Double) -> Unit,
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
                val isSearchMatch = searchState.matchIndices.contains(byteIndex)
                val isCurrentMatch = isSearchMatch && searchState.isInCurrentMatch(byteIndex)

                Span({
                    classes("hex-byte")
                    if (isCurrentMatch) classes("hex-search-current")
                    else if (isSearchMatch) classes("hex-search-match")
                    style {
                        property("width", "28px")
                        property("text-align", "center")
                        if (!isSelected && !isSearchMatch) {
                            property("border-radius", "2px")
                        }
                        if (isSelected && !isSearchMatch) {
                            property("background", "var(--hexana-selection, rgba(156, 183, 255, 0.4))")
                        }
                        if (isCursor) {
                            property("outline", "1px solid var(--vscode-editor-foreground, #ccc)")
                        }
                    }
                    onMouseDown { event ->
                        if (event.button.toInt() != 0) return@onMouseDown
                        event.preventDefault()
                        // Move focus to the hex viewer container so that any
                        // focused panel-table row loses focus and stops
                        // capturing arrow-key events.
                        (document.querySelector(".hex-viewer-scroll") as? HTMLElement)?.focus()
                        val nativeMouseEvent = event.nativeEvent as? MouseEvent

                        if (nativeMouseEvent?.shiftKey == true) {
                            if (selection.anchorIndex < 0) {
                                selection.startSelection(byteIndex)
                            } else {
                                selection.extendSelection(byteIndex)
                            }
                        } else {
                            selection.startSelection(byteIndex)
                            if (nativeMouseEvent != null) {
                                onPendingDrag(nativeMouseEvent.clientX.toDouble(), nativeMouseEvent.clientY.toDouble())
                            }
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
                val isSearchMatch = searchState.matchIndices.contains(byteIndex)
                val isCurrentMatch = isSearchMatch && searchState.isInCurrentMatch(byteIndex)

                Span({
                    classes("hex-ascii-char")
                    if (isCurrentMatch) classes("hex-search-current")
                    else if (isSearchMatch) classes("hex-search-match")
                    style {
                        property("width", "8.5px")
                        property("text-align", "center")
                        color(Color("#CE9178"))
                        if (!isSelected && !isSearchMatch) {
                            property("border-radius", "1px")
                        }
                        if (isSelected && !isSearchMatch) {
                            property("background", "var(--hexana-selection, rgba(156, 183, 255, 0.4))")
                        }
                        if (isCursor) {
                            property("outline", "1px solid var(--vscode-editor-foreground, #ccc)")
                        }
                    }
                    onMouseDown { event ->
                        if (event.button.toInt() != 0) return@onMouseDown
                        event.preventDefault()
                        (document.querySelector(".hex-viewer-scroll") as? HTMLElement)?.focus()
                        val nativeMouseEvent = event.nativeEvent as? MouseEvent

                        if (nativeMouseEvent?.shiftKey == true) {
                            if (selection.anchorIndex < 0) {
                                selection.startSelection(byteIndex)
                            } else {
                                selection.extendSelection(byteIndex)
                            }
                        } else {
                            selection.startSelection(byteIndex)
                            if (nativeMouseEvent != null) {
                                onPendingDrag(nativeMouseEvent.clientX.toDouble(), nativeMouseEvent.clientY.toDouble())
                            }
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
