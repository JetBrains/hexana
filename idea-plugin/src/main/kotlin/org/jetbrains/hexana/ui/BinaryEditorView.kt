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
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.ArchiveMemberTabTitleProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.nativebin.JVM_FILE_SYSTEM
import org.jetbrains.hexana.providers.ClassFileBinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.shared.GroupedTableTabDescriptor
import org.jetbrains.hexana.shared.NativeDebugTabData
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.shared.TabPriority
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.jetbrains.hexana.ui.panel.*
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.zip.ZipEntry
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
    classFileNavTargetFlow: MutableStateFlow<ClassFileNavTarget?> = remember { MutableStateFlow(null) },
    onOpenZipEntry: ((ZipEntry) -> Unit)? = null,
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
        val panels = remember(binaryProvider, hexEditedSinceOpen) {
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
                    classFileNavTargetFlow = classFileNavTargetFlow,
                    fileName = fileName,
                    onOpenZipEntry = onOpenZipEntry,
                )
            }
            val scriptSettings = ScriptTabSettings.getInstance(state.project)
            val scriptPanels = scriptSettings.getScriptPaths(fileName).map { scriptPath ->
                createScriptPanel(scriptPath, binaryProvider)
            }
            mutableStateListOf(*(p + scriptPanels))
        }

        // Track which panels are script tabs so we know which are closable.
        val scriptTabIndices = remember(panels.size) {
            panels.indices.filter { panels[it] is ScriptPanel }.toSet()
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
            TabsPanel(panels, state.currentState.value, scriptTabIndices = scriptTabIndices, onExpand = { expanded ->
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
            }, onAddScriptTab = {
                val existingScriptPaths = panels.filterIsInstance<ScriptPanel>().map { it.scriptPath }.toSet()
                val dialog = AddScriptTabDialog(state.project, existingScriptPaths)
                if (dialog.showAndGet()) {
                    val scriptPath = dialog.selectedFile?.path ?: return@TabsPanel
                    val scriptSettings = ScriptTabSettings.getInstance(state.project)
                    scriptSettings.addScriptPath(fileName, scriptPath)
                    val panel = createScriptPanel(scriptPath, binaryProvider)
                    panels.add(panel)
                    state.setSelectedTab(panels.size - 1)
                }
            }, onCloseScriptTab = { index ->
                val panel = panels.getOrNull(index) as? ScriptPanel ?: return@TabsPanel
                val scriptSettings = ScriptTabSettings.getInstance(state.project)
                scriptSettings.removeScriptPath(fileName, panel.scriptPath)
                panels.removeAt(index)
                if (state.currentState.value.tabIndex >= panels.size && panels.isNotEmpty()) {
                    state.setSelectedTab(panels.size - 1)
                }
            })
        })
    }
}

