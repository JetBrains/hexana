package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.IdeActions
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.HexViewState
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.analytics.binaryFormatOf
import org.jetbrains.hexana.document.*
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.journal.JournalLoadResult
import org.jetbrains.hexana.document.journal.JournalPersister
import org.jetbrains.hexana.dwarf.ui.dwarfPanels
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.nativebin.JVM_FILE_SYSTEM
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.shared.NativeDebugTabData
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.jetbrains.hexana.ui.panel.*
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle

@Composable
internal fun BinaryEditorView(
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    hexFocusRequester: FocusRequester,
    fileName: String = "",
    parentFileName: String? = null,
    onNavigateToParent: (() -> Unit)? = null,
    wasmFunctionNavTargetFlow: MutableStateFlow<WasmFunctionNavTarget?> = remember { MutableStateFlow(null) },
    extraPanels: List<Panel> = emptyList(),
) {
    // Phase F: every opened file gets one editable BinaryDocument shared by
    // the editor's lifecycle. Edits land in this doc, but ONLY the WAT
    // virtualized + Hex view track the post-edit state. Other panels (Top,
    // Functions, Imports, Exports, Garbage, Dominators, …) keep showing the
    // original bytes — they don't need to follow intermediate edits, and
    // re-parsing the in-progress mutation can crash analyses that walk
    // section content (e.g. `sizeAnalysis` deep-scans data records, which
    // explodes on bytes that the encoder hasn't yet finished cascading).
    // The user explicitly invokes "Finalize" to write the result to a new
    // file — that's the moment all derived analyses re-run from scratch.
    val editableDoc: EditableBinaryDocument = remember(binaryProvider) {
        // Wire a journal persister when we have a backing VirtualFile; the
        // sidecar gets written to `<source>.hexana.json` on every commit
        // and deleted on Finalize via clearPersistedJournal. Providers
        // without a file (synthetic / generic) fall back to NoOp — there's
        // nothing to attach a sidecar to. The doc starts empty here; the
        // sidecar load + journal replay happen in the LaunchedEffect below
        // so that VFS I/O (findChild → FSRecordsImpl.update) doesn't run
        // on the EDT inside `remember { ... }`.
        val sourceFile = (binaryProvider as? BinaryVirtualFileProvider)?.file
        val persister: JournalPersister = if (sourceFile != null) {
            IdeaJournalPersister(sourceFile)
        } else {
            JournalPersister.NoOp
        }
        editableBinaryDocumentOf(bytes = binaryProvider.bytes.duplicate(), persister = persister)
    }
    // Sidecar restore runs off-EDT. While `loadSidecarIfPresent` is in
    // flight the editor renders a centred spinner so the user never sees
    // the original bytes briefly before they're replaced by the restored
    // ones (a visible "jump" on file open). For files with no sidecar the
    // gate clears as soon as the IO dispatcher returns null — typically
    // within one frame.
    var sidecarRestored by remember(binaryProvider, editableDoc) { mutableStateOf(false) }
    LaunchedEffect(binaryProvider, editableDoc) {
        val sourceFile = (binaryProvider as? BinaryVirtualFileProvider)?.file
        if (sourceFile == null) {
            sidecarRestored = true
            return@LaunchedEffect
        }
        val originalBytes = binaryProvider.bytes.duplicate()
        val result = withContext(Dispatchers.IO) {
            loadSidecarIfPresent(sourceFile, originalBytes)
        }
        when (result) {
            is JournalLoadResult.Loaded -> {
                editableDoc.loadJournal(result.journal)
                org.jetbrains.hexana.HexanaLog.LOG.info(
                    "Restored ${result.journal.entries.size} journal entries from " +
                        "${sourceFile.name}.hexana.json",
                )
            }
            is JournalLoadResult.Empty -> { /* no-op: design contract — codec never returns Empty */ }
            is JournalLoadResult.Mismatch -> {
                org.jetbrains.hexana.HexanaLog.LOG.warn(
                    "Sidecar ${sourceFile.name}.hexana.json doesn't match the binary " +
                        "(reason=${result.reason}); ignoring and starting fresh",
                )
                notifyJournalMismatch(state.project, sourceFile, result.reason)
            }
            null -> { /* no sidecar present */ }
        }
        sidecarRestored = true
    }
    // Set to true when a HEX cell edit commits. From then on, raw byte
    // mutations bypass the WAT encoder so the binary's structural shape
    // (section sizes, function counts, DWARF offsets …) is no longer
    // trustworthy. The live provider gets demoted to GenericBinaryProvider
    // and the panel list collapses to just the Hex view + status bar —
    // matching what GenericBinaryProvider would surface for an unknown-
    // format file.
    var hexEditedSinceOpen by remember(binaryProvider) { mutableStateOf(false) }
    // The "live" provider for the WAT virtualized panel + Hex view. Starts
    // as the original provider; if the sidecar LaunchedEffect above replays
    // a journal, the resulting `loadJournal` call fires the DocumentListener
    // below, which rebuilds `liveProvider` from the post-replay bytes. The
    // gate on `sidecarRestored` ensures no panels render against the stale
    // original provider during the replay window.
    var liveProvider by remember(binaryProvider, editableDoc) {
        mutableStateOf<BinaryProvider>(binaryProvider)
    }
    DisposableEffect(editableDoc, binaryProvider) {
        val listener = DocumentListener {
            val newBytes = editableDoc.read(0L, editableDoc.length.toInt())
            // After a hex edit, force GenericBinaryProvider for the rest of
            // the session. WAT-encoder-mediated edits keep the typed
            // provider so structural panels stay consistent with the bytes.
            val rebuilt: BinaryProvider? = if (hexEditedSinceOpen) {
                GenericBinaryProvider(newBytes.toCommonByteBuffer())
            } else {
                try {
                    rebuildProviderFromBytes(binaryProvider, newBytes)
                } catch (t: Throwable) {
                    org.jetbrains.hexana.HexanaLog.LOG.warn(
                        "Parser rejected post-edit bytes; keeping previous live provider",
                        t,
                    )
                    null
                }
            }
            if (rebuilt != null) liveProvider = rebuilt
        }
        editableDoc.addListener(listener)
        onDispose { editableDoc.removeListener(listener) }
    }

    // State hoisted ABOVE the panel-rebuild boundary so commits don't
    // reset the user's scroll position or fold pattern. The panels array
    // re-`remember`s on every liveProvider change (rebuilding lambdas);
    // anything held inside those lambdas via positional `remember` resets.
    // Lifting it here means each new lambda captures the SAME LazyListState
    // and folded-section MutableState references, preserving state.
    val watVirtualizedListState = rememberLazyListState()
    val watVirtualizedFolded = remember { mutableStateOf<Set<ChunkId>>(emptySet()) }
    // Binary format derived once per opened provider — fed into the
    // `hex_tab_selected` analytics event so cross-tab funnels can filter
    // by container format (e.g. "ELF users → Disassembly tab").
    val binaryFormat = remember(binaryProvider) {
        binaryFormatOf((binaryProvider as? BinaryVirtualFileProvider)?.file, binaryProvider)
    }
    // The WASM function nav-target flow. Lifted here (above the panels
    // remember boundary) so the same flow instance persists across provider
    // rebuilds triggered by edit commits. The default is the flow passed in
    // from HexanaFileEditor; panels capture it by reference.
    val wasmNavFlow: MutableStateFlow<WasmFunctionNavTarget?> = wasmFunctionNavTargetFlow

    // IDE-keymap-routed Undo / Redo for the editable document. Attached at
    // the BinaryEditorView root so the shortcut fires from anywhere inside
    // — Hex view, WAT panel, status bar — without each surface needing its
    // own handler. `$Undo` / `$Redo` are the action IDs the platform's
    // keymap binds (Cmd+Z / Cmd+Shift+Z by default on macOS); we look up
    // the user's actual shortcut at runtime via `KeymapShortcuts` so any
    // remapping in Settings → Keymap takes effect.
    val docKeyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@ { event ->
        if (event.type != KeyEventType.KeyDown) return@handler false
        if (KeymapShortcuts.isModifierOnly(event)) return@handler false
        when {
            KeymapShortcuts.matches(event, IdeActions.ACTION_UNDO) -> {
                if (editableDoc.canUndo()) editableDoc.undo()
                true
            }
            KeymapShortcuts.matches(event, IdeActions.ACTION_REDO) -> {
                if (editableDoc.canRedo()) editableDoc.redo()
                true
            }
            else -> false
        }
    }
    // Lightweight record of the original bytes at modified positions, saved
    // before an overwrite so the user can undo it. Only the byte ranges that
    // actually differ from the original file are preserved — not the entire
    // file — so this stays small even for huge binaries.
    var overwriteShadowRanges by remember(binaryProvider) { mutableStateOf<OverwriteShadowRanges?>(null) }
    BoxWithConstraints(modifier = Modifier
        .fillMaxSize()
        .background(HexanaTheme.backgroundColor)
        .onPreviewKeyEvent(docKeyHandler)) {
        if (!sidecarRestored) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            return@BoxWithConstraints
        }
        val pathsNav = remember { PathsNavigationState() }
        // Panels are keyed to the ORIGINAL provider, not liveProvider — so
        // they don't rebuild on every commit. The WAT virtualized lambda
        // captures `liveProvider` (a Compose State delegate); its body
        // reads the current value on each composition and recomposes when
        // it changes. Non-edit panel lambdas use `this` (the original
        // provider) and stay frozen across edits.
        val panels = remember(binaryProvider, hexEditedSinceOpen, extraPanels) {
            val p: Array<Panel> = if (hexEditedSinceOpen) {
                // Demoted to generic — only the Hex view + status bar remain.
                emptyArray()
            } else {
                binaryProvider.resolvePanels(
                    state = state,
                    pathsNav = pathsNav,
                    editableDoc = editableDoc,
                    watVirtualizedListState = watVirtualizedListState,
                    watVirtualizedFolded = watVirtualizedFolded,
                    liveProviderProvider = { liveProvider },
                    wasmFunctionNavTargetFlow = wasmNavFlow,
                    archiveName = fileName,
                    extraPanels = extraPanels,
                )
            }
            mutableStateListOf(*p)
        }

        // If the panel list shrank because of a file-aware → plain provider
        // downgrade, clamp the tab index so we don't render past the end.
        LaunchedEffect(panels.size) {
            if (state.currentState.value.tabIndex >= panels.size && panels.isNotEmpty()) {
                state.setSelectedTab(panels.size - 1)
            }
        }

        val topTabIndex = remember(panels) { panels.indexOfFirst { it.description == "Top" }.takeIf { it >= 0 } }

        LaunchedEffect(panels) {
            state.pendingTabSelection.collect { desc ->
                val idx = panels.indexOfFirst { it.description == desc }
                if (idx >= 0) state.setSelectedTab(idx)
            }
        }

        // When an external caller (e.g. the JIT view's chicory-AOT badge) pushes
        // a WasmFunctionNavTarget, the inner `LaunchedEffect(navTarget)` inside
        // WasmPanelWatVirtualized only fires while that panel is currently
        // composed. Force the tab to "WAT (virtualized)" here so the panel
        // mounts and its nav effect runs.
        LaunchedEffect(wasmNavFlow, panels) {
            wasmNavFlow.collect { target ->
                if (target == null) return@collect
                val idx = panels.indexOfFirst { it.description == "WAT (virtualized)" }
                org.jetbrains.hexana.HexanaLog.LOG.warn(
                    "[chicory-debug] BinaryEditorView: wasmNavFlow target=$target panelsCount=${panels.size} watVirtualizedIdx=$idx currentTab=${state.currentState.value.tabIndex}",
                )
                if (idx >= 0) state.setSelectedTab(idx)
            }
        }

        VerticalSplitPanel(state, northPanel = {
            Column(modifier = Modifier.fillMaxSize()) {
                FileDescriptionBar(
                    fileName = fileName,
                    binaryProvider = liveProvider,
                    state = state,
                    onSizeClick = topTabIndex?.let { idx -> {
                        state.setSelectedTab(idx)
                        if (state.topFraction > 0.9f) {
                            state.setTopFraction(0.5f)
                        }
                    }},
                    parentFileName = parentFileName,
                    onNavigateToParent = onNavigateToParent,
                    editableDoc = editableDoc,
                    originalBinaryProvider = binaryProvider,
                    overwriteShadowRanges = overwriteShadowRanges,
                    onOverwriteShadowRangesChanged = { overwriteShadowRanges = it },
                    onReparseAfterOverwrite = {
                        hexEditedSinceOpen = false
                        val currentBytes = editableDoc.read(0L, editableDoc.length.toInt())
                        try {
                            val rebuilt = rebuildProviderFromBytes(binaryProvider, currentBytes)
                            if (rebuilt != null) liveProvider = rebuilt
                        } catch (_: Throwable) { /* keep current */ }
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    HexViewer(
                        binaryProvider = liveProvider,
                        state = state,
                        focusRequester = hexFocusRequester,
                        editableDoc = editableDoc,
                        onHexCommit = { hexEditedSinceOpen = true },
                        suppressEditHighlight = overwriteShadowRanges != null,
                    )
                }
                HexViewStatusBar(state = state, totalBytes = liveProvider.bytes.limit())
            }
        }, southPanel = {
            TabsPanel(panels, state.currentState.value,  onExpand = { expanded ->
                if (expanded) {
                    state.setTopFraction(0f)
                } else {
                    state.setTopFraction(0.5f)
                }
            }, onTabSelect = { index: Int ->
                state.setSelectedTab(index)
                panels.getOrNull(index)?.let { panel ->
                    HexanaService.getInstance().captureAnalytics(
                        HexanaEvent.HexTabSelected(
                            panel.description,
                            surface = HexanaEvent.Surface.IDE_PLUGIN,
                            binaryFormat = binaryFormat,
                        )
                    )
                }
            })
        })
    }
}

