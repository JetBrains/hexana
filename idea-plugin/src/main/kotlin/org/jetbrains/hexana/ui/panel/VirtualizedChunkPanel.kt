package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.actionSystem.IdeActions
import kotlinx.coroutines.launch
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.ChunkTree
import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.format.SectionKind
import org.jetbrains.hexana.document.format.TokenStream
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.SearchField
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar

// ---------------------------------------------------------------------------
// Common virtualized chunk panel
//
// Provides the shared skeleton for all virtualized binary views:
//   - collapsible section headers with offset info and titles
//   - multiline section body with syntax highlighting via TokenStream
//   - a search bar that accepts both symbol names and offsets
//   - a navigation popup showing named items with back/forward history
//   - a section shortcut bar for quick jumps
//   - a status bar showing current position
//
// Format-specific concerns (editing, badges, async decode, expand states)
// are injected via the [elementContent] slot composable.
// ---------------------------------------------------------------------------

/**
 * A search result produced by the panel's search logic. Implementations
 * provide a display label, optional detail text, and the [ChunkId] to
 * navigate to when the result is picked.
 */
internal interface ChunkSearchResult {
    val label: String
    val detail: String?
    val chunkId: ChunkId
}

/** Simple default implementation of [ChunkSearchResult]. */
internal data class SimpleChunkSearchResult(
    override val label: String,
    override val detail: String? = null,
    override val chunkId: ChunkId,
) : ChunkSearchResult

/**
 * Configuration for the common virtualized chunk panel. Groups the
 * format-specific callbacks that the panel delegates to.
 */
internal data class VirtualizedChunkPanelConfig(
    /** Human-readable section name for display (status bar, shortcut bar). */
    val sectionDisplayName: (SectionKind) -> String,
    /** Syntax-highlighting color for a token kind. */
    val colorForToken: (RenderTokenKind) -> Color = ::defaultColorFor,
    /** Placeholder text for the search bar. */
    val searchPlaceholder: String = "Search by name or offset",
    /**
     * Computes search results for the given query. Called on every keystroke;
     * implementations should be bounded and fast.
     */
    val computeSearchResults: (query: String) -> List<ChunkSearchResult> = { emptyList() },
    /**
     * Number of LazyColumn items a chunk currently occupies. Defaults to 1;
     * override for row-virtualized chunks (e.g. big WAT functions that emit
     * one item per row).
     */
    val itemCountForChunk: (ChunkId) -> Int = { 1 },
)

