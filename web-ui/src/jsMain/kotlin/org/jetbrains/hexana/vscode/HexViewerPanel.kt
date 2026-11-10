package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.shared.RowSelectionData
import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.CommonByteBuffer
import kotlin.js.Promise
import org.jetbrains.hexana.providers.StructureRange
import org.jetbrains.hexana.providers.StructureRangeLookup
import org.jetbrains.hexana.shared.RowDetailData
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
 * Tracks search state for the hex viewer: match start offsets (sorted) for prev/next navigation
 * and highlighting. For a file held in memory every match is found up front (at most
 * [MAX_SEARCH_MATCHES]); for a paged file the host finds one match at a time and [matchStarts]
 * holds just the current one, with [isPaged] set.
 */
class HexSearchState {
    var matchStarts by mutableStateOf(emptyList<Long>())
    var matchLength by mutableStateOf(0)
    var currentMatchIndex by mutableStateOf(-1)
    var isPaged by mutableStateOf(false)

    val currentMatchStart: Long get() = matchStarts.getOrElse(currentMatchIndex) { -1L }

    fun clear() {
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

    /** Whether [byteIndex] is inside any match, by binary search over the sorted starts. */
    fun isMatch(byteIndex: Long): Boolean {
        val starts = matchStarts
        if (starts.isEmpty() || matchLength == 0) return false
        var low = 0
        var high = starts.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= byteIndex) { candidate = mid; low = mid + 1 } else high = mid - 1
        }
        return candidate >= 0 && byteIndex < starts[candidate] + matchLength
    }

    fun isInCurrentMatch(byteIndex: Long): Boolean {
        val start = currentMatchStart
        if (start < 0) return false
        return byteIndex >= start && byteIndex < start + matchLength
    }
}

/** Most matches found and highlighted for an in-memory file. */
private const val MAX_SEARCH_MATCHES = 100_000

/**
 * Finds the occurrences of [pattern] in [bytes] (non-overlapping, at most [MAX_SEARCH_MATCHES])
 * and updates [state] with their start positions.
 */
