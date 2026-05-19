package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.IdeActions
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.HexViewState
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.document.*
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.journal.JournalLoadResult
import org.jetbrains.hexana.document.journal.JournalPersister
import org.jetbrains.hexana.dwarf.ui.dwarfPanels
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import kotlinx.coroutines.flow.MutableStateFlow
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
        // nothing to attach a sidecar to.
        val sourceFile = (binaryProvider as? BinaryVirtualFileProvider)?.file
        val persister: JournalPersister = if (sourceFile != null) {
            IdeaJournalPersister(sourceFile)
        } else {
            JournalPersister.NoOp
        }
        val originalBytes = binaryProvider.bytes.toByteArray()
        val doc = editableBinaryDocumentOf(bytes = originalBytes, persister = persister)
        // Restore in-progress edits from a previously-persisted sidecar.
        // The codec validates hash + length; on Mismatch we leave the doc
        // empty and log — the stale sidecar stays on disk so the user can
        // still recover it manually. Discard / Backup-then-discard UX is
        // a follow-up.
        if (sourceFile != null) {
            val result = loadSidecarIfPresent(sourceFile, originalBytes)
            when (result) {
                is JournalLoadResult.Loaded -> {
                    doc.loadJournal(result.journal)
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
        }
        doc
    }
    // Set to true when a HEX cell edit commits. From then on, raw byte
    // mutations bypass the WAT encoder so the binary's structural shape
    // (section sizes, function counts, DWARF offsets …) is no longer
    // trustworthy. The live provider gets demoted to GenericBinaryProvider
    // and the panel list collapses to just the Hex view + status bar —
    // matching what GenericBinaryProvider would surface for an unknown-
    // format file.
    var hexEditedSinceOpen by remember(binaryProvider) { mutableStateOf(false) }
    // The "live" provider for the WAT virtualized panel + Hex view. If the
    // doc replayed a sidecar, the original bytes are stale — build a live
    // provider from the post-replay bytes so the WAT view + Hex view render
    // the user's restored edits, not the on-disk original. If the doc is
    // empty (no sidecar / Mismatch), fall back to the original provider.
    var liveProvider by remember(binaryProvider, editableDoc) {
        val initial: BinaryProvider = if (editableDoc.canUndo()) {
            val bytes = editableDoc.read(0L, editableDoc.length.toInt())
            try {
                rebuildProviderFromBytes(binaryProvider, bytes) ?: binaryProvider
            } catch (t: Throwable) {
                org.jetbrains.hexana.HexanaLog.LOG.warn(
                    "Parser rejected replayed-journal bytes; showing original provider",
                    t,
                )
                binaryProvider
            }
        } else {
            binaryProvider
        }
        mutableStateOf<BinaryProvider>(initial)
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
    BoxWithConstraints(modifier = Modifier
        .fillMaxSize()
        .background(HexanaTheme.backgroundColor)
        .onPreviewKeyEvent(docKeyHandler)) {
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
                )
                EditingDemoBar(
                    doc = editableDoc,
                    stampKey = editableDoc.modificationStamp,
                    binaryProvider = binaryProvider,
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    HexViewer(
                        binaryProvider = liveProvider,
                        state = state,
                        focusRequester = hexFocusRequester,
                        editableDoc = editableDoc,
                        onHexCommit = { hexEditedSinceOpen = true },
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
                        )
                    )
                }
            })
        })
    }
}

/**
 * Demo strip: button that flips byte 0 to 0xFF, plus Undo / Redo, plus a
 * live readout of byte 0 and the modification stamp. Wired to the shared
 * [EditableBinaryDocument]; the [stampKey] parameter (the document's
 * `modificationStamp` at the time of recomposition) is the recomposition
 * trigger that keeps the readout fresh — recomposition is driven from the
 * parent, which reads the stamp after the live provider rebuild.
 *
 * This strip is a placeholder for the eventual hex-cell edit affordance in
 * [HexViewer]. Once that lands, the demo strip can be removed.
 */