@Composable
private fun TabsPanel(
    panels: List<Panel>,
    state: HexViewState,
    scriptTabIndices: Set<Int> = emptySet(),
    onExpand: (Boolean) -> Unit,
    onTabSelect: (Int) -> Unit,
    onAddScriptTab: () -> Unit = {},
    onCloseScriptTab: (Int) -> Unit = {},
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

    // When the last tab is selected (e.g. after adding a script tab),
    // scroll the tab strip to the end so the new tab is visible.
    // We also react to maxTabOffsetPx changes because the tab content width
    // is measured asynchronously — the first LaunchedEffect may fire before
    // the new tab has been laid out, so maxTabOffsetPx is still stale.
    LaunchedEffect(state.tabIndex, panels.size, maxTabOffsetPx) {
        if (state.tabIndex == panels.size - 1 && maxTabOffsetPx > 0) {
            tabOffsetPx = maxTabOffsetPx
        }
    }

    val tabs by remember(state.tabIndex, panels, scriptTabIndices) {
        derivedStateOf {
            panels.mapIndexed { index, panel ->
                TabData.Default(
                    selected = index == state.tabIndex,
                    content = { Text(text = panel.description) },
                    closable = index in scriptTabIndices,
                    onClose = if (index in scriptTabIndices) {
                        { onCloseScriptTab(index) }
                    } else {
                        {}
                    },
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
            IconButton(onClick = onAddScriptTab) {
                Icon(
                    AllIconsKeys.General.Add,
                    contentDescription = "Add script tab",
                    iconClass = AllIcons::class.java,
                )
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
                Row {
                    TabStrip(
                        tabs = tabs,
                        style = JewelTheme.defaultTabStyle,
                    )
                    // Invisible spacer so the last real tab title is never clipped
                    // behind the scroll arrows or the "+" button. Using a plain
                    // Spacer instead of a fake TabData avoids unwanted hover state.
                    Spacer(modifier = Modifier.width(35.dp))
                }
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
    classFileNavTargetFlow: MutableStateFlow<ClassFileNavTarget?>? = null,
    fileName: String? = null,
    onOpenZipEntry: ((ZipEntry) -> Unit)? = null,
): Array<Panel> {
    val enricher = getTabEnricher()
    val providers = TableTabsProviderRegistry.providers
    // Precompute filesystem-dependent debug data for native providers
    // outside the rendering path so tableTabs() never touches the FS.
    val tableTabsContext = TableTabsContext(
        nativeDebugData = (this as? NativeBinaryProvider)
            ?.let { NativeDebugTabData.scan(it, JVM_FILE_SYSTEM, state.project.basePath) },
    )
    // Collect ordinary and grouped tab descriptors per provider, preserving
    // provider order: for each provider ordinary tabs come first, then grouped.
    data class ProviderTabs(
        val ordinary: List<TableTabDescriptor>,
        val grouped: List<GroupedTableTabDescriptor>,
        val providerIndex: Int,
    )
    val perProvider = providers.mapIndexed { index, p ->
        ProviderTabs(
            ordinary = p.tableTabs(this, tableTabsContext),
            grouped = p.groupedTabs(this, tableTabsContext),
            providerIndex = index,
        )
    }
    val providerPanels = when (this) {
        is ClassFileBinaryProvider -> {
            val parsed = this.parsedClassFile
            if (parsed != null) {
                classFilePanels(parsed, classFileNavTargetFlow ?: MutableStateFlow(null))
            } else {
                HexanaLog.LOG.warn("Failed to parse .class file ${fileName ?: "unknown"}")
                listOf(Panel("Parse Error") {
                    Text(
                        "Failed to parse ${fileName ?: "the file"} as a JVM class file \u2014 see idea.log for details."
                    )
                })
            }
        }
        is DexBinaryProvider -> emptyList()
        is GenericBinaryProvider -> emptyList()
        // ArchiveBinaryProvider IS-A NativeBinaryProvider — must precede the generic native
        // branch so archives don't fall through to the near-empty native view. The "Members"
        // table tab is produced by ArchiveTableTabsProvider and enriched below.
        is ArchiveBinaryProvider -> emptyList()
        is NativeBinaryProvider -> commonNativePanels(state)
        is WasmBinaryVirtualFileProvider -> {
            if (canProvideWasmModule()) {
                listOf(
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
    // Build tab panels in provider order: ordinary tabs then grouped tabs per provider.
    // Grouped tabs may need enrichment (e.g. Garbage → Dominators cross-navigation),
    // which depends on providerPanels already being present, so we assemble the
    // full panel list first with providerPanels, then interleave provider tabs.
    val allGroupedDescriptors = perProvider.flatMap { it.grouped }
    val hasDominators = providerPanels.any { it.description == "Dominators" }
    val enrichedGrouped: Map<GroupedTableTabDescriptor, Panel?> =
        if (hasDominators && this is WasmBinaryProvider) {
            val garbageEntries = this.garbageAnalysis?.entries ?: emptyList()
            allGroupedDescriptors.associateWith { grouped ->
                if (grouped.name == "Garbage" && garbageEntries.isNotEmpty()) {
                    enrichGarbageGroupedTab(
                        grouped, garbageEntries,
                        onShowInDominators = { fIdx ->
                            pathsNav.targetFuncIndex = fIdx
                            state.requestSelectTabByDescription("Dominators")
                        },
                        onShowCallers = { fIdx ->
                            pathsNav.targetFuncIndex = fIdx
                            state.requestSelectTabByDescription("Dominators")
                        },
                    ).toPanel(state)
                } else {
                    grouped.toPanel(state)
                }
            }
        } else {
            allGroupedDescriptors.associateWith { it.toPanel(state) }
        }

    // Collect all tab entries with their priority and provider index for sorting.
    data class TabEntry(val panel: Panel, val priority: TabPriority, val providerIndex: Int)
    val tabEntries = mutableListOf<TabEntry>()
    for (pt in perProvider) {
        val enriched = enricher(pt.ordinary, state, pathsNav)
        tabEntries += enriched.map { tab ->
            val panel = if (tab.name == "Entries" && onOpenZipEntry != null && this is ZipBinaryProvider) {
                enrichZipEntriesTab(tab, onOpenZipEntry).toPanel(state)
            } else if (tab.name == "Members" && this is ArchiveBinaryProvider) {
                enrichArchiveMembersTab(tab, state.project, archiveName).toPanel(state)
            } else {
                tab.toPanel(state)
            }
            TabEntry(panel, tab.priority, pt.providerIndex)
        }
        tabEntries += pt.grouped.mapNotNull { grouped ->
            enrichedGrouped[grouped]?.let { panel -> TabEntry(panel, grouped.priority, pt.providerIndex) }
        }
    }
    // Sort by priority first (High < Medium < Low ordinal), then by provider order.
    tabEntries.sortWith(compareBy<TabEntry> { it.priority }.thenBy { it.providerIndex })
    // Low-priority tabs go after all other panels (including non-table provider panels).
    val (lowEntries, normalEntries) = tabEntries.partition { it.priority == TabPriority.Low }
    val panels = mutableListOf<Panel>()
    panels += normalEntries.map { it.panel }
    panels += providerPanels
    panels += lowEntries.map { it.panel }
    return panels.toTypedArray()
}

/**
 * Enriches the ZIP "Entries" tab descriptor with a row-click action that
 * opens the clicked entry via [onOpenEntry]. The entry list is obtained
 * from the [ZipBinaryProvider]'s cached [ZipBinaryProvider.displayEntries].
 */
private fun ZipBinaryProvider.enrichZipEntriesTab(
    tab: TableTabDescriptor,
    onOpenEntry: (ZipEntry) -> Unit,
): TableTabDescriptor {
    val entries = displayEntries
    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        defaultSortColumn = tab.defaultSortColumn,
        defaultSortAscending = tab.defaultSortAscending,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowAction = tab.rowAction,
        contextMenuItems = tab.contextMenuItems,
        rowClickAction = { index -> onOpenEntry(entries[index]) },
        dynamicContextMenuProvider = tab.dynamicContextMenuProvider,
        prologueText = tab.prologueText,
        emptyMessage = tab.emptyMessage,
        childrenProvider = tab.childrenProvider,
        alwaysShowSearch = tab.alwaysShowSearch,
        searchPlaceholder = tab.searchPlaceholder,
        showIdColumn = tab.showIdColumn,
        priority = tab.priority,
    )
}

/**
 * Enriches the archive "Members" tab descriptor with a row-click action that
 * extracts the clicked member to a temp file and opens it in a child editor.
 *
 * The extraction logic mirrors the original [ArchiveMembersPanel]: a single
 * temp directory is created per archive, member bytes are sliced on demand,
 * and the extracted file is opened via [FileEditorManager]. The service-level
 * coroutine scope is used so the extraction outlives the panel.
 */
private fun ArchiveBinaryProvider.enrichArchiveMembersTab(
    tab: TableTabDescriptor,
    project: Project,
    archiveName: String?,
): TableTabDescriptor {
    val provider = this
    val members = provider.members.filterNot { it.isBookkeeping }
    val ambiguousBaseNames = members.groupingBy { archiveMemberBaseName(it.name) }
        .eachCount().filterValues { it > 1 }.keys
    val classifications = members.map { classifyArchiveMember(provider.bytes, it.contentRange) }
    val tempDir = AtomicReference<Path?>(null)
    val scope = HexanaService.getInstance().coroutineScope

    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        defaultSortColumn = tab.defaultSortColumn,
        defaultSortAscending = tab.defaultSortAscending,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowAction = tab.rowAction,
        contextMenuItems = tab.contextMenuItems,
        rowClickAction = { index ->
            val member = members[index]
            val cls = classifications[index]
            if (!cls.openable) return@TableTabDescriptor
            val base = archiveMemberBaseName(member.name)
            val tabTitle = buildString {
                archiveName?.let { append(it).append('/') }
                append(base)
                if (base in ambiguousBaseNames) append(" (").append(member.index).append(')')
            }
            scope.launch(Dispatchers.IO) {
                try {
                    val dir = tempDir.updateAndGet { existing ->
                        existing ?: Files.createTempDirectory(
                            Path.of(PathManager.getTempPath()),
                            "hexana-archive-",
                        ).also { it.toFile().deleteOnExit() }
                    }!!
                    val bytes = provider.bytes
                        .slice(member.contentRange.offset, member.contentRange.size)
                        .toByteArray()
                    val fileName = "${member.index}-${sanitizeArchiveMemberName(member.name)}${archiveMemberExtension(cls.kind)}"
                    val path = dir.resolve(fileName)
                    Files.write(path, bytes)
                    path.toFile().deleteOnExit()
                    val vf = VfsUtil.findFile(path, true) ?: return@launch
                    withContext(Dispatchers.EDT) {
                        if (project.isDisposed) return@withContext
                        vf.putUserData(ArchiveMemberTabTitleProvider.TITLE_KEY, tabTitle)
                        FileEditorManager.getInstance(project).openFile(vf, true)
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    HexanaLog.LOG.warn("Failed to extract archive member ${member.name}", t)
                }
            }
        },
        dynamicContextMenuProvider = tab.dynamicContextMenuProvider,
        prologueText = tab.prologueText,
        emptyMessage = tab.emptyMessage,
        childrenProvider = tab.childrenProvider,
        alwaysShowSearch = tab.alwaysShowSearch,
        searchPlaceholder = tab.searchPlaceholder,
        showIdColumn = tab.showIdColumn,
        priority = tab.priority,
    )
}

/** The member's file name without any archive-internal directory path. */
private fun archiveMemberBaseName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').ifEmpty { name }

/** Keeps only filesystem-safe characters so a member name can become a temp file name. */
private fun sanitizeArchiveMemberName(name: String): String {
    val base = archiveMemberBaseName(name)
    val cleaned = base.map { ch ->
        if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
    }.joinToString("")
    return cleaned.take(96).ifEmpty { "member" }
}

/** A file-name hint for the extracted member; the content detector re-sniffs the magic regardless. */
private fun archiveMemberExtension(kind: ArchiveMemberKind): String = when (kind) {
    ArchiveMemberKind.ELF,
    ArchiveMemberKind.MACH_O,
    ArchiveMemberKind.FAT_MACH_O,
    ArchiveMemberKind.COFF -> ".o"
    ArchiveMemberKind.PE -> ".exe"
    ArchiveMemberKind.ARCHIVE -> ".a"
    ArchiveMemberKind.SHORT_IMPORT,
    ArchiveMemberKind.UNKNOWN -> ""
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
