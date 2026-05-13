package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.io.encoding.Base64
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.document.EditableBinaryDocument
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.format.TokenStream
import org.jetbrains.hexana.document.BinaryDocumentSnapshot
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.journal.JournalOp
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.EditableRowEncoder
import org.jetbrains.hexana.providers.format.EditableRowInfo
import org.jetbrains.hexana.providers.format.RenderedRow
import org.jetbrains.hexana.providers.format.RowEncodeResult
import org.jetbrains.hexana.providers.format.WasmChunkTree
import org.jetbrains.hexana.providers.format.WasmHexanaWatRenderer
import org.jetbrains.hexana.providers.format.WasmParsedBinary
import org.jetbrains.hexana.providers.format.WasmSectionKinds
import org.jetbrains.hexana.providers.format.buildWasmChunkTree
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/**
 * PR 1 of the new virtualized WAT viewer. Compose `LazyColumn` over the
 * format-agnostic `BinaryChunk` tree built from `WasmBinaryProvider`. One
 * chunk per renderable unit (section header, element). Code-section chunks
 * are per-function; the Function section is a single chunk per design
 * decision 5.
 *
 * Renders a placeholder body (`(; $name @ 0xXXXX ;)`) per chunk — the real
 * Hexana-flavored WAT printer lands in PR 2. PR 3 adds the toolbar (search,
 * jump-to-function). PR 4 adds the hex ↔ WAT cross-link.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WasmPanelWatVirtualized(
    provider: WasmBinaryProvider,
    state: HexViewStateHolder,
    editableDoc: EditableBinaryDocument? = null,
    /**
     * LazyColumn scroll state. When supplied externally (typical case from
     * BinaryEditorView), the state lives in a slot that doesn't reset when
     * `provider` rebuilds after an edit, so scroll position is preserved
     * across commits. Defaults to a fresh per-panel state for callers
     * outside the edit pipeline.
     */
    listState: LazyListState? = null,
    /**
     * Set of folded section IDs. Same lifecycle reasoning as [listState] —
     * lifting to a stable slot above the panel keeps the user's open/
     * collapsed pattern intact across edit commits.
     */
    foldedSectionsExternal: MutableState<Set<ChunkId>>? = null,
) {
    val panelState = remember(provider) { buildPanelState(provider) }
    val chunkTree = panelState.chunkTree
    val renderer = panelState.renderer
    val snapshot = panelState.snapshot

    val effectiveListState = listState ?: rememberLazyListState()
    val firstVisibleIndex by remember(effectiveListState) {
        derivedStateOf { effectiveListState.firstVisibleItemIndex }
    }
    val foldedSectionsState: MutableState<Set<ChunkId>> =
        foldedSectionsExternal ?: remember { mutableStateOf(emptySet<ChunkId>()) }
    var foldedSections by foldedSectionsState

    // After an append+redirect commit, the user's actual edit lives in the
    // newly-appended last code chunk — not in the redirect stub that took
    // the original body's place. Track the code-chunk count across panel
    // rebuilds; when it grows (= a function was appended), unfold the code
    // section if needed and scroll to the new entry so the user immediately
    // sees the result of their edit.
    val totalCodeChunks = remember(chunkTree) {
        var n = 0
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            if (c is BinaryChunk.Element && c.kind == WasmSectionKinds.CODE) n++
        }
        n
    }
    // Cross-chunk arrow navigation: each ChunkRow registers its
    // FocusRequesters into this panel-level map keyed by (chunkId, rowIdx).
    // When a row receives arrow Up at the top edge / arrow Down at the
    // bottom edge of its chunk, it asks the panel to hop to the
    // prev/next code chunk via [navUp] / [navDown]. Only works when the
    // target chunk is currently composed (i.e. visible or in the
    // LazyColumn pre-fetch window) — out-of-view targets do nothing.
    val focusMap = remember { mutableStateMapOf<Pair<ChunkId, Int>, FocusRequester>() }
    val codeChunkOrder: List<ChunkId> = remember(chunkTree) {
        val out = ArrayList<ChunkId>()
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            if (c is BinaryChunk.Element && c.kind == WasmSectionKinds.CODE) out += c.id
        }
        out
    }
    val navUp: (ChunkId) -> Unit = { currentId ->
        val pos = codeChunkOrder.indexOf(currentId)
        if (pos > 0) {
            val prevId = codeChunkOrder[pos - 1]
            val maxRow = focusMap.keys
                .filter { it.first == prevId }
                .maxOfOrNull { it.second }
            if (maxRow != null) {
                runCatching { focusMap[prevId to maxRow]?.requestFocus() }
            }
        }
    }
    val navDown: (ChunkId) -> Unit = { currentId ->
        val pos = codeChunkOrder.indexOf(currentId)
        if (pos in 0 until codeChunkOrder.size - 1) {
            val nextId = codeChunkOrder[pos + 1]
            val minRow = focusMap.keys
                .filter { it.first == nextId }
                .minOfOrNull { it.second }
            if (minRow != null) {
                runCatching { focusMap[nextId to minRow]?.requestFocus() }
            }
        }
    }

    // Code-entry indices that this session's append+redirect ops created.
    // Edits on these chunks bypass the append+redirect path and use
    // in-place cascade — we already cloned the function once; cloning the
    // clone every keystroke produces a chain of redirects with no value.
    var appendedCodeIndices by remember { mutableStateOf(emptySet<Int>()) }

    var lastTotalCodeCount by remember { mutableStateOf(-1) }
    androidx.compose.runtime.LaunchedEffect(totalCodeChunks) {
        val prev = lastTotalCodeCount
        lastTotalCodeCount = totalCodeChunks
        if (prev >= 0 && totalCodeChunks > prev) {
            // Auto-unfold the code section so the new entry is reachable.
            var codeHeaderId: ChunkId? = null
            for (i in 0 until chunkTree.totalChunks) {
                val c = chunkTree.chunkAt(i)
                if (c is BinaryChunk.SectionHeader && c.kind == WasmSectionKinds.CODE) {
                    codeHeaderId = c.id
                    break
                }
            }
            if (codeHeaderId != null && codeHeaderId in foldedSections) {
                foldedSections = foldedSections - codeHeaderId
            }
            // Walk the tree (in current fold state) to find the LazyColumn item
            // index AND chunk id of the LAST visible code element.
            var lazyIdx = -1
            var targetChunkId: ChunkId? = null
            var targetIndexInSection = -1
            var lazyCursor = 0
            var hidden = false
            for (i in 0 until chunkTree.totalChunks) {
                val c = chunkTree.chunkAt(i)
                when (c) {
                    is BinaryChunk.SectionHeader -> {
                        hidden = c.id in foldedSections
                        lazyCursor++
                    }
                    is BinaryChunk.Element -> {
                        if (!hidden) {
                            if (c.kind == WasmSectionKinds.CODE) {
                                lazyIdx = lazyCursor
                                targetChunkId = c.id
                                targetIndexInSection = c.indexInSection
                            }
                            lazyCursor++
                        }
                    }
                }
            }
            // Remember the new code-entry index so subsequent edits on it
            // don't trigger another append+redirect. The set lives across
            // panel rebuilds because `remember { … }` (no key) preserves
            // the slot through liveProvider changes.
            if (targetIndexInSection >= 0) {
                appendedCodeIndices = appendedCodeIndices + targetIndexInSection
            }
            if (lazyIdx >= 0 && targetChunkId != null) {
                // First, scroll the new chunk into view. animateScrollToItem
                // suspends until the animation completes — by then LazyColumn
                // has composed the target item.
                effectiveListState.animateScrollToItem(lazyIdx)
                // Then focus the chunk's FIRST editable row. ChunkRow's
                // DisposableEffect registers FocusRequesters into the panel-
                // level focusMap on composition; once the entry appears, a
                // requestFocus() call lands on a real attached requester and
                // Compose's bring-into-view fine-tunes the scroll inside the
                // chunk so the user's cursor sits on the body, not the header.
                //
                // Composition can lag the scroll by a frame or two — poll
                // briefly (max ~250 ms total) until the focusMap entry shows
                // up. If it never does (e.g. the chunk has no editable rows),
                // we silently give up; the scroll alone is still useful.
                var focused = false
                for (attempt in 0 until 5) {
                    val firstRow = focusMap.keys
                        .filter { it.first == targetChunkId }
                        .minOfOrNull { it.second }
                    val req = firstRow?.let { focusMap[targetChunkId!! to it] }
                    if (req != null) {
                        runCatching { req.requestFocus() }
                        focused = true
                        break
                    }
                    kotlinx.coroutines.delay(50)
                }
                @Suppress("UNUSED_VARIABLE") focused
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = effectiveListState, modifier = Modifier.fillMaxSize()) {
                var hidden = false
                for (i in 0 until chunkTree.totalChunks) {
                    val chunk = chunkTree.chunkAt(i)
                    when (chunk) {
                        is BinaryChunk.SectionHeader -> {
                            hidden = chunk.id in foldedSections
                            val isFolded = hidden
                            stickyHeader(key = chunk.id) {
                                SectionHeaderRow(
                                    chunk = chunk,
                                    snapshot = snapshot,
                                    folded = isFolded,
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
                        is BinaryChunk.Element -> {
                            if (!hidden) {
                                item(key = chunk.id) {
                                    ChunkRow(
                                        chunk = chunk,
                                        renderer = renderer,
                                        snapshot = snapshot,
                                        provider = provider,
                                        editableDoc = editableDoc,
                                        focusMap = focusMap,
                                        onCrossChunkUp = navUp,
                                        onCrossChunkDown = navDown,
                                        isAppendedFunc = chunk.kind == WasmSectionKinds.CODE &&
                                            chunk.indexInSection in appendedCodeIndices,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        StatusBar(chunkTree = chunkTree, currentIndex = firstVisibleIndex)
    }
}

private data class PanelState(
    val chunkTree: WasmChunkTree,
    val renderer: WasmHexanaWatRenderer,
    val snapshot: BinaryDocumentSnapshot,
)

private fun buildPanelState(provider: WasmBinaryProvider): PanelState {
    // Phase 1 stub: copy provider bytes into a BinaryDocument so the chunk tree
    // builder has a valid BinaryDocumentSnapshot to attach. Phase 2 plumbs the
    // document directly so this copy goes away.
    val bytes = provider.bytes.toByteArray()
    val snapshot = binaryDocumentOf(bytes).snapshot()
    val parsed = WasmParsedBinary.fromProvider(provider, snapshot)
    return PanelState(
        chunkTree = buildWasmChunkTree(parsed),
        renderer = WasmHexanaWatRenderer(parsed),
        snapshot = snapshot,
    )
}

@Composable
private fun SectionHeaderRow(
    chunk: BinaryChunk.SectionHeader,
    snapshot: BinaryDocumentSnapshot,
    folded: Boolean,
    onToggle: () -> Unit,
) {
    val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = HexanaTheme.textColor,
    )

    // Build the hex preview once per chunk — same format as emitHexBytes in the
    // renderer. Column widths come from WasmHexanaWatRenderer's companion so the
    // sticky header lines up with body rows even if the renderer changes them.
    val hexPreview = remember(chunk.id) {
        val source = snapshot.byteSource()
        val rangeStart = chunk.byteRange.first
        val rangeLen = (chunk.byteRange.last - rangeStart + 1).coerceAtLeast(0L).toInt()
        val available = (source.length - rangeStart).coerceAtLeast(0L).toInt()
        val length = if (rangeStart < 0L) 0 else minOf(rangeLen, available)
        val capped = length.coerceAtMost(WasmHexanaWatRenderer.MAX_BYTES_DISPLAYED)
        val sb = StringBuilder(WasmHexanaWatRenderer.BYTES_WIDTH)
        for (i in 0 until capped) {
            val b = source.get(rangeStart + i)
            if (i > 0) sb.append(' ')
            sb.append(((b.toInt() and 0xFF).toString(16).uppercase()).padStart(2, '0'))
        }
        if (length > WasmHexanaWatRenderer.MAX_BYTES_DISPLAYED) {
            sb.append(" …")
        }
        sb.toString().padEnd(WasmHexanaWatRenderer.BYTES_WIDTH)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Offset gutter — matches GUTTER_WIDTH layout of body rows.
        Text(
            text = "0x${chunk.byteRange.first.toString(16).uppercase().padStart(8, '0')}",
            style = textStyle.copy(color = HexanaTheme.hexTextColor),
        )
        Spacer(modifier = Modifier.width(WasmHexanaWatRenderer.GAP.dp))
        // Hex preview column — mirrors the RAW_BYTES column in body rows.
        Text(
            text = hexPreview,
            style = textStyle.copy(color = HexanaTheme.hexTextColor),
        )
        Spacer(modifier = Modifier.width(WasmHexanaWatRenderer.GAP.dp))
        Text(
            text = if (folded) "▶" else "▼",
            style = textStyle.copy(color = HexanaTheme.hexTextColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "(${WasmSectionKinds.displayName(chunk.kind)})  " +
                "${chunk.elementCount} entries · ${formatBytes(chunk.totalSize)}",
            style = textStyle,
        )
    }
}

private val ROW_TEXT_STYLE = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    color = Color.Unspecified,
)

@Composable
private fun ChunkRow(
    chunk: BinaryChunk.Element,
    renderer: WasmHexanaWatRenderer,
    snapshot: BinaryDocumentSnapshot,
    provider: WasmBinaryProvider,
    editableDoc: EditableBinaryDocument?,
    /**
     * Panel-level map of `(chunkId, rowIdx) → FocusRequester`. ChunkRow
     * registers its requesters here on first composition and removes them
     * on dispose, so the panel-level cross-chunk navigation can look up
     * neighbors' requesters by (id, rowIdx).
     */
    focusMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Pair<ChunkId, Int>, FocusRequester>? = null,
    /** Move focus to the previous code chunk's last editable row. */
    onCrossChunkUp: ((ChunkId) -> Unit)? = null,
    /** Move focus to the next code chunk's first editable row. */
    onCrossChunkDown: ((ChunkId) -> Unit)? = null,
    /**
     * `true` when this chunk was created by an append+redirect commit in
     * the current session. Edits on it bypass append+redirect and use
     * the in-place cascade path — we already cloned the function once;
     * cloning the clone every keystroke produces a chain of redirects.
     */
    isAppendedFunc: Boolean = false,
) {
    // Re-render whenever the renderer or snapshot changes (i.e. after a
    // commit replaces `liveProvider` and rebuilds `panelState`). Keying on
    // `chunk.id` alone is wrong: LazyColumn preserves the item slot across
    // panel rebuilds because `chunk.id` is stable, so a `remember(chunk.id)`
    // returns the PRE-edit `EditableRowInfo` objects (with stale opStart
    // anchors). Subsequent length-changing edits then feed those stale
    // offsets to the encoder, which corrupts the section sizes and crashes
    // the post-edit re-parse with `Unknown data record type: …`.
    val rows = remember(chunk.id, renderer, snapshot) { renderer.renderRows(snapshot, chunk) }
    // Per-row "open insert below" state — survives recompositions until
    // the chunk rebuilds (after a successful commit). Map<rowIndex, true>
    // means an empty insert TextField is showing below that row.
    val insertOpen = remember(chunk.id) { mutableStateMapOf<Int, Boolean>() }
    // FocusRequester per editable row index. Lets arrow Up/Down hop focus
    // between editable rows inside this chunk. Keyed on `chunk.id` plus
    // `rows.size` so the requesters survive snapshot-only rebuilds (length-
    // preserving edits) and only rebuild when the row count actually
    // changes (insert / delete / append redirect into a different chunk).
    val focusRequesters: Map<Int, FocusRequester> = remember(chunk.id, rows.size) {
        rows.indices
            .filter { rows[it].editable != null }
            .associateWith { FocusRequester() }
    }
    val editableIndices: List<Int> = remember(chunk.id, rows.size) {
        rows.indices.filter { rows[it].editable != null }
    }
    // Register/unregister this chunk's editable-row requesters into the
    // panel-level map so cross-chunk navigation can find them. Re-keyed
    // when the requester set changes (insert / delete row, append+redirect
    // adds new code chunk).
    if (focusMap != null) {
        DisposableEffect(chunk.id, focusRequesters) {
            for ((rowIdx, req) in focusRequesters) focusMap[chunk.id to rowIdx] = req
            onDispose {
                for (rowIdx in focusRequesters.keys) focusMap.remove(chunk.id to rowIdx)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            val pos = editableIndices.indexOf(rowIndex)
            // Within-chunk arrow nav. At the top of the first editable row,
            // fall through to the cross-chunk callback (prev chunk's last
            // editable row); at the bottom of the last, similarly call the
            // cross-chunk-down. When the cross callback is null or no
            // neighbor is currently composed, the boundary row's TextField
            // sees the arrow normally.
            val onArrowUp: (() -> Unit)? = when {
                pos > 0 -> {
                    { focusRequesters[editableIndices[pos - 1]]?.requestFocus() }
                }
                pos == 0 && onCrossChunkUp != null -> {
                    { onCrossChunkUp(chunk.id) }
                }
                else -> null
            }
            val onArrowDown: (() -> Unit)? = when {
                pos in 0 until editableIndices.size - 1 -> {
                    { focusRequesters[editableIndices[pos + 1]]?.requestFocus() }
                }
                pos == editableIndices.size - 1 && pos >= 0 && onCrossChunkDown != null -> {
                    { onCrossChunkDown(chunk.id) }
                }
                else -> null
            }
            RenderedRowView(
                row = row,
                chunkId = chunk.id,
                rowIndex = rowIndex,
                editableDoc = editableDoc,
                provider = provider,
                chunk = chunk,
                onEnterRequestInsert = if (editableDoc != null && row.editable?.kind == EditableRowInfo.Kind.OPCODE) {
                    { insertOpen[rowIndex] = true }
                } else {
                    null
                },
                focusRequester = focusRequesters[rowIndex],
                onArrowUp = onArrowUp,
                onArrowDown = onArrowDown,
                isAppendedFunc = isAppendedFunc,
            )
            val editable = row.editable
            if (insertOpen[rowIndex] == true && editableDoc != null && editable != null) {
                InsertedInstructionRow(
                    insertOffset = editable.opStart + editable.opEncodedLength.toLong(),
                    cascade = editable.cascade,
                    localCount = editable.localCount,
                    editableDoc = editableDoc,
                    provider = provider,
                    chunk = chunk,
                    onClose = { insertOpen.remove(rowIndex) },
                    isAppendedFunc = isAppendedFunc,
                )
            }
        }
    }
}

/**
 * Per-row composable. Non-editable rows render the whole row as a single
 * styled `Text`. Editable rows split the row into a structural prefix
 * (offset gutter + hex column + indent) rendered as a static `Text` plus a
 * `BasicTextField` whose initial content is [EditableRowInfo.watText]; on
 * Enter or focus-loss we run [EditableRowEncoder.encode] and commit a
 * transaction on success.
 *
 * Error UX (red border, tooltip, Esc-revert) is Phase C.4 — for now an
 * encoder failure just keeps the field's text live without committing.
 */
@Composable
private fun RenderedRowView(
    row: RenderedRow,
    chunkId: ChunkId,
    rowIndex: Int,
    editableDoc: EditableBinaryDocument?,
    /**
     * Provider + chunk are needed for the Phase E append+redirect path
     * (the encoder reads function-section / type-section / code-section
     * structural metadata from the provider, and locates the original
     * function entry via the chunk). Null when the row isn't in a code
     * chunk — append-edit only applies to function-body edits.
     */
    provider: WasmBinaryProvider? = null,
    chunk: BinaryChunk.Element? = null,
    /**
     * Called after a successful commit (or no-op if the field is unchanged)
     * so the panel can open an empty insert row below this one. Null when
     * the row isn't an opcode row (locals rows don't trigger insert-below
     * in this slice).
     */
    onEnterRequestInsert: (() -> Unit)? = null,
    /**
     * Owned by [ChunkRow]; attached to the inner [BasicTextField] so the
     * row above / below can request focus on arrow-key nav.
     */
    focusRequester: FocusRequester? = null,
    /** Move focus to the previous editable row in the chunk. */
    onArrowUp: (() -> Unit)? = null,
    /** Move focus to the next editable row in the chunk. */
    onArrowDown: (() -> Unit)? = null,
    /** See [ChunkRow.isAppendedFunc]. Forwarded to `commitRowEdit`. */
    isAppendedFunc: Boolean = false,
) {
    val info = row.editable
    val color = HexanaTheme.textColor
    if (info == null || editableDoc == null) {
        Text(
            text = row.tokens.toAnnotatedString(),
            style = ROW_TEXT_STYLE.copy(color = color),
        )
        return
    }

    // Split tokens into a structural prefix (offset gutter + hex column +
    // indent whitespace) and the editable suffix. The TextField only holds
    // the suffix's text, pre-filled with info.watText (which the renderer
    // already strips of structural decoration).
    val prefixEnd = row.tokens.indexOfFirst { it.kind !in PREFIX_KINDS }
    val prefixTokens = if (prefixEnd > 0) row.tokens.subList(0, prefixEnd) else row.tokens
    val prefix = prefixTokens.toAnnotatedString()

    var fieldText by remember(chunkId, rowIndex, info.watText) { mutableStateOf(info.watText) }
    var lastError by remember(chunkId, rowIndex) { mutableStateOf<String?>(null) }
    val errorColor = colorFor(RenderTokenKind.ERROR)
    val borderModifier = if (lastError != null) {
        Modifier.border(width = 1.dp, color = errorColor, shape = RoundedCornerShape(2.dp))
    } else {
        Modifier
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = prefix, style = ROW_TEXT_STYLE.copy(color = color))
        // Local capture so the lambdas below see a stable reference even
        // when the parent recomposes the requester map.
        val requesterModifier = if (focusRequester != null) {
            Modifier.focusRequester(focusRequester)
        } else {
            Modifier
        }
        BasicTextField(
            value = fieldText,
            onValueChange = { fieldText = it; lastError = null },
            singleLine = true,
            cursorBrush = SolidColor(color),
            textStyle = ROW_TEXT_STYLE.copy(color = colorFor(RenderTokenKind.KEYWORD)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    val err = commitRowEdit(editableDoc, info, fieldText, provider, chunk, isAppendedFunc)
                    lastError = err
                    if (err == null) onEnterRequestInsert?.invoke()
                },
            ),
            modifier = Modifier
                // Red border on encoder failure — clears as soon as the
                // user types again (onValueChange resets lastError).
                .then(borderModifier)
                .then(requesterModifier)
                // Direct keyboard handling. KeyboardActions.onDone catches
                // the IME path; on desktop Compose with no IME the Enter
                // key doesn't always reach it, so we handle Enter / arrows
                // / Esc explicitly here. Returning `true` consumes the
                // event so the BasicTextField doesn't also process it.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            fieldText = info.watText
                            lastError = null
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            val err = commitRowEdit(editableDoc, info, fieldText, provider, chunk, isAppendedFunc)
                            lastError = err
                            if (err == null) onEnterRequestInsert?.invoke()
                            true
                        }
                        Key.DirectionUp -> {
                            if (onArrowUp != null) {
                                onArrowUp.invoke()
                                true
                            } else false
                        }
                        Key.DirectionDown -> {
                            if (onArrowDown != null) {
                                onArrowDown.invoke()
                                true
                            } else false
                        }
                        else -> false
                    }
                }
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused && fieldText != info.watText) {
                        lastError = commitRowEdit(editableDoc, info, fieldText, provider, chunk, isAppendedFunc)
                    }
                },
        )
        if (lastError != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "(; ${lastError} ;)",
                style = ROW_TEXT_STYLE.copy(color = errorColor),
            )
        }
    }
}

private val PREFIX_KINDS = setOf(
    RenderTokenKind.OFFSET_ANNOTATION,
    RenderTokenKind.RAW_BYTES,
    RenderTokenKind.WHITESPACE,
)

private fun List<RenderToken>.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    for (t in this@toAnnotatedString) {
        if (t.kind == RenderTokenKind.LINE_BREAK) continue
        withStyle(SpanStyle(color = colorFor(t.kind))) {
            append(t.text)
        }
    }
}

/**
 * Encodes the user's edited [text] for [info] and applies the resulting
 * `JournalOp`s to [doc]. Returns `null` on success, or a short error
 * message on failure.
 *
 * Routing for opcode rows in code chunks (when [provider] and [chunk] are
 * supplied):
 *
 * 1. Try the length-preserving / cascade path first via [EditableRowEncoder.encode].
 * 2. If that returns `Error(requiresCascade = true)` — i.e. the new bytes
 *    don't fit and we'd otherwise mid-section-cascade — try
 *    [EditableRowEncoder.encodeAppendFunctionEditFromText] (Phase E.1).
 * 3. If append-edit returns `Error("body too small for redirect stub")`,
 *    fall back to the original cascade attempt (re-issue with cascade
 *    context). This is rare and only happens for very small bodies.
 *
 * The shape of error messages doesn't change for the user. The only
 * observable difference is which JournalOps land — append+redirect for
 * length-changing edits with a sufficiently large body, in-place cascade
 * for everything else.
 */
private fun commitRowEdit(
    doc: EditableBinaryDocument,
    info: EditableRowInfo,
    text: String,
    provider: WasmBinaryProvider? = null,
    chunk: BinaryChunk.Element? = null,
    /**
     * `true` if the chunk was created by this session's append+redirect
     * ops. The append+redirect routing is suppressed for these chunks —
     * length-changing edits go straight to in-place cascade.
     */
    isAppendedFunc: Boolean = false,
): String? {
    if (text == info.watText) return null // nothing to do

    fun applyOps(result: RowEncodeResult): String? = when (result) {
        is RowEncodeResult.Success -> {
            doc.edit().apply {
                for (op in result.ops) {
                    when (op) {
                        is JournalOp.Replace -> replace(op.start, op.end, Base64.decode(op.data))
                        is JournalOp.Insert -> insert(op.offset, Base64.decode(op.data))
                        is JournalOp.Delete -> delete(op.start, op.end)
                    }
                }
                commit()
            }
            null
        }
        is RowEncodeResult.Error -> result.message
    }

    // -- D.4 row delete: blanking the field + Enter removes the row.
    // Only applies to OPCODE rows — locals declarations have a fixed
    // position in the body (the byte right after the funcSize prefix);
    // dropping them would corrupt the body shape, so we keep the
    // length-changing path for locals.
    if (text.isBlank() && info.kind == EditableRowInfo.Kind.OPCODE) {
        return applyOps(
            EditableRowEncoder.encodeDelete(
                rowStart = info.opStart,
                rowLength = info.opEncodedLength,
                cascade = info.cascade,
            ),
        )
    }

    // -- LOCALS row: stays in-place via encodeLocals. Phase E doesn't move
    // locals to append-edit because the locals declaration is INSIDE the
    // function body — there's no "append a copy" alternative.
    if (info.kind == EditableRowInfo.Kind.LOCALS) {
        return applyOps(
            EditableRowEncoder.encodeLocals(
                opStartOffset = info.opStart,
                opEncodedLength = info.opEncodedLength,
                text = text,
                cascade = info.cascade,
            ),
        )
    }

    // -- OPCODE row. First attempt: length-preserving (no cascade context).
    // If that succeeds, we're done — single Replace op, fastest path.
    val op = info.op ?: return "internal: opcode row missing op metadata"
    val noCascade = EditableRowEncoder.encode(
        op = op,
        opStartOffset = info.opStart,
        opEncodedLength = info.opEncodedLength,
        text = text,
        localCount = info.localCount,
        cascade = null,
    )
    if (noCascade is RowEncodeResult.Success) {
        return applyOps(noCascade)
    }
    val noCascadeErr = noCascade as RowEncodeResult.Error
    if (!noCascadeErr.requiresCascade) {
        // Parse / range / mnemonic error. No cascade will fix that.
        return noCascadeErr.message
    }

    // -- Length-changing edit. Phase E.1 path: append + redirect, when we
    // have provider + chunk metadata. Falls back to in-place cascade if
    // the redirect stub doesn't fit. Skipped entirely when this chunk is
    // itself a function we already appended in this session — chaining
    // redirects on each keystroke produces garbage with no value.
    if (!isAppendedFunc && provider != null && chunk != null && chunk.kind == WasmSectionKinds.CODE) {
        val appendResult = EditableRowEncoder.encodeAppendFunctionEditFromText(
            provider = provider,
            chunk = chunk,
            rowStart = info.opStart,
            oldRowLength = info.opEncodedLength,
            text = text,
            localCount = info.localCount,
        )
        when (appendResult) {
            is RowEncodeResult.Success -> return applyOps(appendResult)
            is RowEncodeResult.Error -> {
                val msg = appendResult.message
                // "body too small for redirect stub" → fall through to
                // the in-place cascade. Other errors (parse / structural)
                // are surfaced to the user.
                if (!msg.contains("body too small")) {
                    return msg
                }
            }
        }
    }

    // -- Fallback: in-place cascade (the path that worked through Phase C).
    return applyOps(
        EditableRowEncoder.encode(
            op = op,
            opStartOffset = info.opStart,
            opEncodedLength = info.opEncodedLength,
            text = text,
            localCount = info.localCount,
            cascade = info.cascade,
        ),
    )
}

/**
 * The empty TextField that appears below an opcode row when the user
 * pressed Enter (Phase D.3). The user types a new instruction; on Enter
 * (or focus-loss) we encode and commit a `JournalOp.Insert` plus cascade
 * ops. Esc closes the row without committing.
 *
 * Empty text + Enter / focus-loss closes silently.
 */
@Composable
private fun InsertedInstructionRow(
    insertOffset: Long,
    cascade: org.jetbrains.hexana.providers.format.CascadeContext?,
    localCount: Int?,
    editableDoc: EditableBinaryDocument,
    /**
     * Provider + chunk are needed to attempt append+redirect when an
     * inserted instruction grows the function. Without them
     * `commitInsertEdit` falls back to the in-place cascade path.
     */
    provider: WasmBinaryProvider? = null,
    chunk: BinaryChunk.Element? = null,
    onClose: () -> Unit,
    /** See [ChunkRow.isAppendedFunc]. Forwarded to `commitInsertEdit`. */
    isAppendedFunc: Boolean = false,
) {
    var fieldText by remember { mutableStateOf("") }
    var lastError by remember { mutableStateOf<String?>(null) }
    val color = HexanaTheme.textColor
    val errorColor = colorFor(RenderTokenKind.ERROR)
    val borderModifier = if (lastError != null) {
        Modifier.border(width = 1.dp, color = errorColor, shape = RoundedCornerShape(2.dp))
    } else {
        Modifier
    }

    fun tryCommit(): Boolean {
        if (fieldText.isBlank()) {
            onClose()
            return true
        }
        val err = commitInsertEdit(editableDoc, insertOffset, cascade, localCount, fieldText, provider, chunk, isAppendedFunc)
        lastError = err
        return err == null
    }

    // Auto-focus the empty field as soon as the row appears so the user
    // can start typing without clicking. The requester is only attached
    // when the row is present, so this `requestFocus()` always lands on a
    // ready target.
    val requester = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { requester.requestFocus() }
    }
    // Tracks whether the field has ever been focused. The empty-and-
    // unfocused close-on-blur logic below races the auto-focus
    // LaunchedEffect — without this guard the field gets closed on its
    // very first onFocusChanged callback (initial isFocused = false),
    // before the LaunchedEffect even runs, and the row vanishes.
    var hadFocus by remember { mutableStateOf(false) }
    val accentColor = colorFor(RenderTokenKind.KEYWORD)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // Subtle left border in the accent color so the empty insert
            // row is visually distinct from the regular rows above and
            // below. Without this it's an invisible 1-line gap between
            // the existing rows — easy to miss.
            .border(width = 0.dp, color = Color.Transparent)
            .padding(start = 0.dp),
    ) {
        // Visible "+" gutter marker so the user can see at a glance that
        // a new-instruction row is open. Same hex-text color used for
        // structural decoration in regular rows so it visually blends
        // into the gutter without being flashy.
        Text(
            text = " + ",
            style = ROW_TEXT_STYLE.copy(color = accentColor, fontWeight = FontWeight.Bold),
        )
        BasicTextField(
            value = fieldText,
            onValueChange = { fieldText = it; lastError = null },
            singleLine = true,
            cursorBrush = SolidColor(accentColor),
            textStyle = ROW_TEXT_STYLE.copy(color = accentColor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { tryCommit() }),
            modifier = Modifier
                .then(borderModifier)
                .focusRequester(requester)
                // Direct keyboard handling. KeyboardActions.onDone catches
                // the IME path; on Compose Desktop without a soft keyboard
                // Enter doesn't always reach it, so we handle Enter / Esc
                // explicitly here. Returning `true` consumes the event so
                // the BasicTextField doesn't also process it.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            onClose()
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            tryCommit()
                            true
                        }
                        else -> false
                    }
                }
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        hadFocus = true
                    } else if (hadFocus && fieldText.isBlank()) {
                        onClose()
                    }
                },
        )
        if (lastError != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "(; ${lastError} ;)",
                style = ROW_TEXT_STYLE.copy(color = errorColor),
            )
        }
    }
}