@Composable
private fun EditingDemoBar(
    doc: EditableBinaryDocument,
    stampKey: Long,
    binaryProvider: BinaryProvider,
) {
    @Suppress("UNUSED_PARAMETER") val unused = stampKey
    val sourceFile = (binaryProvider as? BinaryVirtualFileProvider)?.file
    val finalizedName = sourceFile?.name?.let { computeFinalizedName(it) }
    val hasEdits = doc.canUndo()
    val canFinalize = hasEdits && sourceFile != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HexanaTheme.backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Discard: walks the undo journal back to its origin then deletes
        // the on-disk sidecar via `clearPersistedJournal()`. The end state
        // is the original bytes with no journal entries — equivalent to
        // re-opening the file fresh. Undo / Redo themselves now ride the
        // user's IDE keymap (Cmd+Z / Cmd+Shift+Z by default) — they're
        // handled at the BinaryEditorView root via `KeymapShortcuts`.
        OutlinedButton(
            onClick = {
                while (doc.canUndo()) doc.undo()
                doc.clearPersistedJournal()
            },
            enabled = hasEdits,
        ) { Text("Discard") }
        Spacer(modifier = Modifier.width(8.dp))
        DefaultButton(
            onClick = {
                if (sourceFile != null && finalizedName != null) {
                    val bytes = doc.read(0L, doc.length.toInt())
                    finalizeWriteToSibling(sourceFile, finalizedName, bytes)
                    // Clear the on-disk sidecar — the user has captured the
                    // current state in <source>.edited.<ext>, so the journal
                    // bound to the original file is no longer needed. The
                    // doc keeps its in-memory undo history, so the user can
                    // keep editing; the next commit will recreate the sidecar.
                    doc.clearPersistedJournal()
                }
            },
            enabled = canFinalize,
        ) { Text(if (finalizedName != null) "Finalize → $finalizedName" else "Finalize") }
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * `<base>.edited.<ext>` next to the original. Plain `<name>.edited` if there
 * is no extension. Subsequent finalizes overwrite the same target — the user
 * can clone it before re-finalizing if they want history.
 */
private fun computeFinalizedName(originalName: String): String {
    val dot = originalName.lastIndexOf('.')
    return if (dot < 0) "$originalName.edited"
    else originalName.substring(0, dot) + ".edited" + originalName.substring(dot)
}

/**
 * Writes [bytes] to a sibling of [source] named [outputName]. Creates the
 * file on first finalize; overwrites on subsequent ones. Runs on the EDT
 * inside a write action — required by the IntelliJ VFS contract.
 */
private fun finalizeWriteToSibling(
    source: com.intellij.openapi.vfs.VirtualFile,
    outputName: String,
    bytes: ByteArray,
) {
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        com.intellij.openapi.application.WriteAction.run<Throwable> {
            val parent = source.parent
                ?: error("source file has no parent directory: ${source.path}")
            val existing = parent.findChild(outputName)
            val target = existing ?: parent.createChildData(parent, outputName)
            target.setBinaryContent(bytes)
            org.jetbrains.hexana.HexanaLog.LOG.info(
                "Finalized ${bytes.size} bytes → ${target.path}",
            )
        }
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

    var expanded by remember { mutableStateOf(false) }

    val tabs by remember(state.tabIndex) {
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
            TabStrip(
                tabs = tabs,
                style = JewelTheme.defaultTabStyle,
            )
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


/**
 * Builds a fresh [BinaryProvider] from [newBytes] when the editable document
 * commits a change. File-aware capabilities (the old WAT-text panel that
 * needs `createTextEditor`, DWARF source-location lookups) live on the
 * concrete [WasmBinaryVirtualFileProvider] subclass instantiated in
 * `HexanaFileEditor` and aren't reachable from a freshly-built provider —
 * panels that depend on them are dropped from the post-edit panel list.
 * Returns `null` if [original]'s type isn't supported for rebuild.
 */
private fun rebuildProviderFromBytes(original: BinaryProvider, newBytes: ByteArray): BinaryProvider? {
    val buf = newBytes.toCommonByteBuffer()
    return when (original) {
        is WasmBinaryVirtualFileProvider, is WasmBinaryProvider -> WasmBinaryProvider(buf)
        is WasmComponentBinaryVirtualFileProvider, is WasmComponentBinaryProvider -> WasmComponentBinaryProvider(buf)
        is GenericBinaryProvider -> GenericBinaryProvider(buf)
        else -> null
    }
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
): Array<Panel> {
    val enricher = getTabEnricher()
    val tabPanels = TableTabsProviderRegistry.providers.flatMap {
        enricher(it.tableTabs(this), state, pathsNav).map { it.toPanel(state) }
    }
    val extraPanels = when (this) {
        is GenericBinaryProvider -> emptyList()
        is WasmBinaryVirtualFileProvider -> {
            if (canProvideWasmModule()) {
                listOf(
                    Panel("Top") { WasmPanelSize(this, state) },
                    Panel("Monos") { WasmPanelMonos(this, state) },
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
                    Panel("Top") { WasmPanelSize(this, state) },
                    Panel("Wat") { WasmPanelWat(this, state) },
                )
            } else {
                errorPanel(state)
            }
        }
        is WasmBinaryProvider -> {
            if (canProvideWasmModule()) {
                listOf(
                    Panel("Top") { WasmPanelSize(this, state) },
                    Panel("Monos") { WasmPanelMonos(this, state) },
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
                    Panel("Top") { WasmPanelSize(this, state) },
                )
            } else emptyList()
        }
        else -> emptyList()
    }
    val panels = (tabPanels + extraPanels).toMutableList()
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