@Composable
private fun TabsPanel(
    panels: List<Panel>,
    state: HexViewState,
    onExpand: (Boolean) -> Unit,
    onTabSelect: (Int) -> Unit,
) {
    if (panels.isEmpty()) return

    // Single panel: render content directly without tab controls.
    if (panels.size == 1) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            panels[0].render()
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }
    var tabBarWidthPx by remember { mutableIntStateOf(0) }
    var tabContentWidthPx by remember(panels) { mutableIntStateOf(0) }
    var tabOffsetPx by remember(panels) { mutableIntStateOf(0) }
    val tabOverflowPx = (tabContentWidthPx - tabBarWidthPx).coerceAtLeast(0)
    val hasHiddenTabs = tabOverflowPx > TAB_OVERFLOW_THRESHOLD_PX
    val maxTabOffsetPx = if (hasHiddenTabs) tabOverflowPx else 0
    val canShowPreviousHiddenTab = hasHiddenTabs && tabOffsetPx > 0
    val canShowNextHiddenTab = hasHiddenTabs && tabOffsetPx < maxTabOffsetPx

    LaunchedEffect(maxTabOffsetPx) {
        tabOffsetPx = tabOffsetPx.coerceIn(0, maxTabOffsetPx)
    }

    val tabs by remember(state.tabIndex, panels) {
        derivedStateOf {
            panels.mapIndexed { index, panel ->
                TabData.Default(
                    selected = index == state.tabIndex,
                    content = { Text(text = panel.description) },
                    closable = false,
                    onClick = {
                        onTabSelect(index)
                    }
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HexanaTheme.backgroundColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Center the IconButton both vertically and horizontally within its own parent container
            Box(
                modifier = Modifier
                    .width(40.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    expanded = !expanded
                    onExpand(expanded)
                }) {
                    Icon(
                        if (expanded) AllIconsKeys.General.CollapseComponent else AllIconsKeys.General.ExpandComponent,
                        contentDescription = null,
                        iconClass = AllIcons::class.java
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { tabBarWidthPx = it.size.width }
                    .clipToBounds(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasHiddenTabs) {
                        IconButton(
                            enabled = canShowPreviousHiddenTab,
                            onClick = {
                                tabOffsetPx = (tabOffsetPx - tabBarWidthPx).coerceAtLeast(0)
                            },
                        ) {
                            Text("‹")
                        }
                    }

                    NaturalWidthTabStrip(
                        tabs = tabs,
                        offsetPx = tabOffsetPx.coerceIn(0, maxTabOffsetPx),
                        onContentWidthChanged = { widthPx ->
                            if (widthPx < TAB_STRIP_IMPLAUSIBLE_MEASURE_WIDTH) {
                                tabContentWidthPx = widthPx
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clipToBounds(),
                    )

                    if (hasHiddenTabs) {
                        IconButton(
                            enabled = canShowNextHiddenTab,
                            onClick = {
                                tabOffsetPx = (tabOffsetPx + tabBarWidthPx).coerceAtMost(maxTabOffsetPx)
                            },
                        ) {
                            Text("›")
                        }
                    }
                }
            }
        }

        Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal)

        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            // The panel list shrinks when liveProvider rebuilds from
            // file-aware → plain (Phase A.1a). The LaunchedEffect clamp
            // runs asynchronously, so on the FIRST recomposition after
            // shrinkage state.tabIndex can still point past the new end.
            // Defensive coerce here so we never throw IndexOutOfBounds —
            // the LaunchedEffect catches up on the next composition.
            val safeIndex = state.tabIndex.coerceIn(0, panels.size - 1)
            panels.get(safeIndex).render()
        }
    }
}

@Composable
private fun NaturalWidthTabStrip(
    tabs: List<TabData>,
    offsetPx: Int,
    onContentWidthChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            Box(modifier = Modifier.onSizeChanged { onContentWidthChanged(it.width) }) {
                TabStrip(
                    tabs = tabs,
                    style = JewelTheme.defaultTabStyle,
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(
            Constraints(
                minWidth = 0,
                maxWidth = TAB_STRIP_MEASURE_MAX_WIDTH,
                minHeight = 0,
                maxHeight = constraints.maxHeight.coerceAtMost(TAB_STRIP_MEASURE_MAX_HEIGHT),
            )
        )
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            placeable.width
        }
        val height = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val maxOffset = (placeable.width - width).coerceAtLeast(0)
        val offset = offsetPx.coerceIn(0, maxOffset)

        layout(width, height) {
            placeable.placeRelative(-offset, (height - placeable.height) / 2)
        }
    }
}

private const val TAB_STRIP_MEASURE_MAX_WIDTH = 65_535
private const val TAB_STRIP_IMPLAUSIBLE_MEASURE_WIDTH = TAB_STRIP_MEASURE_MAX_WIDTH - 1_024
private const val TAB_STRIP_MEASURE_MAX_HEIGHT = 4_096
private const val TAB_OVERFLOW_THRESHOLD_PX = 24


/**
 * Stores only the original bytes at positions that were modified, instead of
 * keeping a full copy of the original file. Each entry records the logical
 * offset in the edited document and the original bytes that occupied that
 * position before the edit. [originalLength] is the total size of the
 * original file so the full content can be reconstructed when needed.
 */
internal class OverwriteShadowRanges(
    val originalLength: Long,
    val entries: List<Entry>,
) {
    data class Entry(val offset: Long, val originalBytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Entry && offset == other.offset && originalBytes.contentEquals(other.originalBytes)
        override fun hashCode(): Int = 31 * offset.hashCode() + originalBytes.contentHashCode()
    }

    /**
     * Applies the stored original bytes on top of [current], producing the
     * original file content. Used as a safety net when the undo stack
     * doesn't fully restore the original state.
     */
    fun applyTo(current: ByteArray): ByteArray {
        // If the original was a different length, start from a correctly-sized
        // copy. For same-length (pure replace edits) this is a no-op copy.
        val result = if (current.size.toLong() == originalLength) {
            current.copyOf()
        } else {
            ByteArray(originalLength.toInt())
        }
        for (entry in entries) {
            entry.originalBytes.copyInto(result, entry.offset.toInt())
        }
        return result
    }

    companion object {
        /**
         * Captures the original bytes at positions identified by
         * [modifiedRanges]. Only the bytes that differ are stored.
         */
        fun capture(originalFileBytes: ByteArray, modifiedRanges: List<ModifiedRange>): OverwriteShadowRanges {
            val entries = modifiedRanges.mapNotNull { range ->
                // The modified range is in the edited document's coordinate
                // space. For pure replace edits (same length) the offset
                // maps 1:1 to the original file. For inserts/deletes the
                // mapping is more complex, but we store the original bytes
                // at the same logical offset so `applyTo` can patch them
                // back. Ranges that extend past the original file are
                // inserts — no original bytes to save.
                val end = minOf(range.offset + range.length, originalFileBytes.size.toLong())
                if (range.offset >= originalFileBytes.size) return@mapNotNull null
                val bytes = originalFileBytes.copyOfRange(range.offset.toInt(), end.toInt())
                Entry(range.offset, bytes)
            }
            return OverwriteShadowRanges(originalFileBytes.size.toLong(), entries)
        }
    }
}

/**
 * Builds a fresh [BinaryProvider] from [newBytes] when the editable document
 * commits a change. File-aware capabilities (the old WAT-text panel that
 * needs `createTextEditor`, DWARF source-location lookups) live on the
 * concrete [WasmBinaryVirtualFileProvider] subclass instantiated in
 * `HexanaFileEditor` and aren't reachable from a freshly-built provider —
 * panels that depend on them are dropped from the post-edit panel list.
 * Returns `null` if [original]'s type isn't supported for rebuild.
 */
internal fun rebuildProviderFromBytes(original: BinaryProvider, newBytes: ByteArray): BinaryProvider? {
    val buf = newBytes.toCommonByteBuffer()
    return when (original) {
        is WasmBinaryVirtualFileProvider, is WasmBinaryProvider -> WasmBinaryProvider(buf)
        is WasmComponentBinaryVirtualFileProvider, is WasmComponentBinaryProvider -> WasmComponentBinaryProvider(buf)
        is GenericBinaryVirtualFileProvider, is GenericBinaryProvider -> GenericBinaryProvider(buf)
        else -> null
    }
}

private fun NativeBinaryProvider.commonNativePanels(state: HexViewStateHolder): List<Panel> {
    return listOfNotNull(
        Panel("SBOM") { CycloneDxSbomPanel(this, state) }.takeIf { this.nativeImageInfo?.sbom != null },
        Panel("Disassembly") { NativeDisassemblyPanel(this, state) },
        Panel("Disassembly (virtualized)") { NativeDisassemblyPanelVirtualized(this, state) }
    )
}

/**
 * Resolves the panel list for an editor.
 *
 * `this` is the ORIGINAL, immutable [BinaryProvider]. All non-edit panels
 * (size, functions, imports, exports, dominators, …) bind to it directly —
 * they never re-derive from intermediate edit bytes, so their analyses
 * stay stable while the user is mid-edit.
 *
 * [liveProviderProvider] is a closure that returns the CURRENT live
 * provider (rebuilt from `editableDoc` bytes on every commit). It's read
 * inside the WAT virtualized panel's lambda so Compose recomposes that
 * panel — and only that panel — whenever the live provider changes.
 * `null` means "no live state, use original everywhere" (callers without
 * an editable doc).
 */
private fun BinaryProvider.resolvePanels(
    state: HexViewStateHolder,
    pathsNav: PathsNavigationState,
    editableDoc: EditableBinaryDocument? = null,
    watVirtualizedListState: LazyListState? = null,
    watVirtualizedFolded: MutableState<Set<ChunkId>>? = null,
    liveProviderProvider: (() -> BinaryProvider)? = null,
    wasmFunctionNavTargetFlow: MutableStateFlow<WasmFunctionNavTarget?>? = null,
    archiveName: String? = null,
    extraPanels: List<Panel> = emptyList(),
): Array<Panel> {
    val enricher = getTabEnricher()
    val providers = TableTabsProviderRegistry.providers
    // Precompute filesystem-dependent debug data for native providers
    // outside the rendering path so tableTabs() never touches the FS.
    val tableTabsContext = TableTabsContext(
        nativeDebugData = (this as? NativeBinaryProvider)
            ?.let { NativeDebugTabData.scan(it, JVM_FILE_SYSTEM, state.project.basePath) },
    )
    val tabPanels = providers.flatMap {
        enricher(it.tableTabs(this, tableTabsContext), state, pathsNav).map { it.toPanel(state) }
    }
    val providerPanels = when (this) {
        is GenericBinaryProvider -> emptyList()
        is ElfBinaryProvider -> commonNativePanels(state)
        is MachOBinaryProvider -> commonNativePanels(state)
        is FatMachOBinaryProvider ->  commonNativePanels(state)
        is PeBinaryProvider -> commonNativePanels(state)
        is CoffBinaryProvider -> commonNativePanels(state)
        // Must precede the generic `is NativeBinaryProvider` branch below: ArchiveBinaryProvider
        // IS-A NativeBinaryProvider, and Kotlin `when` is first-match — otherwise the archive
        // would fall through to the near-empty generic native branch (blank editor).
        is ArchiveBinaryProvider -> listOf(
            Panel("Members") { ArchiveMembersPanel(this, state, archiveName = archiveName) },
        )
        is NativeBinaryProvider -> emptyList()
        is WasmBinaryVirtualFileProvider -> {
            if (canProvideWasmModule()) {
                listOf(
                    Panel("Garbage") { WasmPanelGarbage(this, state) },
                    Panel("Dominators") { WasmPanelDominators(this, state, pathsNav) },
                    Panel("Wat") { WasmPanelWat(this, state) },
                    Panel("WAT (virtualized)") {
                        // Read the live provider INSIDE the lambda so Compose
                        // re-renders this panel (and only this panel) on each
                        // post-commit rebuild. Falls back to `this` (original)
                        // when no live provider is wired.
                        val live = (liveProviderProvider?.invoke() as? WasmBinaryProvider) ?: this
                        WasmPanelWatVirtualized(
                            provider = live,
                            state = state,
                            editableDoc = editableDoc,
                            listState = watVirtualizedListState,
                            foldedSectionsExternal = watVirtualizedFolded,
                            navTargetFlow = wasmFunctionNavTargetFlow
                                ?: remember { MutableStateFlow(null) },
                        )
                    },
                ) + dwarfPanels(this, state)
            } else {
                errorPanel(state)
            }
        }
        is WasmComponentBinaryVirtualFileProvider -> {
            if (canProvideWasmModule()) {
                listOf(
                    Panel("Wat") { WasmPanelWat(this, state) },
                    Panel("WAT (virtualized)") { WasmComponentPanelWatVirtualized(this, state) },
                )
            } else {
                errorPanel(state)
            }
        }
        is WasmBinaryProvider -> {
            if (canProvideWasmModule()) {
                listOf(
                    Panel("Garbage") { WasmPanelGarbage(this, state) },
                    Panel("Dominators") { WasmPanelDominators(this, state, pathsNav) },
                    Panel("WAT (virtualized)") {
                        val live = (liveProviderProvider?.invoke() as? WasmBinaryProvider) ?: this
                        WasmPanelWatVirtualized(
                            provider = live,
                            state = state,
                            editableDoc = editableDoc,
                            listState = watVirtualizedListState,
                            foldedSectionsExternal = watVirtualizedFolded,
                            navTargetFlow = wasmFunctionNavTargetFlow
                                ?: remember { MutableStateFlow(null) },
                        )
                    },
                ) + dwarfPanels(this, state)
            } else emptyList()
        }
        is WasmComponentBinaryProvider -> {
            if (canProvideWasmModule()) {
                listOf(
                    Panel("WAT (virtualized)") { WasmComponentPanelWatVirtualized(this, state) },
                )
            } else emptyList()
        }
        else -> emptyList()
    }
    val panels = (tabPanels + providerPanels + extraPanels).toMutableList()
    // Wire Garbage → Dominators cross-panel navigation
    val domIndex = panels.indexOfFirst { it.description == "Dominators" }
    val garbageIndex = panels.indexOfFirst { it.description == "Garbage" }
    if (garbageIndex >= 0 && domIndex >= 0 && this is WasmBinaryProvider) {
        val provider = this
        panels[garbageIndex] = Panel("Garbage") {
            WasmPanelGarbage(
                provider, state,
                onShowInDominators = { fIdx ->
                    pathsNav.targetFuncIndex = fIdx
                    state.setSelectedTab(domIndex)
                },
                onShowCallers = { fIdx ->
                    pathsNav.targetFuncIndex = fIdx
                    state.setSelectedTab(domIndex)
                },
            )
        }
    }
    return panels.toTypedArray()
}

private fun BinaryVirtualFileProvider.errorPanel(state: HexViewStateHolder): List<Panel> = listOf(
    Panel("Something is wrong :(") {
        Box {
            Row {
                Column {
                    Text("Hexana does not know how to parse this file  ")
                }
                Column {
                    Link("report an issue", onClick = {
                        BrowserUtil.browse("https://github.com/JetBrains/hexana/issues")
                    })
                }
            }
        }
    },
    Panel("Wat") { WasmPanelWat(this, state) },
)