/**
 * Encodes the user's typed instruction for an inserted row at
 * [insertOffset] and applies the JournalOps. Returns null on success or a
 * short error message on failure.
 *
 * Routing matches the EDIT path:
 * 1. **Append + redirect** if [provider] + [chunk] are available and the
 *    function body has room for the redirect stub. The original function
 *    becomes a stub `(local.get …; call $newIdx; nop pad)` and a new
 *    function carrying the post-insert body is appended.
 * 2. **In-place cascade** otherwise — single Insert + funcSize/section
 *    cascade ops; the original function bytes mutate in place.
 */
private fun commitInsertEdit(
    doc: EditableBinaryDocument,
    insertOffset: Long,
    cascade: org.jetbrains.hexana.providers.format.CascadeContext?,
    localCount: Int?,
    text: String,
    provider: WasmBinaryProvider? = null,
    chunk: BinaryChunk.Element? = null,
    /**
     * `true` if the host chunk was created by this session's append+
     * redirect ops — suppress the append+redirect routing for inserts
     * into it.
     */
    isAppendedFunc: Boolean = false,
): String? {
    fun applyOps(ops: List<JournalOp>): String? {
        doc.edit().apply {
            for (op in ops) {
                when (op) {
                    is JournalOp.Replace -> replace(op.start, op.end, Base64.decode(op.data))
                    is JournalOp.Insert -> insert(op.offset, Base64.decode(op.data))
                    is JournalOp.Delete -> delete(op.start, op.end)
                }
            }
            commit()
        }
        return null
    }

    // Try append+redirect first when we have the structural metadata.
    // Reuses encodeAppendFunctionEdit with `oldRowLength = 0`, which makes
    // the encoder build `prefix ++ newRowBytes ++ suffix` — exactly an
    // insert at `insertOffset`. Skipped for chunks already created by this
    // session's append+redirect — chaining redirects on every keystroke
    // produces garbage with no value.
    if (!isAppendedFunc && provider != null && chunk != null && chunk.kind == WasmSectionKinds.CODE) {
        val appendResult = EditableRowEncoder.encodeAppendFunctionEditFromText(
            provider = provider,
            chunk = chunk,
            rowStart = insertOffset,
            oldRowLength = 0,
            text = text,
            localCount = localCount,
        )
        when (appendResult) {
            is RowEncodeResult.Success -> return applyOps(appendResult.ops)
            is RowEncodeResult.Error -> {
                val msg = appendResult.message
                // Body too small for the redirect stub → fall through to
                // in-place cascade. Other errors surface to the user.
                if (!msg.contains("body too small")) return msg
            }
        }
    }

    // Fallback: in-place cascade.
    val result = EditableRowEncoder.encodeInsert(
        insertOffset = insertOffset,
        text = text,
        localCount = localCount,
        cascade = cascade,
    )
    return when (result) {
        is RowEncodeResult.Success -> applyOps(result.ops)
        is RowEncodeResult.Error -> result.message
    }
}

@Composable
private fun StatusBar(chunkTree: WasmChunkTree, currentIndex: Int) {
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
        val sectionLabel = WasmSectionKinds.displayName(chunk.kind)
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

private fun TokenStream.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    for (t in tokens) {
        if (t.kind == RenderTokenKind.LINE_BREAK) {
            append('\n')
            continue
        }
        withStyle(SpanStyle(color = colorFor(t.kind))) {
            append(t.text)
        }
    }
}

private fun colorFor(kind: RenderTokenKind): Color = when (kind) {
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

private fun formatBytes(size: Long): String = when {
    size < 1024 -> "${size}B"
    size < 1024 * 1024 -> "${size / 1024}KB"
    else -> "${size / (1024 * 1024)}MB"
}