/**
 * Common virtualized panel that renders a [ChunkTree] as a scrollable
 * [LazyColumn] with collapsible section headers, a search bar, a section
 * shortcut bar, and a status bar.
 *
 * Format-specific element rendering is injected via [elementContent].
 * Format-specific toolbars (e.g. syntax selector) go in [toolbar].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VirtualizedChunkPanel(
    chunkTree: ChunkTree,
    config: VirtualizedChunkPanelConfig,
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
    foldedSectionsExternal: MutableState<Set<ChunkId>>? = null,
    /** Extra toolbar rendered between the search bar and the chunk list. */
    toolbar: @Composable (() -> Unit)? = null,
    /**
     * Renders a section header row. When null, the default shared header
     * is used (fold arrow + section display name + element count + size).
     */
    sectionHeaderContent: @Composable ((
        chunk: BinaryChunk.SectionHeader,
        folded: Boolean,
        onToggle: () -> Unit,
    ) -> Unit)? = null,
    /**
     * Emits LazyColumn items for an element chunk. Receives the
     * [LazyListScope] so it can emit one or more `item()` calls (e.g.
     * big-func row virtualization emits one item per row). Also receives
     * the navigator for follow-target handling. When null, a single
     * default item is emitted per chunk.
     */
    elementContent: (androidx.compose.foundation.lazy.LazyListScope.(
        chunk: BinaryChunk.Element,
        navigator: ChunkPanelNavigator,
    ) -> Unit)? = null,
    /**
     * Called when a search result is picked. Default navigates to the
     * result's chunkId via the navigator.
     */
    onSearchResultPicked: ((ChunkSearchResult, ChunkPanelNavigator) -> Unit)? = null,
    /**
     * Panel-level key event handler, called before the panel's own
     * back/forward/goto shortcuts. Return true to consume the event.
     */
    extraKeyHandler: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
) {
    val effectiveListState = listState ?: rememberLazyListState()
    val foldedSectionsState: MutableState<Set<ChunkId>> =
        foldedSectionsExternal ?: remember { mutableStateOf(emptySet()) }
    var foldedSections by foldedSectionsState

    val scope = rememberCoroutineScope()
    val navHistory = remember { ChunkNavHistoryState() }
    val navigator = remember(chunkTree, effectiveListState, config) {
        ChunkPanelNavigator(
            chunkTree = chunkTree,
            listState = effectiveListState,
            foldedSectionsState = foldedSectionsState,
            scope = scope,
            history = navHistory,
            itemCountForChunk = config.itemCountForChunk,
        )
    }

    val firstVisibleChunkIndex by remember(effectiveListState, chunkTree) {
        derivedStateOf {
            val targetItem = effectiveListState.firstVisibleItemIndex
            var cursor = 0
            var chunkIdx = 0
            for (i in 0 until chunkTree.totalChunks) {
                chunkIdx = i
                val count = config.itemCountForChunk(chunkTree.chunkAt(i).id)
                if (cursor + count > targetItem) break
                cursor += count
            }
            chunkIdx
        }
    }

    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    val searchResults = remember(searchQuery.text, chunkTree) {
        val formatResults = config.computeSearchResults(searchQuery.text)
        val offsetResults = searchChunksByOffset(searchQuery.text, chunkTree, config.sectionDisplayName)
        if (offsetResults.isEmpty()) formatResults else formatResults + offsetResults
    }

    var gotoPopupOpen by remember { mutableStateOf(false) }

    val panelKeyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) return@handler false
        if (KeymapShortcuts.isModifierOnly(event)) return@handler false
        // Let the caller handle keys first.
        if (extraKeyHandler?.invoke(event) == true) return@handler true
        val back = KeymapShortcuts.matches(event, IdeActions.ACTION_GOTO_BACK)
        val fwd = KeymapShortcuts.matches(event, IdeActions.ACTION_GOTO_FORWARD)
        val goto = KeymapShortcuts.matches(event, "GotoSymbol")
        when {
            back -> navigator.back()
            fwd -> navigator.forward()
            goto -> { gotoPopupOpen = true; true }
            else -> false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HexanaTheme.backgroundColor)
            .onPreviewKeyEvent(panelKeyHandler),
    ) {
        ChunkPanelSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            results = searchResults,
            placeholder = config.searchPlaceholder,
            colorForToken = config.colorForToken,
            onResultPicked = { result ->
                if (onSearchResultPicked != null) {
                    onSearchResultPicked(result, navigator)
                } else {
                    navigator.gotoChunk(result.chunkId)
                }
                searchQuery = TextFieldValue("")
            },
        )
        ChunkPanelSectionShortcutBar(
            chunkTree = chunkTree,
            navigator = navigator,
            sectionDisplayName = config.sectionDisplayName,
        )
        toolbar?.invoke()
        SelectionContainer(modifier = Modifier.fillMaxWidth().weight(1f)) {
          Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = effectiveListState, modifier = Modifier.fillMaxSize()) {
                var hidden = false
                for (i in 0 until chunkTree.totalChunks) {
                    val chunk = chunkTree.chunkAt(i)
                    when (chunk) {
                        is BinaryChunk.SectionHeader -> {
                            hidden = chunk.id in foldedSections
                            val isFolded = hidden
                            stickyHeader(key = chunk.id) {
                                if (sectionHeaderContent != null) {
                                    sectionHeaderContent(chunk, isFolded) {
                                        foldedSections = if (chunk.id in foldedSections) {
                                            foldedSections - chunk.id
                                        } else {
                                            foldedSections + chunk.id
                                        }
                                    }
                                } else {
                                    DefaultSectionHeaderRow(
                                        chunk = chunk,
                                        folded = isFolded,
                                        sectionDisplayName = config.sectionDisplayName,
                                        onToggle = {
                                            foldedSections = if (chunk.id in foldedSections) {
                                                foldedSections - chunk.id
                                            } else {
                                                foldedSections + chunk.id
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        is BinaryChunk.Element -> {
                            if (!hidden) {
                                if (elementContent != null) {
                                    with(this@LazyColumn) { elementContent(chunk, navigator) }
                                } else {
                                    item(key = chunk.id) {
                                        DefaultElementRow(chunk, config.colorForToken)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(effectiveListState, modifier = Modifier.align(Alignment.CenterEnd))
          }
        }
        ChunkPanelStatusBar(
            chunkTree = chunkTree,
            currentIndex = firstVisibleChunkIndex,
            sectionDisplayName = config.sectionDisplayName,
        )
    }
}

// ---------------------------------------------------------------------------
// Navigator
// ---------------------------------------------------------------------------

/** Navigation target: a chunk id + optional row index within the chunk. */
internal data class ChunkNavTarget(val chunkId: ChunkId, val rowIdx: Int? = null)

/**
 * Browser-style back / forward stacks plus the current position. Held in a
 * panel slot that survives rebuilds so navigation history is not erased.
 */
internal class ChunkNavHistoryState {
    val back: MutableList<ChunkNavTarget> = mutableListOf()
    val forward: MutableList<ChunkNavTarget> = mutableListOf()
    var current: ChunkNavTarget? = null
}

/**
 * Generic navigator for [VirtualizedChunkPanel]. Handles scroll-to-chunk,
 * unfold-section-containing, and back/forward history. Format-specific
 * navigation (e.g. funcIdx resolution) is built on top of this.
 */
internal class ChunkPanelNavigator(
    private val chunkTree: ChunkTree,
    private val listState: LazyListState,
    private val foldedSectionsState: MutableState<Set<ChunkId>>,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val history: ChunkNavHistoryState,
    private val itemCountForChunk: (ChunkId) -> Int = { 1 },
) {
    fun gotoChunk(chunkId: ChunkId, recordHistory: Boolean = true): Boolean {
        val target = ChunkNavTarget(chunkId = chunkId)
        if (chunkTree.chunkForId(chunkId) == null) return false
        if (recordHistory) {
            history.current?.let { history.back += it }
            history.forward.clear()
        }
        history.current = target
        scope.launch { scrollTo(target) }
        return true
    }

    fun back(): Boolean {
        val prev = history.back.removeLastOrNull() ?: return false
        history.current?.let { history.forward += it }
        history.current = prev
        scope.launch { scrollTo(prev) }
        return true
    }

    fun forward(): Boolean {
        val next = history.forward.removeLastOrNull() ?: return false
        history.current?.let { history.back += it }
        history.current = next
        scope.launch { scrollTo(next) }
        return true
    }

    val canBack: Boolean get() = history.back.isNotEmpty()
    val canForward: Boolean get() = history.forward.isNotEmpty()

    private suspend fun scrollTo(target: ChunkNavTarget) {
        unfoldSectionContaining(target.chunkId)
        val lazyIdx = computeLazyItemIndex(target.chunkId)
        if (lazyIdx >= 0) {
            // Scroll one item earlier so the target isn't hidden behind the
            // sticky section header that Compose pins at the top of the viewport.
            listState.scrollToItem(maxOf(0, lazyIdx - 1))
        }
    }

    private fun computeLazyItemIndex(chunkId: ChunkId): Int {
        var cursor = 0
        var hidden = false
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            when (c) {
                is BinaryChunk.SectionHeader -> {
                    hidden = c.id in foldedSectionsState.value
                    if (c.id == chunkId) return cursor
                    cursor++
                }
                is BinaryChunk.Element -> {
                    if (!hidden) {
                        if (c.id == chunkId) return cursor
                        cursor += itemCountForChunk(c.id)
                    }
                }
            }
        }
        return -1
    }

    private fun unfoldSectionContaining(chunkId: ChunkId) {
        var currentHeader: ChunkId? = null
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            when (c) {
                is BinaryChunk.SectionHeader -> currentHeader = c.id
                is BinaryChunk.Element -> if (c.id == chunkId) {
                    val header = currentHeader ?: return
                    if (header in foldedSectionsState.value) {
                        foldedSectionsState.value = foldedSectionsState.value - header
                    }
                    return
                }
            }
        }
    }

}

// ---------------------------------------------------------------------------
// Offset-based search
// ---------------------------------------------------------------------------

/**
 * Returns `true` when [text] looks like a hex string — either with a `0x`/`0X`
 * prefix or a bare string of hex digits (at least 2 chars to avoid matching
 * single decimal digits like "3").
 */
private fun looksLikeHex(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.startsWith("0x", ignoreCase = true)) {
        return trimmed.length > 2 && trimmed.substring(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
    // Bare hex: require at least 2 chars and all hex digits.
    // Also require at least one letter a-f to distinguish from plain decimal numbers.
    return trimmed.length >= 2 &&
        trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } &&
        trimmed.any { it in 'a'..'f' || it in 'A'..'F' }
}

/**
 * Searches the [chunkTree] for chunks whose byte-range offset (formatted as
 * hex) contains the query substring. Returns up to 30 results. Only called
 * when the query [looksLikeHex].
 */
private fun searchChunksByOffset(
    query: String,
    chunkTree: ChunkTree,
    sectionDisplayName: (SectionKind) -> String,
    limit: Int = 30,
): List<ChunkSearchResult> {
    val trimmed = query.trim()
    if (!looksLikeHex(trimmed)) return emptyList()
    val needle = if (trimmed.startsWith("0x", ignoreCase = true)) {
        trimmed.substring(2).lowercase()
    } else {
        trimmed.lowercase()
    }
    if (needle.isEmpty()) return emptyList()

    val results = ArrayList<ChunkSearchResult>(limit)
    for (i in 0 until chunkTree.totalChunks) {
        if (results.size >= limit) break
        val chunk = chunkTree.chunkAt(i)
        val offsetHex = chunk.byteRange.first.toString(16).lowercase()
        if (needle in offsetHex) {
            val label = when (chunk) {
                is BinaryChunk.SectionHeader -> sectionDisplayName(chunk.kind)
                is BinaryChunk.Element -> chunk.displayName ?: "${sectionDisplayName(chunk.kind)}[${chunk.indexInSection}]"
            }
            results += SimpleChunkSearchResult(
                label = label,
                detail = "@ 0x${offsetHex.uppercase()}",
                chunkId = chunk.id,
            )
        }
    }
    return results
}

// ---------------------------------------------------------------------------
// Search bar
// ---------------------------------------------------------------------------

@Composable
private fun ChunkPanelSearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    results: List<ChunkSearchResult>,
    placeholder: String,
    colorForToken: (RenderTokenKind) -> Color,
    onResultPicked: (ChunkSearchResult) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.Enter
                ) {
                    results.firstOrNull()?.let(onResultPicked)
                    true
                } else {
                    false
                }
            },
        )
        if (results.isNotEmpty()) {
            val suggestionsListState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                LazyColumn(state = suggestionsListState, modifier = Modifier.fillMaxWidth()) {
                    items(results) { result ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onResultPicked(result) }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                result.label,
                                style = TextStyle(fontFamily = FontFamily.Monospace),
                            )
                            val detail = result.detail
                            if (detail != null) {
                                Text(
                                    detail,
                                    style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    color = HexanaTheme.hexTextColor,
                                ),
                            )
                        }
                    }
                }
                }
                VerticalScrollbar(suggestionsListState, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section shortcut bar
// ---------------------------------------------------------------------------

@Composable
internal fun ChunkPanelSectionShortcutBar(
    chunkTree: ChunkTree,
    navigator: ChunkPanelNavigator,
    sectionDisplayName: (SectionKind) -> String,
) {
    val entries: List<Pair<ChunkId, String>> = remember(chunkTree) {
        val out = ArrayList<Pair<ChunkId, String>>()
        val nameCounts = HashMap<String, Int>()
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            if (c is BinaryChunk.SectionHeader) {
                val base = sectionDisplayName(c.kind)
                val count = nameCounts[base] ?: 0
                nameCounts[base] = count + 1
                val label = if (count == 0) base else "$base#$count"
                out += c.id to label
            }
        }
        out
    }
    if (entries.isEmpty()) return

    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HexanaTheme.backgroundColor)
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for ((chunkId, label) in entries) {
            Text(
                text = label,
                style = CHUNK_PANEL_ROW_TEXT_STYLE.copy(
                    color = HexanaTheme.textColor,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.clickable {
                    navigator.gotoChunk(chunkId)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Default section header
// ---------------------------------------------------------------------------

@Composable
private fun DefaultSectionHeaderRow(
    chunk: BinaryChunk.SectionHeader,
    folded: Boolean,
    sectionDisplayName: (SectionKind) -> String,
    onToggle: () -> Unit,
) {
    val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = HexanaTheme.textColor,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "0x${chunk.byteRange.first.toString(16).uppercase().padStart(8, '0')}",
            style = textStyle.copy(color = HexanaTheme.hexTextColor),
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
        Text(
            text = if (folded) "▶" else "▼",
            style = textStyle.copy(color = HexanaTheme.hexTextColor),
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
        Text(
            text = "(${sectionDisplayName(chunk.kind)})  " +
                "${chunk.elementCount} entries · ${chunkFormatBytes(chunk.totalSize)}",
            style = textStyle,
        )
    }
}

// ---------------------------------------------------------------------------
// Default element row
// ---------------------------------------------------------------------------

@Composable
private fun DefaultElementRow(
    chunk: BinaryChunk.Element,
    colorForToken: (RenderTokenKind) -> Color,
) {
    val name = chunk.displayName ?: "${chunk.kind.id}#${chunk.indexInSection}"
    Text(
        text = "(; $name @ 0x${chunk.byteRange.first.toString(16).uppercase()} ;)",
        style = CHUNK_PANEL_ROW_TEXT_STYLE.copy(color = colorForToken(RenderTokenKind.COMMENT)),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
    )
}

// ---------------------------------------------------------------------------
// Status bar
// ---------------------------------------------------------------------------

@Composable
private fun ChunkPanelStatusBar(
    chunkTree: ChunkTree,
    currentIndex: Int,
    sectionDisplayName: (SectionKind) -> String,
) {
    Divider(orientation = Orientation.Horizontal, thickness = 1.dp, modifier = Modifier.fillMaxWidth())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HexanaTheme.backgroundColor)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HexanaTheme.hexTextColor,
        )
        if (chunkTree.totalChunks == 0) {
            Text("No chunks", style = style)
            return@Row
        }
        val chunk = chunkTree.chunkAt(currentIndex.coerceAtMost(chunkTree.totalChunks - 1))
        val sectionLabel = sectionDisplayName(chunk.kind)
        val offsetLabel = "0x${chunk.byteRange.first.toString(16).uppercase().padStart(8, '0')}"
        val nameLabel = when (chunk) {
            is BinaryChunk.Element -> chunk.displayName ?: "${sectionLabel}#${chunk.indexInSection}"
            is BinaryChunk.SectionHeader -> "(section header)"
        }
        Text("Chunk ${currentIndex + 1} / ${chunkTree.totalChunks}", style = style)
        Text("Section: $sectionLabel", style = style)
        Text("Offset: $offsetLabel", style = style)
        Text(nameLabel, style = style)
    }
}

// ---------------------------------------------------------------------------
// Shared utilities
// ---------------------------------------------------------------------------

/** Shared monospace text style for chunk panel rows. */
internal val CHUNK_PANEL_ROW_TEXT_STYLE = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    color = Color.Unspecified,
)

/** Default syntax-highlighting color scheme. */
internal fun defaultColorFor(kind: RenderTokenKind): Color = when (kind) {
    RenderTokenKind.KEYWORD -> Color(0xFF569CD6)
    RenderTokenKind.IDENTIFIER -> Color(0xFF9CDCFE)
    RenderTokenKind.NUMBER -> Color(0xFFB5CEA8)
    RenderTokenKind.STRING -> Color(0xFFCE9178)
    RenderTokenKind.COMMENT -> Color(0xFF7F848E)
    RenderTokenKind.OFFSET_ANNOTATION -> Color(0xFFD7BA7D)
    RenderTokenKind.TYPE_REF -> Color(0xFF4EC9B0)
    RenderTokenKind.FUNC_REF -> Color(0xFFDCDCAA)
    RenderTokenKind.ERROR -> Color(0xFFF44747)
    else -> Color.Unspecified
}

/** Token kinds that form the structural prefix (offset gutter, hex bytes, indent). */
internal val STRUCTURAL_PREFIX_KINDS = setOf(
    RenderTokenKind.OFFSET_ANNOTATION,
    RenderTokenKind.RAW_BYTES,
    RenderTokenKind.WHITESPACE,
)

/**
 * Renders a token list as a [Row] where the structural prefix (offsets, hex bytes,
 * whitespace) is wrapped in [DisableSelection] so only the content tokens are
 * selectable when inside a [SelectionContainer]. Must be called from within a
 * [SelectionContainer] scope for the selection gating to take effect.
 */
@Composable
internal fun SelectableTokenRow(
    tokens: List<RenderToken>,
    style: TextStyle = CHUNK_PANEL_ROW_TEXT_STYLE,
    color: Color = Color.Unspecified,
    colorForToken: (RenderTokenKind) -> Color = ::defaultColorFor,
    modifier: Modifier = Modifier,
    onOffsetClicked: ((Long) -> Unit)? = null,
) {
    val prefixEnd = tokens.indexOfFirst { it.kind !in STRUCTURAL_PREFIX_KINDS }
    if (prefixEnd <= 0) {
        // No prefix or all-prefix — render as a single Text.
        Text(
            text = tokens.toHighlightedAnnotatedString(colorForToken),
            style = style.copy(color = color),
            modifier = modifier,
        )
        return
    }
    val prefixTokens = tokens.subList(0, prefixEnd)
    val contentTokens = tokens.subList(prefixEnd, tokens.size)
    Row(modifier = modifier) {
        DisableSelection {
            if (onOffsetClicked != null) {
                // Render offset tokens as clickable; others as plain text.
                for (token in prefixTokens) {
                    val offset = token.sourceOffset
                    if (token.kind == RenderTokenKind.OFFSET_ANNOTATION && offset != null) {
                        Text(
                            text = AnnotatedString(token.text, SpanStyle(color = colorForToken(token.kind))),
                            style = style.copy(color = color),
                            modifier = Modifier.clickable { onOffsetClicked(offset) },
                        )
                    } else {
                        Text(
                            text = AnnotatedString(token.text, SpanStyle(color = colorForToken(token.kind))),
                            style = style.copy(color = color),
                        )
                    }
                }
            } else {
                Text(
                    text = prefixTokens.toHighlightedAnnotatedString(colorForToken),
                    style = style.copy(color = color),
                )
            }
        }
        Text(
            text = contentTokens.toHighlightedAnnotatedString(colorForToken),
            style = style.copy(color = color),
        )
    }
}

/** Converts a list of [RenderToken]s to an [AnnotatedString] with syntax highlighting. */
internal fun List<RenderToken>.toHighlightedAnnotatedString(
    colorForToken: (RenderTokenKind) -> Color = ::defaultColorFor,
): AnnotatedString = buildAnnotatedString {
    for (t in this@toHighlightedAnnotatedString) {
        if (t.kind == RenderTokenKind.LINE_BREAK) continue
        withStyle(SpanStyle(color = colorForToken(t.kind))) {
            append(t.text)
        }
    }
}

/** Converts a [TokenStream] to an [AnnotatedString] with syntax highlighting. */
internal fun TokenStream.toHighlightedAnnotatedString(
    colorForToken: (RenderTokenKind) -> Color = ::defaultColorFor,
): AnnotatedString = tokens.toHighlightedAnnotatedString(colorForToken)

/** Splits a flat token list into lines at [RenderTokenKind.LINE_BREAK] boundaries. */
internal fun splitTokensIntoLines(tokens: List<RenderToken>): List<List<RenderToken>> {
    if (tokens.isEmpty()) return emptyList()
    val result = mutableListOf<List<RenderToken>>()
    var current = mutableListOf<RenderToken>()
    for (token in tokens) {
        if (token.kind == RenderTokenKind.LINE_BREAK) {
            result += current
            current = mutableListOf()
        } else {
            current += token
        }
    }
    if (current.isNotEmpty()) result += current
    return result
}

/** Human-readable byte size formatting. */
internal fun chunkFormatBytes(size: Long): String = when {
    size < 1024 -> "${size}B"
    size < 1024 * 1024 -> "${size / 1024}KB"
    else -> "${size / (1024 * 1024)}MB"
}