private fun computeSearchMatches(bytes: CommonByteBuffer, pattern: ByteArray, state: HexSearchState) {
    if (pattern.isEmpty()) {
        state.clear()
        return
    }
    val starts = mutableListOf<Long>()
    var i = 0
    while (i <= bytes.size - pattern.size && starts.size < MAX_SEARCH_MATCHES) {
        var found = true
        for (j in pattern.indices) {
            if (bytes[i + j] != pattern[j]) {
                found = false
                break
            }
        }
        if (found) {
            starts.add(i.toLong())
            i += pattern.size
        } else {
            i++
        }
    }
    state.isPaged = false
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
 * by absolute byte index. [Long], because the hex view covers the whole of a
 * file of any size, not just the part the tabs were parsed from.
 */
class HexSelectionState {
    var anchorIndex by mutableStateOf(-1L)
    var activeIndex by mutableStateOf(-1L)
    var cursorIndex by mutableStateOf(-1L)

    /**
     * Bytes of the last clicked table row that exist nowhere in the file - the
     * decrypted plaintext of a TLS record, for instance. Carried here because
     * this is already the one object shared by the tables and the hex view;
     * `null` means the clicked row had none.
     */
    var rowDetail by mutableStateOf<RowDetailData?>(null)
    var showRowDetail by mutableStateOf(false)

    val hasSelection: Boolean get() = anchorIndex >= 0 && activeIndex >= 0 && anchorIndex != activeIndex

    val selectionStart: Long get() = if (anchorIndex <= activeIndex) anchorIndex else activeIndex
    val selectionEnd: Long get() = if (anchorIndex <= activeIndex) activeIndex else anchorIndex

    fun contains(absoluteIndex: Long): Boolean {
        if (anchorIndex < 0 || activeIndex < 0) return false
        return absoluteIndex in selectionStart..selectionEnd
    }

    fun reset() {
        anchorIndex = -1
        activeIndex = -1
    }

    fun startSelection(index: Long) {
        anchorIndex = index
        activeIndex = index
        cursorIndex = index
    }

    fun extendSelection(index: Long) {
        activeIndex = index
        cursorIndex = index
    }

    /** Selects [size] bytes starting at [offset] and scrolls them into view. */
    fun selectRange(offset: Long, size: Long) {
        anchorIndex = offset
        activeIndex = offset + size.coerceAtLeast(1) - 1
        cursorIndex = offset
        scrollHexViewerTo(offset)
    }

    fun selectRange(offset: Int, size: Int) = selectRange(offset.toLong(), size.toLong())

    /** Selects what a table row points at. */
    fun select(row: RowSelectionData) = selectRange(row.offset, row.size)

    /** Whether the selection is exactly what [row] points at. */
    fun matches(row: RowSelectionData): Boolean =
        hasSelection && selectionStart == row.offset && selectionEnd == row.offset + row.size - 1
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
 * Maps a keyboard key string to its hex-digit value `[0..15]`, or null
 * if the key is not a hex digit.
 */
private fun hexDigitValue(key: String): Int? = when (key.lowercase()) {
    "0" -> 0; "1" -> 1; "2" -> 2; "3" -> 3; "4" -> 4
    "5" -> 5; "6" -> 6; "7" -> 7; "8" -> 8; "9" -> 9
    "a" -> 0xA; "b" -> 0xB; "c" -> 0xC; "d" -> 0xD; "e" -> 0xE; "f" -> 0xF
    else -> null
}

/**
 * CSS rgba color strings for structure region backgrounds, cycling across
 * adjacent regions. Matches the IntelliJ dark-theme palette.
 */
private val STRUCTURE_COLORS = listOf(
    "rgba(100, 149, 237, 0.15)",  // cornflower blue
    "rgba(144, 238, 144, 0.15)",  // light green
    "rgba(255, 182, 108, 0.15)",  // peach
    "rgba(186, 147, 214, 0.15)",  // lavender
    "rgba(255, 218, 120, 0.15)",  // pale gold
    "rgba(120, 210, 210, 0.15)",  // teal
)

/** Slightly stronger alpha for label badge backgrounds. */
private val STRUCTURE_LABEL_COLORS = listOf(
    "rgba(100, 149, 237, 0.35)",
    "rgba(144, 238, 144, 0.35)",
    "rgba(255, 182, 108, 0.35)",
    "rgba(186, 147, 214, 0.35)",
    "rgba(255, 218, 120, 0.35)",
    "rgba(120, 210, 210, 0.35)",
)

/**
 * Global "show this byte" requests ([scrollHexViewerTo]), observed by the hex viewer: tables and
 * tabs select bytes without holding a reference to it.
 */
internal object HexViewerScrollRequests {
    var offset by mutableStateOf(-1L)
        private set
    var serial by mutableStateOf(0)
        private set

    fun request(byteOffset: Long) {
        offset = byteOffset
        serial++
    }
}

/** Tallest the scroll container's content gets; Chromium caps element heights near 33.5M px. */
internal const val MAX_SCROLL_PX = 8_000_000.0

/**
 * The hex viewer's position: the first visible row, as a [Long] row index.
 *
 * Up to [MAX_SCROLL_PX] of rows the scroll container scrolls them natively, one pixel per pixel.
 * Beyond that — a file of a few hundred megabytes and up — the content height is capped and the
 * scrollbar position *scales* to a row, while the wheel, the keys, dragging and auto-scroll move by
 * whole rows. In both cases the visible rows are drawn pinned to the top of the viewport, starting
 * at [topRow]; [subPx] is the part of the top row scrolled out of view in native mode.
 */
internal class HexScroller {
    var container: HTMLElement? = null
    var totalRows by mutableStateOf(0L)
    var viewportHeight by mutableStateOf(600)
    var topRow by mutableStateOf(0L)
    var subPx by mutableStateOf(0.0)
    var scrollPx by mutableStateOf(0.0)
    private var programmaticScrollPx = -1.0
    private var wheelRemainder = 0.0

    val scaled: Boolean get() = totalRows * ROW_HEIGHT_PX > MAX_SCROLL_PX
    val spacerPx: Double get() = if (scaled) MAX_SCROLL_PX else totalRows * ROW_HEIGHT_PX.toDouble()
    val visibleRows: Long get() = ((viewportHeight - ROW_HEIGHT_PX) / ROW_HEIGHT_PX).coerceAtLeast(1).toLong()
    val maxTopRow: Long get() = (totalRows - visibleRows).coerceAtLeast(0)
    private val maxScrollPx: Double
        get() = container?.let { (it.scrollHeight - it.clientHeight).toDouble() }?.takeIf { it > 0 }
            ?: (spacerPx + ROW_HEIGHT_PX - viewportHeight).coerceAtLeast(1.0)

    fun onScroll(px: Double, clientHeight: Int) {
        viewportHeight = clientHeight
        scrollPx = px
        if (scaled) {
            // Our own scrollTo lands here too; its row is already set, more precisely than the
            // scaled pixel position could say.
            if (programmaticScrollPx >= 0 && kotlin.math.abs(px - programmaticScrollPx) < 2.0) return
            programmaticScrollPx = -1.0
            topRow = kotlin.math.round(px / maxScrollPx * maxTopRow).toLong().coerceIn(0, maxTopRow)
            subPx = 0.0
        } else {
            topRow = (px / ROW_HEIGHT_PX).toLong()
            subPx = px - topRow * ROW_HEIGHT_PX
        }
    }

    fun scrollToRow(row: Long) {
        val target = row.coerceIn(0, maxTopRow)
        val element = container ?: return
        if (scaled) {
            topRow = target
            subPx = 0.0
            val px = if (maxTopRow == 0L) 0.0 else target.toDouble() / maxTopRow * maxScrollPx
            programmaticScrollPx = px
            scrollPx = px
            element.scrollTop = px
        } else {
            element.scrollTop = target * ROW_HEIGHT_PX.toDouble()
        }
    }

    fun scrollByRows(delta: Long) = scrollToRow(topRow + delta)

    /** Scrolls the least distance that shows [row] whole. */
    fun ensureRowVisible(row: Long) {
        val first = topRow + if (subPx > 0.0) 1 else 0
        val last = topRow + visibleRows - 1
        when {
            row < first -> scrollToRow(row)
            row > last -> scrollToRow(row - visibleRows + 1)
        }
    }

    /** The row under a point [y] pixels below the top of the rows area (under the header). */
    fun rowAt(y: Double): Long =
        (topRow + kotlin.math.floor((y + subPx) / ROW_HEIGHT_PX).toLong()).coerceIn(0, (totalRows - 1).coerceAtLeast(0))

    /** Wheel scrolling in scaled mode, in rows; native mode leaves the wheel to the browser. */
    fun onWheel(deltaY: Double, deltaMode: Int): Boolean {
        if (!scaled) return false
        val rows = when (deltaMode) {
            1 -> deltaY // lines
            2 -> deltaY * visibleRows // pages
            else -> deltaY / ROW_HEIGHT_PX
        } + wheelRemainder
        val whole = rows.toLong()
        wheelRemainder = rows - whole
        if (whole != 0L) scrollByRows(whole)
        return true
    }

    /** Auto-scroll during drag selection by [px] pixels. */
    fun autoScrollBy(px: Double) {
        val element = container ?: return
        if (!scaled) {
            element.scrollTop += px
        } else {
            wheelRemainder += px / ROW_HEIGHT_PX
            val whole = wheelRemainder.toLong()
            wheelRemainder -= whole
            if (whole != 0L) scrollByRows(whole)
        }
    }
}

/**
 * A virtualized hex viewer covering the whole of [bytes], of any size.
 *
 * Only rows visible in the viewport (plus a small overscan) are rendered.
 * Selection is supported via click, shift-click, drag, and keyboard navigation.
 * When [editState] is provided, hex digits typed on the cursor cell are
 * committed as byte edits (high nibble first, then low nibble).
 *
 * [onSearchFile] finds the next (or previous) occurrence of a pattern in a file that is not
 * fully loaded ([HexBytes.isFullyLoaded] false); search is local otherwise.
 */
@Composable
fun HexViewerPanel(
    bytes: HexBytes,
    selection: HexSelectionState = remember { HexSelectionState() },
    editState: HexEditState? = null,
    structureRanges: List<StructureRange> = emptyList(),
    onTabChangeRequest: ((String) -> Unit)? = null,
    onSearchFile: ((pattern: ByteArray, from: Long, reverse: Boolean) -> Promise<Long>)? = null,
) {
    val totalBytes = bytes.size
    val totalRows = (totalBytes + BYTES_PER_ROW - 1) / BYTES_PER_ROW
    // Re-render the rows when fetched pages arrive; passed to every row so none is skipped.
    val loadStamp = bytes.loadStamp
    // Script regions overlay the base parser's, so the ranges may overlap.
    val structureLookup = remember(structureRanges) { StructureRangeLookup(structureRanges) }
    // Offsets past 4 GiB need more than eight hex digits.
    val offsetDigits = maxOf(8, (totalBytes - 1).coerceAtLeast(0).toString(16).length)
    val offsetColumnPx = 80 + 8 * (offsetDigits - 8)

    val scroller = remember { HexScroller() }
    scroller.totalRows = totalRows

    // Drag state is tracked via plain JS variables (not Compose mutableStateOf) to avoid
    // recomposition-driven DisposableEffect teardown/rebuild cycles that would lose the
    // auto-scroll timer and create runaway scrolling feedback loops.
    val dragState = remember { DragState() }

    // --- Hex edit state ---
    // Pending hex digit for in-cell editing: when non-null, the cursor cell
    // shows `<digit>_` and the next hex digit typed combines into a byte and
    // commits via editState.replaceByte(). Cleared on Escape or cursor move.
    var pendingDigit by remember(editState) { mutableStateOf<Int?>(null) }

    // --- Hex search state ---
    var hexSearchVisible by remember { mutableStateOf(false) }
    var hexSearchQuery by remember { mutableStateOf("") }
    var hexSearchMode by remember { mutableStateOf(HexSearchMode.BYTES) }
    val hexSearchState = remember { HexSearchState() }
    // Bumped to make the host search from the current match again (paged files).
    var pagedSearchStep by remember { mutableStateOf<Boolean?>(null) }

    fun searchPattern(): ByteArray? = when (hexSearchMode) {
        HexSearchMode.STRING -> hexSearchQuery.encodeToByteArray().takeIf { it.isNotEmpty() }
        HexSearchMode.BYTES -> parseHexQuery(hexSearchQuery)
    }

    // Recompute matches when query, mode, or underlying bytes change.
    LaunchedEffect(hexSearchQuery, hexSearchMode, bytes) {
        val pattern = if (hexSearchQuery.isEmpty()) null else searchPattern()
        when {
            pattern == null -> hexSearchState.clear()
            bytes is BufferHexBytes -> computeSearchMatches(bytes.bytes, pattern, hexSearchState)
            onSearchFile != null -> {
                hexSearchState.isPaged = true
                hexSearchState.matchLength = pattern.size
                onSearchFile(pattern, 0L, false).then { at ->
                    hexSearchState.matchStarts = if (at >= 0) listOf(at) else emptyList()
                    hexSearchState.currentMatchIndex = if (at >= 0) 0 else -1
                }
            }
            else -> hexSearchState.clear()
        }
    }

    // Paged files: next / previous asks the host for the match after / before the current one.
    LaunchedEffect(pagedSearchStep) {
        val reverse = pagedSearchStep ?: return@LaunchedEffect
        pagedSearchStep = null
        val pattern = searchPattern() ?: return@LaunchedEffect
        val search = onSearchFile ?: return@LaunchedEffect
        val current = hexSearchState.currentMatchStart
        val from = when {
            current < 0 -> if (reverse) totalBytes - 1 else 0L
            reverse -> current - 1
            else -> current + 1
        }
        search(pattern, from, reverse).then { at ->
            // Wrap around once, as the in-memory search does.
            if (at >= 0) {
                hexSearchState.matchStarts = listOf(at)
                hexSearchState.currentMatchIndex = 0
            } else {
                search(pattern, if (reverse) totalBytes - 1 else 0L, reverse).then { wrapped ->
                    hexSearchState.matchStarts = if (wrapped >= 0) listOf(wrapped) else emptyList()
                    hexSearchState.currentMatchIndex = if (wrapped >= 0) 0 else -1
                }
            }
        }
    }

    // Scroll to current match when it changes.
    LaunchedEffect(hexSearchState.currentMatchStart) {
        val matchStart = hexSearchState.currentMatchStart
        if (matchStart < 0) return@LaunchedEffect
        scroller.ensureRowVisible(matchStart / BYTES_PER_ROW)
    }

    // Selections made elsewhere (tables, tabs) scroll their first byte into view.
    LaunchedEffect(HexViewerScrollRequests.serial) {
        val offset = HexViewerScrollRequests.offset
        if (offset >= 0) scroller.ensureRowVisible(offset / BYTES_PER_ROW)
    }

    // Modification stamp used as a key to force the hex grid to recompose
    // when editState bytes change (e.g. after discard or undo-overwrite)
    // even when modifiedOffsets stays the same empty set.
    val editStamp = editState?.modificationStamp ?: 0L

    val firstVisibleRow = scroller.topRow.coerceAtMost((totalRows - 1).coerceAtLeast(0))
    val lastVisibleRow = (scroller.topRow + scroller.visibleRows + OVERSCAN_ROWS).coerceAtMost(totalRows - 1)

    // Fetch the shown window and its surroundings before rows scroll onto them.
    LaunchedEffect(bytes, firstVisibleRow, lastVisibleRow) {
        bytes.prefetch(firstVisibleRow * BYTES_PER_ROW, (lastVisibleRow + 1) * BYTES_PER_ROW)
    }

    // Keyboard handler.
    DisposableEffect(Unit) {
        val handler: (Event) -> Unit = handler@{ event ->
            val ke = event as? KeyboardEvent ?: return@handler
            val key = ke.key

            // Skip all hex viewer keyboard handling when focus is inside the
            // tab content area — let the table/tab handle its own keys.
            val focusTarget = ke.target as? HTMLElement
            if (focusTarget?.closest(".hexana-tab-content") != null) return@handler

            // Never intercept keys typed into editable elements — dialog
            // fields and search inputs live outside .hexana-tab-content,
            // and the hex-digit branch below would otherwise swallow every
            // 0-9/a-f keystroke aimed at them.
            val focusTag = focusTarget?.tagName
            if (focusTag == "INPUT" || focusTag == "TEXTAREA" || focusTag == "SELECT" ||
                focusTarget?.isContentEditable == true
            ) return@handler

            // Cmd/Ctrl+F opens the hex search bar.
            if (key == "f" && (ke.metaKey || ke.ctrlKey)) {
                // Only intercept when focus is on the hex viewer or the body.
                val target = focusTarget
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

            // Escape clears pending hex digit.
            if (key == "Escape" && pendingDigit != null) {
                ke.preventDefault()
                pendingDigit = null
                return@handler
            }

            // Hex digit input for editing (only ever wired for a file held whole in memory).
            if (editState != null) {
                val digit = hexDigitValue(key)
                if (digit != null && !ke.ctrlKey && !ke.metaKey && !ke.altKey) {
                    ke.preventDefault()
                    val cursorIdx = selection.cursorIndex
                    if (cursorIdx < 0 || cursorIdx >= editState.size) return@handler
                    val pd = pendingDigit
                    if (pd == null) {
                        pendingDigit = digit
                    } else {
                        val byte = ((pd and 0xF) shl 4) or (digit and 0xF)
                        editState.replaceByte(cursorIdx.toInt(), byte.toByte())
                        pendingDigit = null
                        // Advance cursor to next cell.
                        val nextIdx = cursorIdx + 1
                        if (nextIdx < editState.size) {
                            selection.startSelection(nextIdx)
                        }
                    }
                    return@handler
                }
            }

            val isJump = (key == "Home" || key == "End") && (ke.ctrlKey || ke.metaKey)
            if (key !in listOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "PageUp", "PageDown") && !isJump) return@handler

            // Only handle navigation keys when focus is actually on the hex
            // viewer or its scroll container.  After tree-table
            // recomposition the focused <tr> may be destroyed and focus
            // falls to <body>; without this positive check the hex viewer
            // would steal the arrow key before the table can re-focus.
            val target = ke.target as? HTMLElement
            val hexViewer = document.querySelector(".hex-viewer-scroll") as? HTMLElement
            if (target != null && hexViewer != null) {
                if (target != hexViewer && !hexViewer.contains(target)) return@handler
            } else if (target?.closest(".hexana-panel-table") != null) {
                return@handler
            }
            if (totalBytes == 0L) return@handler

            ke.preventDefault()
            pendingDigit = null
            val cur = if (selection.cursorIndex >= 0) selection.cursorIndex else 0L
            val pageBytes = scroller.visibleRows * BYTES_PER_ROW

            val newIndex = when (key) {
                "ArrowRight" -> (cur + 1).coerceAtMost(totalBytes - 1)
                "ArrowLeft" -> (cur - 1).coerceAtLeast(0)
                "ArrowDown" -> (cur + BYTES_PER_ROW).coerceAtMost(totalBytes - 1)
                "ArrowUp" -> (cur - BYTES_PER_ROW).coerceAtLeast(0)
                "PageDown" -> (cur + pageBytes).coerceAtMost(totalBytes - 1)
                "PageUp" -> (cur - pageBytes).coerceAtLeast(0)
                "Home" -> 0L
                "End" -> totalBytes - 1
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
            scroller.ensureRowVisible(newIndex / BYTES_PER_ROW)
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
            if (scroller.totalRows == 0L) return
            val rect = container.getBoundingClientRect()
            val row = scroller.rowAt(clientY - rect.top - ROW_HEIGHT_PX)
            val col = resolveColumn(clientX - rect.left, offsetColumnPx)
            val idx = (row * BYTES_PER_ROW + col).coerceIn(0, scroller.totalRows * BYTES_PER_ROW - 1)
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
                scroller.autoScrollBy(AUTO_SCROLL_PX_PER_SEC * dragState.autoScrollDirection * (dt / 1000.0))
                updateSelectionAt(dragState.lastClientX, dragState.lastClientY)
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
            pagedMatchStart = hexSearchState.currentMatchStart.takeIf { hexSearchState.isPaged },
            onNext = { if (hexSearchState.isPaged) pagedSearchStep = false else hexSearchState.goToNext() },
            onPrevious = { if (hexSearchState.isPaged) pagedSearchStep = true else hexSearchState.goToPrevious() },
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
        ref { element ->
            scroller.container = element
            scroller.viewportHeight = element.clientHeight
            onDispose { if (scroller.container === element) scroller.container = null }
        }
        addEventListener("scroll") { event ->
            val target = event.target as? HTMLElement ?: return@addEventListener
            scroller.onScroll(target.scrollTop, target.clientHeight)
        }
        addEventListener("wheel") { event ->
            val wheel = event.nativeEvent.asDynamic()
            if (scroller.onWheel((wheel.deltaY as Number).toDouble(), (wheel.deltaMode as Number).toInt())) {
                event.preventDefault()
            }
        }
    }) {
        // Column header
        HexColumnHeader(
            structureRanges = structureRanges,
            scroller = scroller,
            bytesPerRow = BYTES_PER_ROW,
            offsetColumnPx = offsetColumnPx,
            onTabChangeRequest = onTabChangeRequest,
        )

        // Virtual scroll container: a fixed-height spacer gives the scrollbar its range, and the
        // visible rows are drawn pinned to the top of the viewport (translateY of the scroll
        // position, less the part of the top row scrolled out of view).
        Div({
            style {
                height(scroller.spacerPx.px)
                property("position", "relative")
                property("overflow", "hidden")
            }
        }) {
            Div({
                style {
                    property("position", "absolute")
                    property("left", "0")
                    property("right", "0")
                    property("top", "0")
                    property("transform", "translateY(${scroller.scrollPx - scroller.subPx}px)")
                }
            }) {
                key(editStamp) {
                    if (totalRows > 0) {
                        var row = firstVisibleRow
                        while (row <= lastVisibleRow) {
                            HexRow(bytes, loadStamp, row, offsetDigits, offsetColumnPx, selection, hexSearchState, editState, editStamp, pendingDigit, structureRanges, structureLookup, onTabChangeRequest) { ox, oy ->
                                dragState.pendingOriginX = ox
                                dragState.pendingOriginY = oy
                            }
                            row++
                        }
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
private fun HexColumnHeader(
    structureRanges: List<StructureRange>,
    scroller: HexScroller,
    bytesPerRow: Int,
    offsetColumnPx: Int,
    onTabChangeRequest: ((String) -> Unit)? = null,
) {
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
            property("align-items", "center")
        }
    }) {
        // Offset column placeholder
        Span({
            classes("hex-offset")
            style {
                property("min-width", "${offsetColumnPx}px")
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
                // Fixed width matching the ASCII content column
                property("width", "${bytesPerRow * 8.5}px")
            }
        }) {
            Text("ASCII")
        }

        // Section navigation arrows
        if (structureRanges.isNotEmpty()) {
            Span({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "2px")
                    property("padding-left", "16px")
                    property("align-items", "center")
                }
            }) {
                // Previous section
                Span({
                    classes("hexana-section-nav")
                    attr("title", "Previous section")
                    onClick {
                        val currentRow = scroller.topRow
                        val prevIdx = structureRanges.indexOfLast { it.offset / bytesPerRow < currentRow }
                        if (prevIdx >= 0) scroller.scrollToRow(structureRanges[prevIdx].offset / bytesPerRow)
                    }
                }) {
                    Text("\u25B2") // ▲
                }
                // Next section
                Span({
                    classes("hexana-section-nav")
                    attr("title", "Next section")
                    onClick {
                        val currentRow = scroller.topRow
                        val nextIdx = structureRanges.indexOfFirst { it.offset / bytesPerRow > currentRow }
                        if (nextIdx >= 0) scroller.scrollToRow(structureRanges[nextIdx].offset / bytesPerRow)
                    }
                }) {
                    Text("\u25BC") // ▼
                }
            }
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
    /** For a file searched on the host: where the current match is, `-1` for none, `null` for local search. */
    pagedMatchStart: Long? = null,
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
                when {
                    pagedMatchStart != null && pagedMatchStart >= 0 -> Text("at 0x${pagedMatchStart.toString(16).uppercase()}")
                    matchCount > 0 -> Text("${currentMatch + 1} of $matchCount")
                    else -> Text("No results")
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
    bytes: HexBytes,
    /** [HexBytes.loadStamp]: a change re-renders the row, so bytes that arrived replace their placeholders. */
    @Suppress("UNUSED_PARAMETER") loadStamp: Int,
    row: Long,
    offsetDigits: Int,
    offsetColumnPx: Int,
    selection: HexSelectionState,
    searchState: HexSearchState,
    editState: HexEditState?,
    editStamp: Long,
    pendingDigit: Int?,
    structureRanges: List<StructureRange>,
    structureLookup: StructureRangeLookup,
    onTabChangeRequest: ((String) -> Unit)? = null,
    onPendingDrag: (Double, Double) -> Unit,
) {
    val offset = row * BYTES_PER_ROW
    val end = minOf(offset + BYTES_PER_ROW, bytes.size)
    // A byte still being fetched reads null and shows as a placeholder until its page arrives.
    fun byteAt(i: Long): Byte? = if (editState != null) editState.getByte(i.toInt()) else bytes.byteOrNull(i)

    // Determine structure color for the offset column (use the first byte's range)
    val offsetStructureIdx = if (structureRanges.isNotEmpty()) {
        structureLookup.indexAt(offset)
    } else -1
    val offsetStructureBg = if (offsetStructureIdx >= 0) {
        STRUCTURE_COLORS[offsetStructureIdx % STRUCTURE_COLORS.size]
    } else null

    // Indices of every structure range that starts on this row. Several sections
    // can start on the same row, and each of them gets its own label.
    val labelIndices = if (structureRanges.isNotEmpty()) {
        structureRanges.indices.filter { structureRanges[it].offset / BYTES_PER_ROW == row }
    } else emptyList()

    Div({
        classes("hex-row")
        style {
            display(DisplayStyle.Flex)
            property("gap", "0")
            height(ROW_HEIGHT_PX.px)
            property("align-items", "center")
        }
    }) {
        // Offset column
        Span({
            classes("hex-offset")
            style {
                property("min-width", "${offsetColumnPx}px")
                property("padding-right", "12px")
                property("text-align", "right")
                color(Color("#6A9955"))
                if (offsetStructureBg != null) {
                    property("background", offsetStructureBg)
                }
            }
        }) {
            Text(offset.toString(16).padStart(offsetDigits, '0'))
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
                val byteVal = byteAt(i)?.toUByte()
                val isSelected = selection.contains(byteIndex)
                val isCursor = selection.cursorIndex == byteIndex
                val isSearchMatch = searchState.isMatch(byteIndex)
                val isCurrentMatch = isSearchMatch && searchState.isInCurrentMatch(byteIndex)
                val isEdited = editState?.modifiedOffsets?.contains(byteIndex.toInt()) == true
                val hasPendingDigit = isCursor && pendingDigit != null

                val byteStructureIdx = if (structureRanges.isNotEmpty()) {
                    structureLookup.indexAt(byteIndex)
                } else -1
                val byteStructureBg = if (byteStructureIdx >= 0) {
                    STRUCTURE_COLORS[byteStructureIdx % STRUCTURE_COLORS.size]
                } else null

                Span({
                    classes("hex-byte")
                    if (isCurrentMatch) classes("hex-search-current")
                    else if (isSearchMatch) classes("hex-search-match")
                    if (isEdited) classes("hex-edited")
                    style {
                        property("width", "28px")
                        property("text-align", "center")
                        if (!isSelected && !isSearchMatch && byteStructureBg == null) {
                            property("border-radius", "2px")
                        }
                        if (isSelected && !isSearchMatch && !isEdited) {
                            property("background", "var(--hexana-selection, rgba(156, 183, 255, 0.4))")
                        } else if (!isSearchMatch && !isEdited && byteStructureBg != null) {
                            property("background", byteStructureBg)
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
                    if (hasPendingDigit) {
                        Text(pendingDigit!!.toString(16) + "_")
                    } else {
                        Text(byteVal?.toString(16)?.padStart(2, '0') ?: "\u00B7\u00B7")
                    }
                }
            }
            // Pad incomplete last row
            val missing = BYTES_PER_ROW - (end - offset).toInt()
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
                val rawByte = byteAt(i)
                val c = rawByte?.toInt()?.and(0xFF)?.toChar()
                val display = if (c != null && c in ' '..'~') c.toString() else if (c == null) " " else "."
                val isSelected = selection.contains(byteIndex)
                val isCursor = selection.cursorIndex == byteIndex
                val isSearchMatch = searchState.isMatch(byteIndex)
                val isCurrentMatch = isSearchMatch && searchState.isInCurrentMatch(byteIndex)
                val isEdited = editState?.modifiedOffsets?.contains(byteIndex.toInt()) == true

                val asciiStructureIdx = if (structureRanges.isNotEmpty()) {
                    structureLookup.indexAt(byteIndex)
                } else -1
                val asciiStructureBg = if (asciiStructureIdx >= 0) {
                    STRUCTURE_COLORS[asciiStructureIdx % STRUCTURE_COLORS.size]
                } else null

                Span({
                    classes("hex-ascii-char")
                    if (isCurrentMatch) classes("hex-search-current")
                    else if (isSearchMatch) classes("hex-search-match")
                    if (isEdited) classes("hex-edited")
                    style {
                        property("width", "8.5px")
                        property("text-align", "center")
                        color(Color("#CE9178"))
                        if (!isSelected && !isSearchMatch && asciiStructureBg == null) {
                            property("border-radius", "1px")
                        }
                        if (isSelected && !isSearchMatch && !isEdited) {
                            property("background", "var(--hexana-selection, rgba(156, 183, 255, 0.4))")
                        } else if (!isSearchMatch && !isEdited && asciiStructureBg != null) {
                            property("background", asciiStructureBg)
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

        // Section labels (shown on the row where structure ranges begin).
        // All sections starting on this row are shown side by side.
        labelIndices.forEachIndexed { position, labelIdx ->
            val labelRange = structureRanges[labelIdx]
            val isClickable = onTabChangeRequest != null && labelRange.tabName != null
            Span({
                classes("hexana-section-label")
                style {
                    property("margin-left", if (position == 0) "16px" else "4px")
                    property("padding", "0px 4px")
                    property("border-radius", "3px")
                    property("font-size", "9px")
                    property("align-self", "flex-start")
                    property("line-height", "normal")
                    property("white-space", "nowrap")
                    property("opacity", "0.85")
                    property("background", STRUCTURE_LABEL_COLORS[labelIdx % STRUCTURE_LABEL_COLORS.size])
                    color(Color("var(--vscode-editor-foreground, #ccc)"))
                    if (isClickable) {
                        cursor("pointer")
                    }
                }
                if (isClickable) {
                    onClick { onTabChangeRequest!!(labelRange.tabName!!) }
                }
            }) {
                Text(labelRange.label)
            }
        }
    }
}

private fun resolveColumn(relativeX: Double, offsetColumnPx: Int): Int {
    // The offset column plus its 12px gap comes first; the hex bytes are 28px each after it.
    val hexAreaStart = offsetColumnPx + 12.0
    val byteWidth = 28.0
    val col = ((relativeX - hexAreaStart) / byteWidth).toInt()
    return col.coerceIn(0, BYTES_PER_ROW - 1)
}
