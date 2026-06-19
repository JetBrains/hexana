package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.hexana.ui.WasmFunctionNavTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
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
import org.jetbrains.hexana.navigation.SymbolNavigator
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
import org.jetbrains.hexana.providers.format.DataViewMode
import org.jetbrains.hexana.providers.format.FollowTarget
import org.jetbrains.hexana.providers.format.RenderedRow
import org.jetbrains.hexana.providers.format.RowEncodeResult
import org.jetbrains.hexana.providers.format.RowKind
import org.jetbrains.hexana.providers.format.WasmChunkTree
import org.jetbrains.hexana.providers.format.WasmHexanaWatRenderer
import org.jetbrains.hexana.providers.format.WasmParsedBinary
import org.jetbrains.hexana.providers.format.WasmSectionKinds
import org.jetbrains.hexana.providers.format.buildWasmChunkTree
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.Project
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.wasm.analysis.GraalJavaSymbol
import org.jetbrains.hexana.wasm.analysis.KotlinWasmSymbol
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

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
    /**
     * External navigation request. Non-null value drives the panel to scroll
     * to the specified function's body in the WAT virtualized view. After
     * consumption the flow is reset to null so equal re-clicks fire again.
     *
     * Seeded from [WASM_FUNCTION_NAV_TARGET_KEY] user-data on construction;
     * subsequent requests for an already-open editor are pushed via
     * [org.jetbrains.hexana.HexanaFileEditor.requestWasmFunctionNav].
     */
    navTargetFlow: MutableStateFlow<WasmFunctionNavTarget?> = remember { MutableStateFlow(null) },
) {
    // WAT model build (parse + DWARF symbol resolution + the ~130k-chunk
    // tree) runs OFF the EDT on Dispatchers.IO; a spinner shows on first
    // load until it lands. Building it synchronously inside `remember`
    // during composition (the old behaviour) stalled the EDT for the whole
    // build on large modules. Mirrors NativeDisassemblyPanelVirtualized's
    // load pattern. `provider.bytes` hands out an independent-cursor
    // duplicate, so the background read doesn't race the EDT's hex view
    // over the same buffer.
    //
    // The holder is intentionally NOT keyed on `provider`: on an edit-commit
    // provider rebuild we keep showing the previous (matched) panelState and
    // swap atomically once the new build lands — no null frame. That keeps
    // [WasmPanelWatVirtualizedContent] continuously mounted, so its no-key
    // `remember`s (fold/expand toggles, nav history, appended-func tracking)
    // survive commits exactly as they did when the build was synchronous.
    // `provider` travels inside [PanelState], so content never observes a
    // new provider paired with the old chunk tree during the rebuild window.
    var panelState by remember { mutableStateOf<PanelState?>(null) }
    androidx.compose.runtime.LaunchedEffect(provider) {
        panelState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            buildPanelState(provider)
        }
    }
    val ready = panelState
    if (ready == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HexanaTheme.backgroundColor),
        ) {
            org.jetbrains.jewel.ui.component.CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }
    WasmPanelWatVirtualizedContent(
        panelState = ready,
        project = state.project,
        editableDoc = editableDoc,
        listState = listState,
        foldedSectionsExternal = foldedSectionsExternal,
        navTargetFlow = navTargetFlow,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WasmPanelWatVirtualizedContent(
    panelState: PanelState,
    project: Project,
    editableDoc: EditableBinaryDocument?,
    listState: LazyListState?,
    foldedSectionsExternal: MutableState<Set<ChunkId>>?,
    navTargetFlow: MutableStateFlow<WasmFunctionNavTarget?>,
) {
    // Read provider from the bundle, not from a separate param: it must stay
    // matched to chunkTree/renderer/snapshot through edit-commit swaps.
    val provider = panelState.provider
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

    // Expanded WasmGC `(rec …)` type groups, keyed by the rec header chunk's
    // id. Empty = all collapsed (the default): a single rec can hold tens of
    // thousands of types, which composed up front would freeze the EDT in
    // Skia text layout. Member TYPE chunks carry their header's id as
    // `groupId` and are hidden by the LazyColumn loop unless listed here.
    // Held without a `provider` key so toggles survive edit-commit rebuilds.
    var expandedRecGroups by remember { mutableStateOf(emptySet<ChunkId>()) }

    // Per-chunk expand state for Data-section entries. Held without a
    // `provider` key so toggles survive edit-commit panel rebuilds (the
    // chunk id is stable across rebuilds via the symbol resolver / index
    // mapping). Empty set means "all data chunks collapsed", which is the
    // default — large Data sections (Skiko: tens of MB) would otherwise
    // compose millions of body rows up front.
    val expandedDataChunksState: MutableState<Set<ChunkId>> =
        remember { mutableStateOf(emptySet<ChunkId>()) }

    // Per-chunk view mode override for Data-section entries. Missing key →
    // default U8_HEX. Lives next to `expandedDataChunksState` and survives
    // edit-commit rebuilds for the same reason.
    val dataChunkViewModesState: MutableState<Map<ChunkId, DataViewMode>> =
        remember { mutableStateOf(emptyMap()) }

    // Per-chunk expand state for Import-section entries. Same lifecycle
    // and rationale as `expandedDataChunksState` — by default all imports
    // collapse to a single `(import "mod" "name" …)` line; expanding
    // surfaces module / symbol / type descriptor rows.
    val expandedImportChunksState: MutableState<Set<ChunkId>> =
        remember { mutableStateOf(emptySet<ChunkId>()) }

    // Per-chunk expand state for Export-section entries. Expanding shows
    // the symbol slice + descriptor slice as xxd rows; the descriptor's
    // first row carries a follow target so Cmd+B on it jumps to the
    // referenced function's declaration.
    val expandedExportChunksState: MutableState<Set<ChunkId>> =
        remember { mutableStateOf(emptySet<ChunkId>()) }

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

    // Navigation history slot. Held without a `provider` key so the user's
    // back / forward stack survives edit-commit panel rebuilds. The Compose
    // scope below is also panel-scoped; gotoFunc launches scroll animations
    // there.
    val navHistory = remember { NavHistoryState() }
    val navScope = rememberCoroutineScope()
    val navigator = remember(chunkTree, effectiveListState, focusMap) {
        WasmWatNavigator(
            chunkTree = chunkTree,
            provider = provider,
            listState = effectiveListState,
            foldedSectionsState = foldedSectionsState,
            focusMap = focusMap,
            scope = navScope,
            history = navHistory,
        )
    }

    var lastTotalCodeCount by remember { mutableStateOf(-1) }
    androidx.compose.runtime.LaunchedEffect(totalCodeChunks) {
        val prev = lastTotalCodeCount
        lastTotalCodeCount = totalCodeChunks
        if (prev >= 0 && totalCodeChunks > prev) {
            // The newly-appended function is the last code element. Find its
            // index-in-section so we can (a) record it as an appended chunk
            // and (b) ask the navigator to scroll-and-focus.
            var lastCodeIdx = -1
            for (i in 0 until chunkTree.totalChunks) {
                val c = chunkTree.chunkAt(i)
                if (c is BinaryChunk.Element && c.kind == WasmSectionKinds.CODE) {
                    lastCodeIdx = c.indexInSection
                }
            }
            if (lastCodeIdx >= 0) {
                // The set lives across panel rebuilds because the surrounding
                // `remember { … }` slot has no key — subsequent edits on this
                // chunk bypass append+redirect.
                appendedCodeIndices = appendedCodeIndices + lastCodeIdx
                // recordHistory=false: an append is not a user nav step, so
                // it shouldn't push onto the back stack.
                navigator.gotoFunc(provider.importFuncCount + lastCodeIdx, recordHistory = false)
            }
        }
    }

    // External "jump to function N" requests wired from HexanaFileEditor.
    // The flow is seeded from VirtualFile UserData on first open (one-shot)
    // and kept alive for already-open-editor pushes via requestWasmFunctionNav.
    // Resetting to null after each consumption lets equal re-clicks re-fire.
    val navTarget by navTargetFlow.collectAsState()
    androidx.compose.runtime.LaunchedEffect(navTarget) {
        val target = navTarget ?: return@LaunchedEffect
        navigator.gotoFunc(target.funcIdx)
        navTargetFlow.value = null
    }

    // Lowercase function name → global funcidx, built from the chunk tree's
    // already-resolved displayName field. Excludes imports (they have no
    // body chunk in the code section). Powers the goto-by-name popup.
    val funcNamesIndex: Map<String, Int> = remember(chunkTree, provider) {
        val out = HashMap<String, Int>(chunkTree.totalChunks)
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            if (c is BinaryChunk.Element && c.kind == WasmSectionKinds.CODE) {
                val name = c.displayName ?: continue
                out[name.lowercase()] = provider.importFuncCount + c.indexInSection
            }
        }
        out
    }
    var gotoPopupOpen by remember { mutableStateOf(false) }

    // Panel-level shortcuts: Back / Forward / GotoSymbol. Attached at the
    // outer Column so they fire regardless of which row currently owns
    // keyboard focus. The preview pass runs before the focused element's
    // own handler, so these win over any built-in BasicTextField caret
    // bindings for the same combo.
    val panelKeyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@ { event ->
        if (event.type != KeyEventType.KeyDown) return@handler false
        if (KeymapShortcuts.isModifierOnly(event)) return@handler false
        // Every non-modifier KeyDown reaching the panel — captures whether
        // Cmd+B even arrives here. Combined with the more selective `back/fwd
        // /goto` log below this answers "did the keystroke get to the
        // handler, and did the keymap recognise it?" in one greppable trace.
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] panel handler key=${event.key} keyCode=${event.key.keyCode} " +
                "isMeta=${event.isMetaPressed} isCtrl=${event.isCtrlPressed} " +
                "isShift=${event.isShiftPressed} isAlt=${event.isAltPressed}",
        )
        val back = KeymapShortcuts.matches(event, IdeActions.ACTION_GOTO_BACK)
        val fwd = KeymapShortcuts.matches(event, IdeActions.ACTION_GOTO_FORWARD)
        // No `IdeActions.ACTION_GOTO_SYMBOL` constant exists — the platform
        // registers the id as a plain string in PlatformActions.xml.
        val goto = KeymapShortcuts.matches(event, "GotoSymbol")
        if (back || fwd || goto) {
            org.jetbrains.hexana.HexanaLog.LOG.warn(
                "[wat-nav-debug] panel matched back=$back fwd=$fwd goto=$goto " +
                    "canBack=${navigator.canBack} canForward=${navigator.canForward}",
            )
        }
        when {
            back -> navigator.back()
            fwd -> navigator.forward()
            goto -> {
                gotoPopupOpen = true
                true
            }
            else -> false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HexanaTheme.backgroundColor)
            .onPreviewKeyEvent(panelKeyHandler),
    ) {
        SectionShortcutBar(chunkTree = chunkTree, navigator = navigator)
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
                            if (chunk.kind == WasmSectionKinds.TYPE_REC) {
                                // Collapsible header for a WasmGC (rec …) group.
                                // Its member TYPE chunks (groupId == this id) are
                                // gated below; default-collapsed so a 50k-type rec
                                // never composes up front.
                                if (!hidden) {
                                    val expanded = chunk.id in expandedRecGroups
                                    item(key = chunk.id) {
                                        RecGroupHeaderRow(
                                            chunk = chunk,
                                            expanded = expanded,
                                            onToggle = {
                                                expandedRecGroups = if (chunk.id in expandedRecGroups) {
                                                    expandedRecGroups - chunk.id
                                                } else {
                                                    expandedRecGroups + chunk.id
                                                }
                                            },
                                        )
                                    }
                                }
                            } else if (!hidden &&
                                (chunk.groupId == null || chunk.groupId in expandedRecGroups)
                            ) {
                                item(key = chunk.id) {
                                    ChunkRow(
                                        chunk = chunk,
                                        renderer = renderer,
                                        snapshot = snapshot,
                                        provider = provider,
                                        project = project,
                                        editableDoc = editableDoc,
                                        focusMap = focusMap,
                                        onCrossChunkUp = navUp,
                                        onCrossChunkDown = navDown,
                                        isAppendedFunc = chunk.kind == WasmSectionKinds.CODE &&
                                            chunk.indexInSection in appendedCodeIndices,
                                        expandedDataChunks = expandedDataChunksState,
                                        dataChunkViewModes = dataChunkViewModesState,
                                        expandedImportChunks = expandedImportChunksState,
                                        expandedExportChunks = expandedExportChunksState,
                                        onFollow = { tgt ->
                                            org.jetbrains.hexana.HexanaLog.LOG.warn(
                                                "[wat-nav-debug] panel onFollow target=$tgt",
                                            )
                                            val ok = navigator.gotoFunc(tgt.funcIdx, tgt.section)
                                            org.jetbrains.hexana.HexanaLog.LOG.warn(
                                                "[wat-nav-debug] panel onFollow navigator.gotoFunc returned $ok",
                                            )
                                        },
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
    if (gotoPopupOpen) {
        WasmGotoFuncPopup(
            funcNamesIndex = funcNamesIndex,
            onResolved = { funcIdx ->
                gotoPopupOpen = false
                // Land on the Function-section declaration row so the user
                // gets the same starting view as Cmd+B from a call site —
                // a second Cmd+B (or click) continues to the Code body.
                navigator.gotoFunc(funcIdx, WasmSectionKinds.FUNCTION)
            },
            onDismiss = { gotoPopupOpen = false },
        )
    }
}

private data class PanelState(
    val provider: WasmBinaryProvider,
    val chunkTree: WasmChunkTree,
    val renderer: WasmHexanaWatRenderer,
    val snapshot: BinaryDocumentSnapshot,
)

private fun buildPanelState(provider: WasmBinaryProvider): PanelState {
    // Phase 1 stub: wrap provider bytes in a BinaryDocument so the chunk tree
    // builder has a valid BinaryDocumentSnapshot to attach. `provider.bytes`
    // already returns an independent-cursor duplicate, so no copy is required.
    val snapshot = binaryDocumentOf(provider.bytes).snapshot()
    val parsed = WasmParsedBinary.fromProvider(provider, snapshot)
    return PanelState(
        provider = provider,
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

/**
 * Collapsible header for a WasmGC `(rec …)` type group. Click toggles
 * [expanded]; the member `(type $N …)` chunks (which carry this chunk's id as
 * their `groupId`) are shown/hidden by the LazyColumn loop. `chunk.displayName`
 * carries the member count (set by `appendTypeChunks`).
 */
@Composable
private fun RecGroupHeaderRow(
    chunk: BinaryChunk.Element,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val arrow = if (expanded) "▼" else "▶"
    val count = chunk.displayName ?: "?"
    Text(
        text = "$arrow (rec  (; $count types ;)",
        style = ROW_TEXT_STYLE.copy(color = colorFor(RenderTokenKind.KEYWORD)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 1.dp),
    )
}

internal val ROW_TEXT_STYLE = TextStyle(
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
    /**
     * Project for the GraalVM Web Image "Java" badge's navigate-to-bytecode
     * action. Null in non-IDE / preview callers, which simply omit the badge.
     */
    project: Project? = null,
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
    /**
     * Per-Data-chunk expand state. When `chunk.id` is in the set, body
     * rows ([RowKind.DataBody]) are rendered; otherwise only the header
     * and expander rows are shown. Owned by the panel so toggles survive
     * edit-commit rebuilds.
     */
    expandedDataChunks: MutableState<Set<ChunkId>>? = null,
    /**
     * Per-Data-chunk view mode override. Missing key means default
     * [DataViewMode.U8_HEX]. Drives [WasmHexanaWatRenderer.renderRows]'s
     * body layout and the inline mode-bar's highlighted button.
     */
    dataChunkViewModes: MutableState<Map<ChunkId, DataViewMode>>? = null,
    /**
     * Per-Import-chunk expand state. When `chunk.id` is in the set, the
     * module / symbol / type descriptor rows are rendered; otherwise
     * only the single-line header is shown.
     */
    expandedImportChunks: MutableState<Set<ChunkId>>? = null,
    /**
     * Per-Export-chunk expand state. When `chunk.id` is in the set, the
     * symbol / descriptor rows are rendered; otherwise only the
     * single-line header is shown.
     */
    expandedExportChunks: MutableState<Set<ChunkId>>? = null,
    /**
     * Invoked when the user presses GotoDeclaration on an editable `call
     * N` row, GotoDeclaration on a Function-section row, or clicks a
     * Function-section row. The lambda receives the target funcidx + the
     * section to land in (Function for "go to declaration" from a call
     * site, Code for "go to body" from a Function row). Null when no
     * navigator is available.
     */
    onFollow: ((FollowTarget) -> Unit)? = null,
) {
    // Re-render whenever the renderer or snapshot changes (i.e. after a
    // commit replaces `liveProvider` and rebuilds `panelState`). Keying on
    // `chunk.id` alone is wrong: LazyColumn preserves the item slot across
    // panel rebuilds because `chunk.id` is stable, so a `remember(chunk.id)`
    // returns the PRE-edit `EditableRowInfo` objects (with stale opStart
    // anchors). Subsequent length-changing edits then feed those stale
    // offsets to the encoder, which corrupts the section sizes and crashes
    // the post-edit re-parse with `Unknown data record type: …`.
    val viewMode = dataChunkViewModes?.value?.get(chunk.id) ?: DataViewMode.U8_HEX
    val rows = remember(chunk.id, renderer, snapshot, viewMode) {
        renderer.renderRows(snapshot, chunk, viewMode)
    }
    // GraalVM Web Image: resolve this code chunk's function to a Java symbol
    // once. Null for non-Web-Image modules, stripped/minified names, synthetic
    // (lambda) symbols, or non-code chunks — in all those cases no badge shows.
    // Code-chunk indexInSection is defined-local; the name map is global-keyed.
    val graalSymbol: GraalJavaSymbol? = if (project != null && chunk.kind == WasmSectionKinds.CODE) {
        remember(chunk.id, provider) {
            provider.graalSymbolFor(provider.importFuncCount + chunk.indexInSection)
                ?.takeIf { !it.isSynthetic }
        }
    } else {
        null
    }
    // Kotlin/Wasm: resolve this code chunk's function to a Kotlin symbol once. Null for
    // non-Kotlin/Wasm modules, stripped names, or non-code chunks — mutually exclusive with
    // [graalSymbol] since a module has a single producer. Same global-index mapping as above.
    val kotlinSymbol: KotlinWasmSymbol? = if (project != null && graalSymbol == null && chunk.kind == WasmSectionKinds.CODE) {
        remember(chunk.id, provider) {
            provider.kotlinSymbolFor(provider.importFuncCount + chunk.indexInSection)
        }
    } else {
        null
    }
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
    val isDataExpanded = expandedDataChunks != null && chunk.id in expandedDataChunks.value
    val isImportExpanded = expandedImportChunks != null && chunk.id in expandedImportChunks.value
    val isExportExpanded = expandedExportChunks != null && chunk.id in expandedExportChunks.value
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            // Skip Data body and descriptor rows when the chunk is
            // collapsed. The header and the expander itself stay visible
            // so the user always has a click target to open the body.
            if ((row.kind is RowKind.DataBody || row.kind is RowKind.DataDescriptor) && !isDataExpanded) {
                return@forEachIndexed
            }
            // Same gate for Import descriptor rows.
            if (row.kind is RowKind.ImportDescriptor && !isImportExpanded) {
                return@forEachIndexed
            }
            if (row.kind is RowKind.ExportDescriptor && !isExportExpanded) {
                return@forEachIndexed
            }
            // Import / Export expander: clickable `▶ show details` / `▼
            // hide details`. Sits below the single-line `(import ...)`
            // / `(export ...)` header.
            if (row.kind is RowKind.ImportExpander && expandedImportChunks != null) {
                Text(
                    text = if (isImportExpanded) "▼ hide details" else "▶ show details",
                    style = ROW_TEXT_STYLE.copy(color = colorFor(RenderTokenKind.KEYWORD)),
                    modifier = Modifier
                        .padding(start = 90.dp)
                        .clickable {
                            expandedImportChunks.value = if (isImportExpanded) {
                                expandedImportChunks.value - chunk.id
                            } else {
                                expandedImportChunks.value + chunk.id
                            }
                        },
                )
                return@forEachIndexed
            }
            if (row.kind is RowKind.ExportExpander && expandedExportChunks != null) {
                Text(
                    text = if (isExportExpanded) "▼ hide details" else "▶ show details",
                    style = ROW_TEXT_STYLE.copy(color = colorFor(RenderTokenKind.KEYWORD)),
                    modifier = Modifier
                        .padding(start = 90.dp)
                        .clickable {
                            expandedExportChunks.value = if (isExportExpanded) {
                                expandedExportChunks.value - chunk.id
                            } else {
                                expandedExportChunks.value + chunk.id
                            }
                        },
                )
                return@forEachIndexed
            }
            if (row.kind is RowKind.DataExpander && expandedDataChunks != null) {
                val byteCount = (row.kind as RowKind.DataExpander).bodyByteCount
                val label = if (isDataExpanded) {
                    "▼ hide $byteCount bytes"
                } else {
                    "▶ show $byteCount bytes"
                }
                Text(
                    text = label,
                    style = ROW_TEXT_STYLE.copy(color = colorFor(RenderTokenKind.KEYWORD)),
                    modifier = Modifier
                        // Rough alignment with the offset gutter column; perfect
                        // pixel match would need a per-frame font-metrics read.
                        .padding(start = 90.dp)
                        .clickable {
                            expandedDataChunks.value = if (isDataExpanded) {
                                expandedDataChunks.value - chunk.id
                            } else {
                                expandedDataChunks.value + chunk.id
                            }
                        },
                )
                // Inline mode bar — only when expanded, only if state slot
                // is provided. Sits between the expander row and the first
                // body row so a mode switch visibly updates the bytes below.
                if (isDataExpanded && dataChunkViewModes != null) {
                    DataViewModeBar(
                        current = viewMode,
                        onSelect = { mode ->
                            dataChunkViewModes.value = dataChunkViewModes.value + (chunk.id to mode)
                        },
                    )
                }
                return@forEachIndexed
            }
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
            val rowView = @Composable {
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
                    onFollow = onFollow,
                )
            }
            // The "Java" badge sits inline on the function's header row (the
            // first rendered row of a code chunk) for Web Image modules.
            if (graalSymbol != null && project != null && rowIndex == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    rowView()
                    GraalJavaBadge(symbol = graalSymbol, project = project)
                }
            } else if (kotlinSymbol != null && project != null && rowIndex == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    rowView()
                    KotlinWasmBadge(symbol = kotlinSymbol, project = project)
                }
            } else {
                rowView()
            }
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
 * Small clickable "Java" pill shown inline on a GraalVM Web Image function's
 * header row. Click navigates to the resolved Java method's bytecode via the
 * [SymbolNavigator] extension point (no-op when the Java plugin is absent). The tooltip
 * shows the demangled Java signature.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GraalJavaBadge(symbol: GraalJavaSymbol, project: Project) {
    val badgeBg = if (JewelTheme.isDark) Color(0xFF394A5E) else Color(0xFFDCE7F7)
    val badgeFg = if (JewelTheme.isDark) Color(0xFFB7C9E2) else Color(0xFF2A4D80)
    Tooltip(tooltip = { Text("Go to Java bytecode: ${symbol.display()}") }) {
        Text(
            text = "Java",
            style = ROW_TEXT_STYLE.copy(color = badgeFg, fontSize = 10.sp),
            modifier = Modifier
                .padding(start = 8.dp)
                .background(badgeBg, RoundedCornerShape(4.dp))
                .clickable { SymbolNavigator.getInstance()?.navigateToGraalSymbol(project, symbol) }
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * Small clickable "Kotlin/Wasm" pill shown inline on a Kotlin/Wasm function's header row. Click
 * navigates to the resolved Kotlin source declaration via the [SymbolNavigator] EP; the tooltip
 * shows the structured Kotlin symbol. Uses the Kotlin brand purple to distinguish it from the
 * Web Image "Java" badge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KotlinWasmBadge(symbol: KotlinWasmSymbol, project: Project) {
    val badgeBg = if (JewelTheme.isDark) Color(0xFF433A5E) else Color(0xFFE7DCF7)
    val badgeFg = if (JewelTheme.isDark) Color(0xFFC6B7E2) else Color(0xFF5A2A80)
    Tooltip(tooltip = { Text("Go to Kotlin source: ${symbol.display()}") }) {
        Text(
            text = "Kotlin/Wasm",
            style = ROW_TEXT_STYLE.copy(color = badgeFg, fontSize = 10.sp),
            modifier = Modifier
                .padding(start = 8.dp)
                .background(badgeBg, RoundedCornerShape(4.dp))
                .clickable { SymbolNavigator.getInstance()?.navigateToKotlinSymbol(project, symbol) }
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
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
    /** See [ChunkRow.onFollow]. */
    onFollow: ((FollowTarget) -> Unit)? = null,
) {
    val info = row.editable
    val color = HexanaTheme.textColor
    if (info == null || editableDoc == null) {
        val target = row.followTarget
        if (target != null && onFollow != null) {
            // Non-editable but navigable row (Function-section entry).
            // Click → follow; Cmd+B → follow. We attach a FocusRequester so
            // the key event has somewhere to land; clicking the row also
            // grabs focus via the click handler.
            val rowFocus = remember(chunkId, rowIndex) { FocusRequester() }
            Text(
                text = row.tokens.toAnnotatedString(),
                style = ROW_TEXT_STYLE.copy(color = color),
                modifier = Modifier
                    .focusRequester(rowFocus)
                    .focusable()
                    .clickable {
                        org.jetbrains.hexana.HexanaLog.LOG.warn(
                            "[wat-nav-debug] func-row click target=$target chunkId=$chunkId rowIndex=$rowIndex",
                        )
                        // Don't requestFocus() on the source row before
                        // navigating — once `scrollToItem` jumps the
                        // viewport, Compose's LazyColumn auto-scrolls a
                        // focused-but-offscreen child back into view (a
                        // smooth scroll), which makes the click feel like
                        // a slow drift instead of an instant jump. Focus
                        // stays wherever it was; the navigation does its
                        // own scrolling.
                        onFollow.invoke(target)
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (KeymapShortcuts.isModifierOnly(event)) return@onPreviewKeyEvent false
                        val isGoto = KeymapShortcuts.matches(event, IdeActions.ACTION_GOTO_DECLARATION)
                        org.jetbrains.hexana.HexanaLog.LOG.warn(
                            "[wat-nav-debug] func-row handler chunkId=$chunkId rowIndex=$rowIndex " +
                                "key=${event.key} isGoto=$isGoto target=$target",
                        )
                        if (isGoto) {
                            onFollow.invoke(target)
                            true
                        } else false
                    },
            )
            return
        }
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
                    if (KeymapShortcuts.isModifierOnly(event)) return@onPreviewKeyEvent false
                    // Every non-modifier KeyDown reaching this row's text
                    // field — useful to confirm whether the row even sees the
                    // key (focus issues) before checking matcher logs.
                    org.jetbrains.hexana.HexanaLog.LOG.warn(
                        "[wat-nav-debug] row handler chunkId=$chunkId rowIndex=$rowIndex " +
                            "key=${event.key} keyCode=${event.key.keyCode} " +
                            "isMeta=${event.isMetaPressed} isCtrl=${event.isCtrlPressed} " +
                            "isShift=${event.isShiftPressed} isAlt=${event.isAltPressed} " +
                            "info.op=${info.op?.let { it::class.simpleName }} fieldText='$fieldText'",
                    )
                    // GotoDeclaration on a `call N` row → follow to the
                    // Function-section row for N (the declaration; the
                    // user's next Cmd+B from there continues to the body).
                    // Checked before the literal-key dispatch so a keymap
                    // that bound F4 / Cmd+B / etc. always wins over any
                    // built-in BasicTextField caret behaviour for that combo.
                    val callOp = info.op as? OpCodes.Flow.Call
                    val isGoto = KeymapShortcuts.matches(event, IdeActions.ACTION_GOTO_DECLARATION)
                    if (isGoto) {
                        org.jetbrains.hexana.HexanaLog.LOG.warn(
                            "[wat-nav-debug] row key=${event.key} GotoDeclaration matched " +
                                "onFollow=${onFollow != null} " +
                                "info.op=${info.op?.let { it::class.simpleName }} " +
                                "callOp=${callOp != null} fieldText='$fieldText'",
                        )
                    }
                    if (onFollow != null && callOp != null && isGoto) {
                        val funcIdx = parseCallTarget(fieldText, callOp)
                        org.jetbrains.hexana.HexanaLog.LOG.warn(
                            "[wat-nav-debug] follow-call parse fieldText='$fieldText' " +
                                "originalValue=${callOp.value} parsed=$funcIdx",
                        )
                        if (funcIdx != null) {
                            onFollow.invoke(FollowTarget(funcIdx = funcIdx, section = WasmSectionKinds.FUNCTION))
                            return@onPreviewKeyEvent true
                        }
                    }
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

/**
 * Pull the funcidx immediate out of a `call N` row's text. Falls back to the
 * original parsed opcode's value when the field text isn't a recognisable
 * `call <u32>` form (mid-edit, or the user changed the mnemonic but the
 * follow-call shortcut was still fired on the original `Call`).
 *
 * Accepts decimal and `0x`-prefixed hex; anything else returns the original
 * value so the user always lands on *something* sensible rather than getting
 * a silent no-op.
 */
internal fun parseCallTarget(fieldText: String, originalOp: OpCodes.Flow.Call): Int? {
    val tokens = fieldText.trim().split(Regex("\\s+"))
    if (tokens.size >= 2 && tokens[0] == "call") {
        val raw = tokens[1]
        val parsed = when {
            raw.startsWith("0x") || raw.startsWith("0X") -> raw.substring(2).toLongOrNull(radix = 16)
            else -> raw.toLongOrNull()
        }
        if (parsed != null && parsed in 0..Int.MAX_VALUE.toLong()) return parsed.toInt()
    }
    return originalOp.value.toLong().takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
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

