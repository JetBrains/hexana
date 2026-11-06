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
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.actionSystem.IdeActions
import org.jetbrains.hexana.HexanaBinaryVirtualFile
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.HexViewState
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.analytics.binaryFormatOf
import org.jetbrains.hexana.document.*
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.journal.JournalLoadResult
import org.jetbrains.hexana.document.journal.JournalPersister
import org.jetbrains.hexana.dwarf.ui.dwarfOutlineSectionsOf
import org.jetbrains.hexana.shared.DWARF_GROUP_TAB_NAME
import org.jetbrains.hexana.dwarf.ui.dwarfPanels
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.ui.panel.GutterMark
import org.jetbrains.hexana.ui.panel.GutterMarksModel
import org.jetbrains.hexana.ArchiveMemberTabTitleProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.nativebin.JVM_FILE_SYSTEM
import org.jetbrains.hexana.providers.ClassFileBinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.features.RuntimeTargetsService
import org.jetbrains.hexana.features.WasmFeatureCatalogService
import org.jetbrains.hexana.features.WasmRuntimeCompatibilityConfigurable
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.shared.COMPATIBILITY_TAB_NAME
import org.jetbrains.hexana.shared.PROBLEMS_TAB_NAME
import org.jetbrains.hexana.mcp.scripting.ScriptTabBinding
import org.jetbrains.hexana.ui.panel.ProviderTabContent
import org.jetbrains.hexana.ui.panel.displayName
import org.jetbrains.hexana.shared.CLR_IL_TAB_NAME
import org.jetbrains.hexana.shared.DEX_SMALI_TAB_NAME
import org.jetbrains.hexana.shared.GroupedTableTabDescriptor
import org.jetbrains.hexana.shared.NativeDebugTabData
import org.jetbrains.hexana.shared.PdbCompanionLoader
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.shared.TabPriority
import org.jetbrains.hexana.shared.PcapTableTabsProvider
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.jetbrains.hexana.ui.panel.*
import org.jetbrains.hexana.pcap.PcapDissectedPacket
import org.jetbrains.hexana.pcap.PcapDissector
import org.jetbrains.hexana.pcap.PlaintextHttpBody
import org.jetbrains.hexana.pcap.HttpReplay
import org.jetbrains.hexana.pcap.CapturedHttpRequest
import org.jetbrains.hexana.ui.pcap.HttpClientReplayExport
import org.jetbrains.hexana.ui.pcap.HttpReplayDialog
import org.jetbrains.hexana.providers.PcapBinaryProvider
import org.jetbrains.hexana.providers.TarBinaryProvider
import org.jetbrains.hexana.providers.memory.isMemoryBudgetEligible
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.shared.RowContextMenuItemDescriptor
import org.jetbrains.hexana.tar.TarEntry
import org.jetbrains.hexana.zip.ZipEntry
import org.jetbrains.hexana.shared.APK_CLASSES_TAB_NAME
import org.jetbrains.hexana.shared.isApkFileName
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
    /**
     * Path of the open file, or null for a binary with no file on disk (a ZIP
     * entry, a nested module opened from a table). Script tabs surface it as
     * `binary.path`.
     */
    filePath: String? = null,
    /**
     * Size of the file on disk when only its head is loaded (a file over
     * `MAX_BINARY_WINDOW_BYTES`), else `null`. Such a view is read-only.
     */
    partialFileSize: Long? = null,
    /** Turns the format badge into a parser picker; see [ParserChoice]. */
    parserChoice: ParserChoice? = null,
    /** Tabs `hexana.defineTab` attached to this file, shown after the built-in ones. */
    scriptTabs: List<ScriptTabBinding> = emptyList(),
    /** Regions a scripted provider's `parse()` reported, tinted on top of the provider's own. */
    scriptStructureRanges: List<StructureRange> = emptyList(),
    parentFileName: String? = null,
    onNavigateToParent: (() -> Unit)? = null,
    wasmFunctionNavTargetFlow: MutableStateFlow<WasmFunctionNavTarget?> = remember { MutableStateFlow(null) },
    wasmByteOffsetNavFlow: MutableStateFlow<Int?> = remember { MutableStateFlow(null) },
    wasmTypeNavFlow: MutableStateFlow<Int?> = remember { MutableStateFlow(null) },
    wasmTypeGraphFocusFlow: MutableStateFlow<Int?> = remember { MutableStateFlow(null) },
    wasmTypeInfoNavFlow: MutableStateFlow<Int?> = remember { MutableStateFlow(null) },
    wasmUiProbeSink: ((String, String) -> Unit)? = null,
    classFileNavTargetFlow: MutableStateFlow<ClassFileNavTarget?> = remember { MutableStateFlow(null) },
    onOpenZipEntry: ((ZipEntry) -> Unit)? = null,
    /** Opens an APK's DEX [ZipEntry] with class `classDefIndex` selected on its Classes tab. */
    onRevealApkDexClass: ((entry: ZipEntry, classDefIndex: Int) -> Unit)? = null,
    onOpenTarEntry: ((TarEntry) -> Unit)? = null,
    decompiledContentPanelFlow: StateFlow<Panel?> = remember { MutableStateFlow(null) },
    gutterMarksModelFlow: StateFlow<GutterMarksModel?>? = null,
    executionOffsetFlow: StateFlow<Long?>? = null,
    onToggleWasmBreakpoint: ((funcIdx: Int, byteOffset: Long, instrOrdinal: Int) -> Unit)? = null,
    onToggleNativeBreakpoint: ((va: Long) -> Unit)? = null,
    onDeleteBreakpoint: ((id: String) -> Unit)? = null,
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
        // In-memory nested views (HexanaBinaryVirtualFile) have no disk
        // location for a sidecar — they keep the NoOp persister.
        val sourceFile = (binaryProvider as? BinaryVirtualFileProvider)?.file
            ?.takeUnless { it is HexanaBinaryVirtualFile }
        val persister: JournalPersister = if (sourceFile != null) {
            IdeaJournalPersister(sourceFile)
        } else {
            JournalPersister.NoOp
        }
        editableBinaryDocumentOf(bytes = binaryProvider.bytes.duplicate(), persister = persister)
    }
    // What edits go through: nothing, for the head of a file too large to load. Overwriting
    // the file with the edited head would cut it off at the window's end.
    val hexEditDoc: EditableBinaryDocument? = editableDoc.takeIf { partialFileSize == null }
    // Sidecar restore runs off-EDT. While `loadSidecarIfPresent` is in
    // flight the editor renders a centred spinner so the user never sees
    // the original bytes briefly before they're replaced by the restored
    // ones (a visible "jump" on file open). For files with no sidecar the
    // gate clears as soon as the IO dispatcher returns null — typically
    // within one frame.
    var sidecarRestored by remember(binaryProvider, editableDoc) { mutableStateOf(false) }
    // The spinner is for opening the file only. A parser chosen for it later hands this view a
    // new provider for the same bytes, and blanking the whole editor for the restore round-trip
    // made every switch blink; the restore still runs, and a file with unsaved edits simply
    // shows them a frame later.
    var restoredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(sidecarRestored) { if (sidecarRestored) restoredOnce = true }
    LaunchedEffect(binaryProvider, editableDoc) {
        val sourceFile = (binaryProvider as? BinaryVirtualFileProvider)?.file
        // A partial view never edits, so there is no journal to restore into it.
        if (sourceFile == null || partialFileSize != null) {
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
    // The state holder outlives the provider, but detail bytes belong to the
    // one they were dissected from: a decrypted TLS record read out of bytes
    // that have since been edited (and, once a hex cell commits, out of a
    // format the live provider no longer even claims) is not something to keep
    // showing beside the hex view.
    LaunchedEffect(liveProvider) {
        state.setRowDetail(null)
        state.setShowRowDetail(false)
    }

    // State hoisted ABOVE the panel-rebuild boundary so commits don't
    // reset the user's scroll position or fold pattern. The panels array
    // re-`remember`s on every liveProvider change (rebuilding lambdas);
    // anything held inside those lambdas via positional `remember` resets.
    // Lifting it here means each new lambda captures the SAME LazyListState
    // and folded-section MutableState references, preserving state.
    // Both survive a liveProvider commit but not a different [binaryProvider] — a parser
    // chosen for the file — whose WAT has unrelated chunks and lines.
    val watVirtualizedListState = key(binaryProvider) { rememberLazyListState() }
    val watVirtualizedFolded = remember(binaryProvider) { mutableStateOf<Set<ChunkId>>(emptySet()) }
    // Binary format derived once per opened provider — fed into the
    // `hex_tab_selected` analytics event so cross-tab funnels can filter
    // by container format (e.g. "ELF users → Disassembly tab").
    val binaryFormat = remember(binaryProvider, fileName) {
        val name = fileName.ifEmpty { (binaryProvider as? BinaryVirtualFileProvider)?.file?.name }
        binaryFormatOf(name, binaryProvider)
    }
    // The WASM function nav-target flow. Lifted here (above the panels
    // remember boundary) so the same flow instance persists across provider
    // rebuilds triggered by edit commits. The default is the flow passed in
    // from HexanaFileEditor; panels capture it by reference.
    val wasmNavFlow: MutableStateFlow<WasmFunctionNavTarget?> = wasmFunctionNavTargetFlow
    val wasmOffsetNavFlow: MutableStateFlow<Int?> = wasmByteOffsetNavFlow
    // Type Graph cross-tab flows come in as parameters (with remembered
    // defaults) so HexanaFileEditor and UI-test probes can drive them; the
    // same instances survive panel rebuilds on edit commits.
    // wasmTypeNavFlow drives the WAT panel to scroll to a type's definition row;
    // wasmTypeGraphFocusFlow drives the Type Graph panel to focus on a type;
    // wasmTypeInfoNavFlow opens the WAT panel's type-info popup.

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
        if (!sidecarRestored && !restoredOnce) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            return@BoxWithConstraints
        }
        val pathsNav = remember(binaryProvider) { PathsNavigationState() }
        // Panels are keyed to the ORIGINAL provider, not liveProvider — so
        // they don't rebuild on every commit. The WAT virtualized lambda
        // captures `liveProvider` (a Compose State delegate); its body
        // reads the current value on each composition and recomposes when
        // it changes. Non-edit panel lambdas use `this` (the original
        // provider) and stay frozen across edits.
        //
        // Tab headers are shown immediately using cheap heuristics
        // (provider type checks, section existence) with loading spinners
        // in the content area. The full panel content is resolved on a
        // background thread and swapped in once ready.
        // Track whether panel resolution has completed so we don't
        // re-resolve when the composable re-enters composition (e.g.
        // switching IDE editor tabs and back). The flag is keyed to the
        // same inputs as the panels list so it resets on provider change.
        // Shared by the initial tab resolution and the Compatibility tab's refresh, so the
        // two cannot drift on the hooks they hand the descriptors.
        fun buildTableTabsContext(nativeDebugData: NativeDebugTabData?) = TableTabsContext(
            nativeDebugData = nativeDebugData,
            fileName = fileName,
            // Drives the Compatibility tab. Null for a project with no base path, and the
            // tab is then absent rather than empty.
            runtimeCompatibility = RuntimeTargetsService.getInstance(state.project).compatibility(),
            openUrl = { url -> BrowserUtil.browse(url) },
            openRuntimeSettings = {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    state.project,
                    WasmRuntimeCompatibilityConfigurable::class.java,
                )
            },
            // The Problems tab lists these alongside the inspections' own; the toolbar badge
            // reads the same pair through ideInspectionContext, so the two agree.
            hostProblems = scriptCheckProblems(state.project, filePath),
            problemNavigator = binaryProvider.problemNavigator(state),
        )

        val panelsResolved = remember(binaryProvider, hexEditedSinceOpen) { mutableStateOf(false) }
        // PcapBinaryProvider.sslKeyLog is a plain `var`, so installing a key log
        // is invisible to Compose. Bumping this counter is what refreshes the
        // header badge and the Load/Change button label.
        var sslKeyLogGeneration by remember(binaryProvider) { mutableStateOf(0) }
        // Whether the capture contains TLS records at all. The scan walks every
        // packet, so it runs in the background effect below rather than during
        // composition.
        var pcapHasTls by remember(binaryProvider) { mutableStateOf(false) }
        val panels = remember(binaryProvider, hexEditedSinceOpen) {
            val scriptSettings = ScriptTabSettings.getInstance(state.project)
            val scriptPanels = scriptSettings.getScriptPaths(fileName).map { scriptPath ->
                createScriptPanel(scriptPath, binaryProvider, fileName, filePath, state.project)
            }
            val placeholders = if (hexEditedSinceOpen) emptyList() else binaryProvider.discoverPanelNames(fileName)
                .map { name -> Panel(name) { LoadingTabContent() } }
            val decompiled = decompiledContentPanelFlow.value?.let { listOf(it) } ?: emptyList()
            mutableStateListOf(*(placeholders + decompiled + scriptPanels).toTypedArray())
        }
        // The decompiled panel may arrive late: for in-memory files its backing
        // VirtualFile is resolved in the background (the VFS refresh involved is
        // forbidden on the EDT). Insert it before the script panels when it lands.
        LaunchedEffect(panels) {
            decompiledContentPanelFlow.collect { decompiled ->
                if (decompiled != null && panels.none { it === decompiled }) {
                    val scriptStart = panels.indexOfFirst { it.isAppended() }.takeIf { it >= 0 } ?: panels.size
                    panels.add(scriptStart, decompiled)
                }
            }
        }
        // Provider-script tabs follow the scripts, not the provider: they change when a
        // script is edited, so they are swapped in place rather than rebuilt with the list.
        // Placed after the built-in tabs and before the user's own script tabs.
        LaunchedEffect(panels, scriptTabs) {
            // The selection is an index, so keep it on the same logical tab: the tabs after
            // the provider ones shift when their count changes. A replaced provider tab is
            // found again by its name.
            val selected = panels.getOrNull(state.currentState.value.tabIndex)
            panels.removeAll { it is ProviderTabPanel }
            val insertAt = panels.indexOfFirst { it is ScriptPanel }.takeIf { it >= 0 } ?: panels.size
            panels.addAll(insertAt, scriptTabs.map { binding -> ProviderTabPanel(binding, state) })
            val reselected = when (selected) {
                null -> -1
                is ProviderTabPanel -> panels.indexOfFirst { it is ProviderTabPanel && it.tabName == selected.tabName }
                else -> panels.indexOfFirst { it === selected }
            }
            if (reselected >= 0) {
                if (reselected != state.currentState.value.tabIndex) state.setSelectedTab(reselected)
            } else if (selected is ProviderTabPanel) {
                // Its tab is gone: fall back to the first tab rather than whatever slid in.
                state.setSelectedTab(0)
            }
        }
        // Publish the live tab names so non-composition observers (the UI-test
        // probe) see what is actually rendered, including placeholder swaps.
        LaunchedEffect(panels) {
            snapshotFlow { panels.map { it.description } }
                .collect { state.setPanelNames(it) }
        }
        LaunchedEffect(binaryProvider, hexEditedSinceOpen) {
            if (hexEditedSinceOpen) {
                // No placeholders were advertised, so there is nothing to
                // resolve: report completion to probe observers immediately.
                state.setPanelsResolved(true)
                return@LaunchedEffect
            }
            if (panelsResolved.value) return@LaunchedEffect
            // Reattach the key log this capture was last paired with *before*
            // the tabs are built, so the "Packets" tab is decrypted on first
            // render rather than built encrypted and rebuilt a moment later.
            // Sitting after the panelsResolved early return keeps re-entering
            // composition from re-reading the key log.
            (binaryProvider as? PcapBinaryProvider)?.let { pcap ->
                // Both the key log read and hasEncryptedPackets (which dissects
                // every packet) stay off the composition thread; only the state
                // writes come back to it.
                val (restored, hasTls) = withContext(Dispatchers.IO) {
                    val restored = restoreRememberedSslKeyLog(pcap)
                    restored to (pcap.hasEncryptedPackets || pcap.sslKeyLog.isNotEmpty)
                }
                pcapHasTls = hasTls
                if (restored) sslKeyLogGeneration++
            }
            // Same for a PE image's PDB: attached before the tabs are built, so the disassembly,
            // IL view, Methods and Debug Info tabs show its source lines on first render.
            (binaryProvider as? PeBinaryProvider)?.let { pe ->
                withContext(Dispatchers.IO) {
                    try {
                        PdbCompanionLoader.load(pe, JVM_FILE_SYSTEM)
                    } catch (e: Exception) {
                        HexanaLog.LOG.warn("Failed to load the PDB of ${pe.sourcePath}", e)
                    }
                }
            }
            state.setPanelsResolved(false)
            state.clearResolvedTableTabs()
            // Resolve hardcoded panels (Dominators, WAT, DWARF, etc.) and
            // table-tab panels independently so whichever finishes first
            // replaces its placeholders immediately — the user doesn't wait
            // for the slowest tab before seeing any content.
            val hardcodedJob = launch(Dispatchers.Default) {
                val hardcoded = binaryProvider.resolveHardcodedPanels(
                    state = state,
                    pathsNav = pathsNav,
                    editableDoc = hexEditDoc,
                    watVirtualizedListState = watVirtualizedListState,
                    watVirtualizedFolded = watVirtualizedFolded,
                    liveProviderProvider = { liveProvider },
                    wasmFunctionNavTargetFlow = wasmNavFlow,
                    wasmByteOffsetNavFlow = wasmOffsetNavFlow,
                    wasmTypeNavFlow = wasmTypeNavFlow,
                    wasmTypeGraphFocusFlow = wasmTypeGraphFocusFlow,
                    wasmTypeInfoNavFlow = wasmTypeInfoNavFlow,
                    wasmUiProbeSink = wasmUiProbeSink,
                    classFileNavTargetFlow = classFileNavTargetFlow,
                    fileName = fileName,
                    onRevealApkDexClass = onRevealApkDexClass,
                    gutterMarksModelFlow = gutterMarksModelFlow,
                    executionOffsetFlow = executionOffsetFlow,
                    onToggleWasmBreakpoint = onToggleWasmBreakpoint,
                    onToggleNativeBreakpoint = onToggleNativeBreakpoint,
                    onDeleteBreakpoint = onDeleteBreakpoint,
                )
                withContext(Dispatchers.Main) {
                    replacePlaceholders(panels, hardcoded)
                }
            }
            // Launch each table-tab provider independently so fast providers
            // (e.g. Top, Sections) appear before slow ones (Size, Garbage).
            // Within each provider, each tab descriptor is resolved in its own
            // coroutine so individual tabs replace their placeholders as soon
            // as they're ready — a slow "Functions" tab won't block "Data".
            val enricher = binaryProvider.getTabEnricher()
            val providers = TableTabsProviderRegistry.providers
            val tableTabJobs = providers.mapIndexed { index, p ->
                launch(Dispatchers.Default) {
                    val tableTabsContext = buildTableTabsContext(
                        (binaryProvider as? NativeBinaryProvider)
                            ?.let { NativeDebugTabData.scan(it, JVM_FILE_SYSTEM, state.project.basePath) },
                    )
                    val availableNames = p.availableTabNames(binaryProvider, tableTabsContext)
                    if (availableNames != null) {
                        // Provider supports cheap name discovery — resolve
                        // each tab independently via singleTab() so a slow
                        // tab (e.g. Functions) doesn't block fast ones.
                        val (ordinaryNames, groupedNames) = availableNames
                        val tabJobs = ordinaryNames.map { tabName ->
                            launch {
                                try {
                                    // Cheap discovery is optimistic: a tab it advertised can
                                    // resolve to nothing (e.g. Monos with no groups). Remove
                                    // the placeholder then, or its spinner never ends.
                                    val tab = p.singleTab(tabName, binaryProvider, tableTabsContext)
                                    val enriched = tab?.let { enricher(listOf(it), state, pathsNav).firstOrNull() }
                                    if (enriched == null) {
                                        withContext(Dispatchers.Main) {
                                            replacePlaceholder(panels, tabName, panel = null)
                                        }
                                        return@launch
                                    }
                                    val panel = binaryProvider.resolveOrdinaryTabPanel(
                                        tab = enriched,
                                        state = state,
                                        archiveName = fileName,
                                        onOpenZipEntry = onOpenZipEntry,
                                        onOpenTarEntry = onOpenTarEntry,
                                    )
                                    withContext(Dispatchers.Main) {
                                        state.publishResolvedTableTab(
                                            tabName = enriched.name,
                                            columns = enriched.columns.map { it.name },
                                            prologueText = enriched.prologueText,
                                        )
                                        replacePlaceholders(panels, listOf(panel))
                                    }
                                } catch (c: kotlinx.coroutines.CancellationException) {
                                    throw c
                                } catch (t: Throwable) {
                                    HexanaLog.LOG.warn("Failed to resolve tab '$tabName'", t)
                                    withContext(Dispatchers.Main) {
                                        replacePlaceholder(panels, tabName, panel = null)
                                    }
                                }
                            }
                        }
                        // Grouped tabs are typically fewer; resolve each independently too.
                        val grouped = p.groupedTabs(binaryProvider, tableTabsContext)
                        // Advertised grouped names whose descriptor resolved to nothing
                        // (e.g. Garbage with no findings) leave dangling placeholders.
                        val resolvedGroupNames = grouped.map { it.name }.toSet()
                        val danglingGroupNames = groupedNames.filterNot { it in resolvedGroupNames }
                        if (danglingGroupNames.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                for (name in danglingGroupNames) {
                                    replacePlaceholder(panels, name, panel = null)
                                }
                            }
                        }
                        val groupedJobs = grouped.map { g ->
                            launch {
                                try {
                                    val panel = binaryProvider.resolveGroupedTabPanel(
                                        g = g, state = state, pathsNav = pathsNav,
                                    )
                                    // Match by the group name, not the panel name: a single-subtab
                                    // group is promoted to a "Group: Sub" panel name that would
                                    // never match its "Group" placeholder.
                                    withContext(Dispatchers.Main) {
                                        replacePlaceholder(panels, g.name, panel)
                                    }
                                } catch (c: kotlinx.coroutines.CancellationException) {
                                    throw c
                                } catch (t: Throwable) {
                                    HexanaLog.LOG.warn("Failed to resolve grouped tab '${g.name}'", t)
                                    withContext(Dispatchers.Main) {
                                        replacePlaceholder(panels, g.name, panel = null)
                                    }
                                }
                            }
                        }
                        tabJobs.forEach { it.join() }
                        groupedJobs.forEach { it.join() }
                    } else {
                        // Fallback: resolve all tabs at once for providers
                        // without cheap name discovery.
                        val ordinary = p.tableTabs(binaryProvider, tableTabsContext)
                        val grouped = p.groupedTabs(binaryProvider, tableTabsContext)
                        val enrichedOrdinary = enricher(ordinary, state, pathsNav)
                        val ordinaryPanels = enrichedOrdinary.map { tab ->
                            binaryProvider.resolveOrdinaryTabPanel(
                                tab = tab, state = state,
                                archiveName = fileName, onOpenZipEntry = onOpenZipEntry,
                                onOpenTarEntry = onOpenTarEntry,
                            )
                        }
                        val groupedPanels = grouped.mapNotNull { g ->
                            binaryProvider.resolveGroupedTabPanel(
                                g = g, state = state, pathsNav = pathsNav,
                            )
                        }
                        withContext(Dispatchers.Main) {
                            for (tab in enrichedOrdinary) {
                                state.publishResolvedTableTab(
                                    tabName = tab.name,
                                    columns = tab.columns.map { it.name },
                                    prologueText = tab.prologueText,
                                )
                            }
                            replacePlaceholders(panels, ordinaryPanels + groupedPanels)
                        }
                    }
                }
            }
            // Wait for all jobs so cancellation propagates correctly.
            hardcodedJob.join()
            tableTabJobs.forEach { it.join() }
            panelsResolved.value = true
            state.setPanelsResolved(true)
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

        // Rebuild just the tabs whose content depends on host state, when that state changes:
        // .hexana/runtimes.json, or a background refresh of the feature catalog the verdicts
        // are read from, moves both the Compatibility columns and the runtime-compatibility
        // problems; a finished script run moves only the Problems tab. Re-resolving every panel
        // would re-run the expensive analyses for nothing. `drop(1)` skips the current value:
        // the initial resolution already used it.
        suspend fun refreshTab(tabName: String) {
            val rebuilt = withContext(Dispatchers.Default) {
                runCatching {
                    val context = buildTableTabsContext(null)
                    TableTabsProviderRegistry.providers
                        .firstNotNullOfOrNull { p -> p.singleTab(tabName, binaryProvider, context) }
                        ?.let { tab -> binaryProvider.getTabEnricher()(listOf(tab), state, pathsNav).firstOrNull() }
                }.getOrNull()
            }
            val panel = rebuilt?.let {
                binaryProvider.resolveOrdinaryTabPanel(
                    tab = it,
                    state = state,
                    archiveName = fileName,
                    onOpenZipEntry = onOpenZipEntry,
                    onOpenTarEntry = onOpenTarEntry,
                )
            }
            if (rebuilt != null) {
                state.publishResolvedTableTab(
                    tabName = rebuilt.name,
                    columns = rebuilt.columns.map { it.name },
                    prologueText = rebuilt.prologueText,
                )
            }
            // Inserts the tab when it was absent: a module with no problems gains one once
            // a declared runtime cannot run it, or a script check fails.
            replacePlaceholder(panels, tabName, panel)
        }
        LaunchedEffect(binaryProvider, panels) {
            merge(
                RuntimeTargetsService.getInstance(state.project).changes.drop(1),
                WasmFeatureCatalogService.getInstance().changes.drop(1),
            ).collect {
                refreshTab(COMPATIBILITY_TAB_NAME)
                refreshTab(PROBLEMS_TAB_NAME)
            }
        }
        LaunchedEffect(binaryProvider, panels) {
            ScriptFindingsStore.getInstance(state.project).changes.drop(1).collect {
                refreshTab(PROBLEMS_TAB_NAME)
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
                    filePath = filePath,
                    parserChoice = parserChoice,
                    onSizeClick = topTabIndex?.let { idx -> {
                        state.setSelectedTab(idx)
                        if (state.topFraction > 0.9f) {
                            state.setTopFraction(0.5f)
                        }
                    }},
                    parentFileName = parentFileName,
                    onNavigateToParent = onNavigateToParent,
                    onSaveAs = if (parentFileName != null) {
                        {
                            val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
                                "Save As", "Save the embedded content to a file",
                            )
                            val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
                                .createSaveFileDialog(descriptor, state.project)
                            val wrapper = dialog.save(fileName)
                            if (wrapper != null) {
                                com.intellij.openapi.application.runWriteAction {
                                    val buf = liveProvider.bytes
                                    val data = ByteArray(buf.limit())
                                    buf.position(0)
                                    buf.get(data, 0, data.size)
                                    wrapper.getVirtualFile(true)?.setBinaryContent(data)
                                }
                            }
                        }
                    } else null,
                    editableDoc = hexEditDoc,
                    originalBinaryProvider = binaryProvider,
                    fileSize = partialFileSize,
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
                    pcapHasTls = pcapHasTls,
                    sslKeyLogGeneration = sslKeyLogGeneration,
                    onSslKeyLogLoaded = {
                        sslKeyLogGeneration++
                        val pcap = liveProvider as? PcapBinaryProvider ?: return@FileDescriptionBar
                        val tabsProvider = PcapTableTabsProvider()
                        val newTabs = tabsProvider.tableTabs(pcap)
                        val packetsTab = newTabs.firstOrNull { it.name == "Packets" } ?: return@FileDescriptionBar
                        val enriched = pcap.enrichPcapPacketsTab(packetsTab, state.project)
                        val newPanel = enriched.toPanel(state)
                        val idx = panels.indexOfFirst { it.description == "Packets" }
                        if (idx >= 0) {
                            panels[idx] = newPanel
                        }
                    },
                )
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        HexViewer(
                            extraStructureRanges = scriptStructureRanges,
                            binaryProvider = liveProvider,
                            state = state,
                            focusRequester = hexFocusRequester,
                            editableDoc = hexEditDoc,
                            onHexCommit = { hexEditedSinceOpen = true },
                            suppressEditHighlight = overwriteShadowRanges != null,
                            onSectionLabelClick = { tabName ->
                                val idx = panels.indexOfTabLink(tabName)
                                if (idx >= 0) state.setSelectedTab(idx)
                            },
                        )
                    }
                    if (state.showDataInspector) {
                        Divider(
                            orientation = org.jetbrains.jewel.ui.Orientation.Vertical,
                            thickness = 1.dp,
                            modifier = Modifier.fillMaxHeight(),
                        )
                        DataInspectorPanel(
                            state = state,
                            bytes = liveProvider.bytes,
                        )
                    }
                    // A second, independent column: the row detail panel shows
                    // bytes that are not in the file, so it has nothing to do
                    // with the inspector's view of the cursor and neither one
                    // closes the other.
                    if (state.showRowDetail) {
                        Divider(
                            orientation = org.jetbrains.jewel.ui.Orientation.Vertical,
                            thickness = 1.dp,
                            modifier = Modifier.fillMaxHeight(),
                        )
                        RowDetailPanel(state = state)
                    }
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
                    val panel = createScriptPanel(scriptPath, binaryProvider, fileName, filePath, state.project)
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
        },
            // A generic binary has no tabs at all, and TabsPanel then draws nothing: the
            // hex view gets the whole editor instead of sharing it with empty space. The
            // list is observable, so a late decompiled panel still brings the split back.
            showSouthPanel = panels.isNotEmpty(),
        )
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
            ScrollableTabStrip(
                tabs = tabs,
                selectedIndex = state.tabIndex,
                resetKey = panels,
                modifier = Modifier.weight(1f),
            )
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

/**
 * A tab strip that keeps every tab at its natural width and, when they do not all fit,
 * scrolls: ‹ › buttons page through the hidden ones. Selecting the last tab (e.g. a tab
 * just added) scrolls to the end so it is visible.
 *
 * @param resetKey scroll position is reset when it changes, e.g. when the tab list is rebuilt
 */
@Composable
internal fun ScrollableTabStrip(
    tabs: List<TabData>,
    selectedIndex: Int,
    resetKey: Any?,
    modifier: Modifier = Modifier,
) {
    var tabBarWidthPx by remember { mutableIntStateOf(0) }
    var tabContentWidthPx by remember(resetKey) { mutableIntStateOf(0) }
    var tabOffsetPx by remember(resetKey) { mutableIntStateOf(0) }
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
    LaunchedEffect(selectedIndex, tabs.size, maxTabOffsetPx) {
        if (selectedIndex == tabs.size - 1 && maxTabOffsetPx > 0) {
            tabOffsetPx = maxTabOffsetPx
        }
    }

    Box(
        modifier = modifier
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

private fun NativeBinaryProvider.commonNativePanels(
    state: HexViewStateHolder,
    gutterMarksModelFlow: StateFlow<GutterMarksModel?>? = null,
    executionOffsetFlow: StateFlow<Long?>? = null,
    onToggleNativeBreakpoint: ((va: Long) -> Unit)? = null,
    onDeleteBreakpoint: ((id: String) -> Unit)? = null,
    /** False for managed images whose code all comes from IL (see [PeBinaryProvider.hasOnlyManagedCode]), where both views would show nothing useful. */
    includeDisassembly: Boolean = true,
): List<Panel> {
    val sbom = Panel("SBOM") { CycloneDxSbomPanel(this, state) }.takeIf { this.nativeImageInfo?.sbom != null }
    if (!includeDisassembly) return listOfNotNull(sbom)
    return listOfNotNull(
        sbom,
        Panel("Disassembly") { NativeDisassemblyPanel(this, state) },
        Panel("Disassembly (virtualized)") {
            val gutterModel by (gutterMarksModelFlow
                ?: remember { MutableStateFlow(null) }).collectAsState()
            NativeDisassemblyPanelVirtualized(
                provider = this,
                state = state,
                gutterMarksFlow = gutterModel?.marks,
                executionVaFlow = executionOffsetFlow,
                onToggleBreakpoint = onToggleNativeBreakpoint,
                onDeleteBreakpoint = onDeleteBreakpoint,
            )
        },
    )
}

/**
 * Replaces placeholder panels (those showing [LoadingTabContent]) with
 * resolved panels by matching on [Panel.description]. Placeholders whose
 * name matches a resolved panel are swapped in-place; resolved panels
 * with no matching placeholder are appended before script panels.
 *
 * Grouped tabs may be discovered under a short name (e.g. "Garbage") but
 * resolve to "Garbage: Data" when only one inner tab is present. To handle
 * this, when no exact match is found we also look for a placeholder whose
 * description is a prefix of the resolved panel's description followed by ": ".
 */
/**
 * Replaces the placeholder named [placeholderName] with [panel], or removes it
 * when [panel] is null (the advertised tab resolved to no content). Unlike
 * [replacePlaceholders] the match key is the placeholder's name, so a resolved
 * panel whose name differs (a promoted "Group: Sub" grouped tab) still lands
 * in its placeholder's slot. Script panels are never touched.
 */
/**
 * A tab a provider script attached with `hexana.defineTab`, paged from its live session.
 * Not closable: it belongs to the project's scripts, not to this file.
 */
private class ProviderTabPanel(binding: ScriptTabBinding, state: HexViewStateHolder) :
    Panel(binding.tab.displayName(), { ProviderTabContent(binding, state) }) {
    /** The full `user:` name: what a region's `tab` links to, and stable across rebinds. */
    val tabName: String = binding.tab.name
}

/**
 * The panel a region's tab link [tabName] names: a provider-script tab by its full `user:`
 * name — shown without the prefix, so it cannot be matched (or shadowed) by description —
 * otherwise a panel by description.
 */
private fun List<Panel>.indexOfTabLink(tabName: String): Int =
    indexOfFirst { it is ProviderTabPanel && it.tabName == tabName }
        .takeIf { it >= 0 }
        ?: indexOfFirst { it !is ProviderTabPanel && it.description == tabName }

/**
 * Panels appended after the format's own — provider-script tabs, then the user's script
 * tabs. Placeholder resolution never matches them, so a script tab named like a built-in
 * one is never replaced by it, and new format tabs are inserted before them.
 */
private fun Panel.isAppended(): Boolean = this is ScriptPanel || this is ProviderTabPanel

private fun replacePlaceholder(panels: MutableList<Panel>, placeholderName: String, panel: Panel?) {
    val idx = panels.indexOfFirst {
        (it.description == placeholderName || (panel != null && it.description == panel.description)) && !it.isAppended()
    }
    when {
        panel == null -> if (idx >= 0) panels.removeAt(idx)
        idx >= 0 -> panels[idx] = panel
        else -> {
            val scriptStart = panels.indexOfFirst { it.isAppended() }.takeIf { it >= 0 } ?: panels.size
            panels.add(scriptStart, panel)
        }
    }
}

private fun replacePlaceholders(panels: MutableList<Panel>, resolved: List<Panel>) {
    for (panel in resolved) {
        val idx = panels.indexOfFirst { it.description == panel.description && !it.isAppended() }
        if (idx >= 0) {
            panels[idx] = panel
        } else {
            // Grouped-tab promotion: placeholder "X" should match resolved "X: Y"
            val prefixIdx = panels.indexOfFirst {
                !it.isAppended() && panel.description.startsWith("${it.description}: ")
            }
            if (prefixIdx >= 0) {
                panels[prefixIdx] = panel
            } else {
                // Insert before script panels
                val scriptStart = panels.indexOfFirst { it.isAppended() }.takeIf { it >= 0 } ?: panels.size
                panels.add(scriptStart, panel)
            }
        }
    }
}

/**
 * Resolves the hardcoded (non-table-tab) panels for this provider.
 * These are typically cheap to create — they just wrap Composable lambdas
 * without triggering expensive analysis. Examples: Dominators, WAT, DWARF.
 */
private fun BinaryProvider.resolveHardcodedPanels(
    state: HexViewStateHolder,
    pathsNav: PathsNavigationState,
    editableDoc: EditableBinaryDocument? = null,
    watVirtualizedListState: LazyListState? = null,
    watVirtualizedFolded: MutableState<Set<ChunkId>>? = null,
    liveProviderProvider: (() -> BinaryProvider)? = null,
    wasmFunctionNavTargetFlow: MutableStateFlow<WasmFunctionNavTarget?>? = null,
    wasmByteOffsetNavFlow: MutableStateFlow<Int?>? = null,
    wasmTypeNavFlow: MutableStateFlow<Int?>? = null,
    wasmTypeGraphFocusFlow: MutableStateFlow<Int?>? = null,
    wasmTypeInfoNavFlow: MutableStateFlow<Int?>? = null,
    wasmUiProbeSink: ((String, String) -> Unit)? = null,
    classFileNavTargetFlow: MutableStateFlow<ClassFileNavTarget?>? = null,
    fileName: String? = null,
    onRevealApkDexClass: ((entry: ZipEntry, classDefIndex: Int) -> Unit)? = null,
    gutterMarksModelFlow: StateFlow<GutterMarksModel?>? = null,
    executionOffsetFlow: StateFlow<Long?>? = null,
    onToggleWasmBreakpoint: ((funcIdx: Int, byteOffset: Long, instrOrdinal: Int) -> Unit)? = null,
    onToggleNativeBreakpoint: ((va: Long) -> Unit)? = null,
    onDeleteBreakpoint: ((id: String) -> Unit)? = null,
): List<Panel> = when (this) {
    is ClassFileBinaryProvider -> {
        val parsed = this.parsedClassFile
        if (parsed != null) {
            classFilePanels(parsed, classFileNavTargetFlow ?: MutableStateFlow(null), state)
        } else {
            HexanaLog.LOG.warn("Failed to parse .class file ${fileName ?: "unknown"}")
            listOf(Panel("Parse Error") {
                Text(
                    "Failed to parse ${fileName ?: "the file"} as a JVM class file \u2014 see idea.log for details."
                )
            })
        }
    }
    is DexBinaryProvider -> dexSmaliPanels(this, state)
    is ZipBinaryProvider -> if (isApkFileName(fileName)) apkClassesPanels(this, state, onRevealApkDexClass) else emptyList()
    is GenericBinaryProvider -> emptyList()
    is ArchiveBinaryProvider -> emptyList()
    is ElfBinaryProvider -> {
        val elfFile = (this as? BinaryVirtualFileProvider)?.file
        val memoryPanel = if (isMemoryBudgetEligible(header, sections)) {
            listOf(Panel("Memory") { ElfMemoryBudgetPanel(this, state, elfFile, state.project) })
        } else emptyList()
        commonNativePanels(
            state, gutterMarksModelFlow, executionOffsetFlow, onToggleNativeBreakpoint, onDeleteBreakpoint,
        ) + memoryPanel
    }
    is PeBinaryProvider -> {
        val ilPanels = if (isManaged) clrIlPanels(this, state) else emptyList()
        ilPanels + commonNativePanels(
            state, gutterMarksModelFlow, executionOffsetFlow, onToggleNativeBreakpoint, onDeleteBreakpoint,
            includeDisassembly = !hasOnlyManagedCode,
        )
    }
    is NativeBinaryProvider -> commonNativePanels(
        state, gutterMarksModelFlow, executionOffsetFlow, onToggleNativeBreakpoint, onDeleteBreakpoint,
    )
    is WasmBinaryVirtualFileProvider -> {
        if (canProvideWasmModule()) {
            // Stable fallback flows for callers that don't wire cross-tab signaling.
            // Must be remembered inside composable lambdas; each lambda remembers
            // its own instance. Cross-tab signaling only works when the caller
            // supplies non-null values (BinaryEditorView always does).
            listOf(
                Panel("Dominators") { WasmPanelDominators(this, state, pathsNav) },
                Panel("Wat") { WasmPanelWat(this, state, wasmByteOffsetNavFlow ?: remember { MutableStateFlow(null) }) },
                Panel("WAT (virtualized)") {
                    val live = (liveProviderProvider?.invoke() as? WasmBinaryProvider) ?: this
                    val gutterModel by (gutterMarksModelFlow
                        ?: remember { MutableStateFlow(null) }).collectAsState()
                    val typeNavFlowActual = wasmTypeNavFlow ?: remember { MutableStateFlow<Int?>(null) }
                    val typeGraphFlowActual = wasmTypeGraphFocusFlow ?: remember { MutableStateFlow<Int?>(null) }
                    val typeInfoNavFlowActual = wasmTypeInfoNavFlow ?: remember { MutableStateFlow<Int?>(null) }
                    WasmPanelWatVirtualized(
                        provider = live,
                        state = state,
                        editableDoc = editableDoc,
                        listState = watVirtualizedListState,
                        foldedSectionsExternal = watVirtualizedFolded,
                        navTargetFlow = wasmFunctionNavTargetFlow
                            ?: remember { MutableStateFlow(null) },
                        byteOffsetNavFlow = wasmByteOffsetNavFlow
                            ?: remember { MutableStateFlow(null) },
                        typeNavFlow = typeNavFlowActual,
                        typeInfoNavFlow = typeInfoNavFlowActual,
                        uiProbeSink = wasmUiProbeSink,
                        gutterMarks = gutterModel?.marks,
                        executionOffset = executionOffsetFlow,
                        onToggleBreakpoint = onToggleWasmBreakpoint,
                        onDeleteBreakpoint = onDeleteBreakpoint,
                        onShowTypeGraph = { typeIdx ->
                            typeGraphFlowActual.value = typeIdx
                            state.requestSelectTabByDescription("Type Graph")
                        },
                    )
                },
                Panel("Type Graph") {
                    val live = (liveProviderProvider?.invoke() as? WasmBinaryProvider) ?: this
                    val typeGraphFlowActual = wasmTypeGraphFocusFlow ?: remember { MutableStateFlow<Int?>(null) }
                    val typeNavFlowActual = wasmTypeNavFlow ?: remember { MutableStateFlow<Int?>(null) }
                    WasmTypeGraphPanel(
                        provider = live,
                        focusTypeFlow = typeGraphFlowActual,
                        uiProbeSink = wasmUiProbeSink,
                        onNavigateToType = { typeIdx, _ ->
                            typeNavFlowActual.value = typeIdx
                            state.requestSelectTabByDescription("WAT (virtualized)")
                        },
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
                Panel("Dependencies") { WasmComponentDependencyGraph(this, state, file) },
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
                    val gutterModel by (gutterMarksModelFlow
                        ?: remember { MutableStateFlow(null) }).collectAsState()
                    val typeNavFlowActual = wasmTypeNavFlow ?: remember { MutableStateFlow<Int?>(null) }
                    val typeGraphFlowActual = wasmTypeGraphFocusFlow ?: remember { MutableStateFlow<Int?>(null) }
                    val typeInfoNavFlowActual = wasmTypeInfoNavFlow ?: remember { MutableStateFlow<Int?>(null) }
                    WasmPanelWatVirtualized(
                        provider = live,
                        state = state,
                        editableDoc = editableDoc,
                        listState = watVirtualizedListState,
                        foldedSectionsExternal = watVirtualizedFolded,
                        navTargetFlow = wasmFunctionNavTargetFlow
                            ?: remember { MutableStateFlow(null) },
                        typeNavFlow = typeNavFlowActual,
                        typeInfoNavFlow = typeInfoNavFlowActual,
                        uiProbeSink = wasmUiProbeSink,
                        gutterMarks = gutterModel?.marks,
                        executionOffset = executionOffsetFlow,
                        onToggleBreakpoint = onToggleWasmBreakpoint,
                        onDeleteBreakpoint = onDeleteBreakpoint,
                        onShowTypeGraph = { typeIdx ->
                            typeGraphFlowActual.value = typeIdx
                            state.requestSelectTabByDescription("Type Graph")
                        },
                    )
                },
                Panel("Type Graph") {
                    val live = (liveProviderProvider?.invoke() as? WasmBinaryProvider) ?: this
                    val typeGraphFlowActual = wasmTypeGraphFocusFlow ?: remember { MutableStateFlow<Int?>(null) }
                    val typeNavFlowActual = wasmTypeNavFlow ?: remember { MutableStateFlow<Int?>(null) }
                    WasmTypeGraphPanel(
                        provider = live,
                        focusTypeFlow = typeGraphFlowActual,
                        uiProbeSink = wasmUiProbeSink,
                        onNavigateToType = { typeIdx, _ ->
                            typeNavFlowActual.value = typeIdx
                            state.requestSelectTabByDescription("WAT (virtualized)")
                        },
                    )
                },
            ) + dwarfPanels(this, state)
        } else emptyList()
    }
    is WasmComponentBinaryProvider -> {
        if (canProvideWasmModule()) {
            listOf(
                Panel("Dependencies") {
                    WasmComponentDependencyGraph(this, state, (this as? BinaryVirtualFileProvider)?.file)
                },
                Panel("WAT (virtualized)") { WasmComponentPanelWatVirtualized(this, state) },
            )
        } else emptyList()
    }
    else -> emptyList()
}

/**
 * Resolves a single ordinary [TableTabDescriptor] into a [Panel].
 * Called per-tab on a background thread so each tab replaces its
 * placeholder independently.
 */
private fun BinaryProvider.resolveOrdinaryTabPanel(
    tab: TableTabDescriptor,
    state: HexViewStateHolder,
    archiveName: String? = null,
    onOpenZipEntry: ((ZipEntry) -> Unit)? = null,
    onOpenTarEntry: ((TarEntry) -> Unit)? = null,
): Panel {
    return if (tab.name == "Entries" && onOpenZipEntry != null && this is ZipBinaryProvider) {
        enrichZipEntriesTab(tab, onOpenZipEntry).toPanel(state)
    } else if (tab.name == "Entries" && onOpenTarEntry != null && this is TarBinaryProvider) {
        enrichTarEntriesTab(tab, onOpenTarEntry).toPanel(state)
    } else if (tab.name == "Members" && this is ArchiveBinaryProvider) {
        enrichArchiveMembersTab(tab, state.project, archiveName).toPanel(state)
    } else if (tab.name == "Packets" && this is PcapBinaryProvider) {
        enrichPcapPacketsTab(tab, state.project).toPanel(state)
    } else {
        tab.toPanel(state)
    }
}

/**
 * Resolves a single [GroupedTableTabDescriptor] into a [Panel],
 * enriching Garbage tabs with Dominators cross-navigation when applicable.
 */
private fun BinaryProvider.resolveGroupedTabPanel(
    g: GroupedTableTabDescriptor,
    state: HexViewStateHolder,
    pathsNav: PathsNavigationState,
): Panel? {
    return if (g.name == "Garbage" && this is WasmBinaryProvider) {
        val garbageEntries = this.garbageAnalysis?.entries ?: emptyList()
        if (garbageEntries.isNotEmpty()) {
            enrichGarbageGroupedTab(
                g, garbageEntries,
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
            g.toPanel(state)
        }
    } else {
        g.toPanel(state)
    }
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
        predefinedFilters = tab.predefinedFilters,
    )
}

/**
 * Enriches the TAR "Entries" tab descriptor with a row-click action that
 * opens the clicked entry via [onOpenEntry]. The entry list is obtained
 * from the [TarBinaryProvider]'s cached [TarBinaryProvider.displayEntries].
 */
private fun TarBinaryProvider.enrichTarEntriesTab(
    tab: TableTabDescriptor,
    onOpenEntry: (TarEntry) -> Unit,
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
        predefinedFilters = tab.predefinedFilters,
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

/**
 * Enriches the PCAP "Packets" tab with a context-menu action that extracts
 * the HTTP response body from a packet and opens it in a new editor tab.
 *
 * Binary content recognised by Hexana (Wasm, ELF, etc.) opens in the hex
 * editor; text content opens in a plain IntelliJ text editor.
 */
private fun PcapBinaryProvider.enrichPcapPacketsTab(
    tab: TableTabDescriptor,
    project: Project,
): TableTabDescriptor {
    val provider = this
    val pcap = provider.parsedPcapFile ?: return tab
    val packets = pcap.packets
    val linkType = pcap.header.network
    val scope = HexanaService.getInstance().coroutineScope

    // Lazily dissect packets (same cache pattern as PcapTableTabs)
    val dissectedCache = HashMap<Int, PcapDissectedPacket>()
    fun dissected(index: Int): PcapDissectedPacket = dissectedCache.getOrPut(index) {
        try {
            PcapDissector.dissect(provider.bytes, packets[index], linkType)
        } catch (_: Exception) {
            PcapDissectedPacket("", "", "?", "")
        }
    }

    // Decrypted plaintext was never in the file, so the file-offset path above
    // cannot reach an HTTPS body. The shared tab hands the record's plaintext
    // over through its row detail hook - the same bytes the "Decrypted TLS"
    // panel shows - and the body is split out of that instead.
    fun decryptedHttpBody(index: Int): PlaintextHttpBody? {
        val plaintext = tab.rowDetailProvider?.invoke(index)?.bytes ?: return null
        return PcapDissector.httpBodyInPlaintext(plaintext)
    }

    // isVisible runs on every menu build, so the answer is cached; the body
    // itself is not, to keep decrypted copies of rows nobody opens off the heap.
    val hasDecryptedBodyCache = HashMap<Int, Boolean>()
    fun hasDecryptedHttpBody(index: Int): Boolean =
        hasDecryptedBodyCache.getOrPut(index) { decryptedHttpBody(index) != null }

    fun hasCleartextHttpBody(d: PcapDissectedPacket): Boolean =
        d.httpBodyFileOffset >= 0 && d.httpBodySize > 0

    val openBodyItem = RowContextMenuItemDescriptor(
        labelProvider = { "Open HTTP Body" },
        action = { index ->
            val d = dissected(index)
            val fromFile = hasCleartextHttpBody(d)
            if (!fromFile && !hasDecryptedHttpBody(index)) return@RowContextMenuItemDescriptor
            scope.launch(Dispatchers.IO) {
                try {
                    val body = if (fromFile) {
                        val raw = PcapDissector.reassembleHttpBody(
                            provider.bytes, packets, index, linkType, d,
                        ) ?: return@launch
                        PlaintextHttpBody(raw, d.httpContentType, d.httpContentEncoding)
                    } else {
                        decryptedHttpBody(index) ?: return@launch
                    }
                    val bodyBytes = decompressHttpBody(body.bytes, body.contentEncoding)

                    val ext = httpContentTypeToExtension(body.contentType)
                    val fileName = "http-body-packet-${index + 1}$ext"
                    val dir = Files.createTempDirectory(
                        Path.of(PathManager.getTempPath()),
                        "hexana-pcap-",
                    ).also { it.toFile().deleteOnExit() }
                    val path = dir.resolve(fileName)
                    Files.write(path, bodyBytes)
                    path.toFile().deleteOnExit()

                    val vf = VfsUtil.findFile(path, true) ?: return@launch
                    withContext(Dispatchers.EDT) {
                        if (project.isDisposed) return@withContext
                        FileEditorManager.getInstance(project).openFile(vf, true)
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    HexanaLog.LOG.warn("Failed to extract HTTP body from packet ${index + 1}", t)
                }
            }
        },
        isVisible = { index ->
            hasCleartextHttpBody(dissected(index)) || hasDecryptedHttpBody(index)
        },
    )

    fun decryptedPlaintext(index: Int): ByteArray? = tab.rowDetailProvider?.invoke(index)?.bytes

    val isReplayableCache = HashMap<Int, Boolean>()
    fun isReplayable(index: Int): Boolean = isReplayableCache.getOrPut(index) {
        try {
            HttpReplay.mayHoldRequest(dissected(index)) { decryptedPlaintext(index) }
        } catch (_: Exception) {
            false
        }
    }

    fun replayItem(label: String, open: suspend (CapturedHttpRequest) -> Unit) =
        RowContextMenuItemDescriptor(
            labelProvider = { label },
            action = { index ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val captured = HttpReplay.capturedRequest(provider.bytes, packets, index, linkType) {
                            decryptedPlaintext(index)
                        } ?: return@launch
                        open(captured)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        HexanaLog.LOG.warn("Failed to rebuild the HTTP request in packet ${index + 1}", t)
                    }
                }
            },
            isVisible = { index -> isReplayable(index) },
        )

    val replayItems = buildList {
        add(replayItem("Replay HTTP Request…") { captured ->
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed) HttpReplayDialog(project, captured).show()
            }
        })
        if (HttpClientReplayExport.isAvailable) {
            add(replayItem("Open Request in HTTP Client") { captured ->
                HttpClientReplayExport.open(project, captured, captured.request)
            })
        }
    }

    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        defaultSortColumn = tab.defaultSortColumn,
        defaultSortAscending = tab.defaultSortAscending,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowAction = tab.rowAction,
        contextMenuItems = tab.contextMenuItems + openBodyItem + replayItems,
        rowClickAction = tab.rowClickAction,
        rowDoubleClickAction = tab.rowDoubleClickAction,
        dynamicContextMenuProvider = tab.dynamicContextMenuProvider,
        prologueText = tab.prologueText,
        emptyMessage = tab.emptyMessage,
        childrenProvider = tab.childrenProvider,
        alwaysShowSearch = tab.alwaysShowSearch,
        searchPlaceholder = tab.searchPlaceholder,
        showIdColumn = tab.showIdColumn,
        priority = tab.priority,
        predefinedFilters = tab.predefinedFilters,
        // Rebuilt field by field, so anything the shared tab set has to be
        // carried over by hand - the decrypted-plaintext panel included.
        rowDetailProvider = tab.rowDetailProvider,
    )
}

/**
 * Decompresses an HTTP body according to the Content-Encoding header.
 * Supports gzip, deflate (raw deflate and zlib-wrapped), and x-gzip.
 * Returns the original bytes unchanged when the encoding is `identity`,
 * absent, or unrecognised. At most [maxBytes] are produced, so a
 * compression bomb cannot fill the heap.
 */
internal fun decompressHttpBody(body: ByteArray, contentEncoding: String?, maxBytes: Int = Int.MAX_VALUE): ByteArray {
    if (body.isEmpty()) return body
    val encoding = contentEncoding?.trim()?.lowercase() ?: return body
    return when (encoding) {
        "gzip", "x-gzip" -> {
            try {
                java.util.zip.GZIPInputStream(body.inputStream()).use { it.readNBytes(maxBytes) }
            } catch (_: Exception) {
                body
            }
        }
        "deflate" -> {
            // RFC 7230: "deflate" can be raw deflate or zlib-wrapped.
            // Try zlib (Inflater with nowrap=false) first, fall back to raw deflate.
            try {
                val inflater = java.util.zip.Inflater(false)
                try {
                    inflater.setInput(body)
                    val out = java.io.ByteArrayOutputStream(minOf(body.size.toLong() * 2, maxBytes.toLong()).toInt())
                    val buf = ByteArray(8192)
                    while (!inflater.finished() && out.size() < maxBytes) {
                        val n = inflater.inflate(buf)
                        if (n == 0 && inflater.needsInput()) break
                        out.write(buf, 0, minOf(n, maxBytes - out.size()))
                    }
                    out.toByteArray()
                } finally {
                    inflater.end()
                }
            } catch (_: Exception) {
                // Fall back to raw deflate (nowrap=true)
                try {
                    val inflater = java.util.zip.Inflater(true)
                    try {
                        inflater.setInput(body)
                        val out = java.io.ByteArrayOutputStream(minOf(body.size.toLong() * 2, maxBytes.toLong()).toInt())
                        val buf = ByteArray(8192)
                        while (!inflater.finished() && out.size() < maxBytes) {
                            val n = inflater.inflate(buf)
                            if (n == 0 && inflater.needsInput()) break
                            out.write(buf, 0, minOf(n, maxBytes - out.size()))
                        }
                        out.toByteArray()
                    } finally {
                        inflater.end()
                    }
                } catch (_: Exception) {
                    body
                }
            }
        }
        "identity" -> body
        else -> body // br, zstd, etc. — not supported, return raw
    }
}

/** Maps an HTTP Content-Type to a file extension hint for the temp file. */
private fun httpContentTypeToExtension(contentType: String?): String {
    if (contentType == null) return ""
    val mime = contentType.substringBefore(';').trim().lowercase()
    return when {
        mime == "application/json" -> ".json"
        mime == "application/xml" || mime == "text/xml" -> ".xml"
        mime == "text/html" -> ".html"
        mime == "text/css" -> ".css"
        mime == "text/javascript" || mime == "application/javascript" -> ".js"
        mime == "text/plain" -> ".txt"
        mime == "application/octet-stream" -> ".bin"
        mime == "application/pdf" -> ".pdf"
        mime == "image/png" -> ".png"
        mime == "image/jpeg" -> ".jpg"
        mime == "image/gif" -> ".gif"
        mime == "image/svg+xml" -> ".svg"
        mime == "application/wasm" -> ".wasm"
        mime == "application/zip" -> ".zip"
        mime == "application/gzip" -> ".gz"
        // Textual application/* subtypes — open as text, not hex.
        mime == "application/xhtml+xml" -> ".xhtml"
        mime == "application/soap+xml" -> ".xml"
        mime == "application/rss+xml" -> ".xml"
        mime == "application/atom+xml" -> ".xml"
        mime == "application/yaml" || mime == "application/x-yaml" -> ".yaml"
        mime == "application/graphql" -> ".graphql"
        mime == "application/ld+json" -> ".jsonld"
        mime == "application/x-www-form-urlencoded" -> ".txt"
        mime == "application/sql" -> ".sql"
        mime == "application/rtf" -> ".rtf"
        mime == "application/csv" -> ".csv"
        mime == "application/x-sh" || mime == "application/x-csh" -> ".sh"
        mime == "application/typescript" -> ".ts"
        mime.endsWith("+xml") -> ".xml"
        mime.endsWith("+json") -> ".json"
        mime.startsWith("text/") -> ".txt"
        mime.startsWith("application/") -> ".bin"
        else -> ".bin"
    }
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

/**
 * Cheaply discovers the tab names that [resolvePanels] will likely produce,
 * without performing expensive parsing or analysis. Used to show tab headers
 * immediately with loading indicators while the full content resolves on a
 * background thread.
 */
private fun BinaryProvider.discoverPanelNames(fileName: String): List<String> {
    val providers = TableTabsProviderRegistry.providers
    val context = TableTabsContext()

    // Collect table-tab names from providers that support cheap discovery.
    // For providers that return null (unknown), we skip them — their tabs
    // will appear when the full resolution completes.
    data class NameEntry(val name: String, val priority: TabPriority, val providerIndex: Int)
    val nameEntries = mutableListOf<NameEntry>()
    for ((index, p) in providers.withIndex()) {
        val available = p.availableTabNames(this, context)
        if (available != null) {
            val (ordinary, grouped) = available
            ordinary.forEach { nameEntries.add(NameEntry(it, p.tabPriority(it, this), index)) }
            grouped.forEach { nameEntries.add(NameEntry(it, p.tabPriority(it, this), index)) }
        } else {
            // Fall back to full computation for cheap providers
            val tabs = p.tableTabs(this, context)
            tabs.forEach { nameEntries.add(NameEntry(it.name, it.priority, index)) }
            val grouped = p.groupedTabs(this, context)
            grouped.forEach { g ->
                if (g.innerTabs.isNotEmpty() || g.emptyMessage != null) {
                    // Match the naming logic in GroupedTableTabDescriptor.toPanel
                    val displayName = if (g.innerTabs.size == 1) "${g.name}: ${g.innerTabs.single().name}" else g.name
                    nameEntries.add(NameEntry(displayName, g.priority, index))
                }
            }
        }
    }
    nameEntries.sortWith(compareBy<NameEntry> { it.priority }.thenBy { it.providerIndex })
    val (lowEntries, normalEntries) = nameEntries.partition { it.priority == TabPriority.Low }

    // Hardcoded provider panel names (non-table panels like Dominators, WAT, etc.)
    val providerPanelNames = discoverHardcodedPanelNames(fileName)

    return normalEntries.map { it.name } + providerPanelNames + lowEntries.map { it.name }
}

/**
 * Returns the names of hardcoded (non-table-tab) panels that this provider
 * will produce, based on cheap type checks only.
 */
private fun BinaryProvider.discoverHardcodedPanelNames(fileName: String): List<String> = when (this) {
    is ClassFileBinaryProvider -> listOf("Header", "Methods")
    is DexBinaryProvider -> listOf(DEX_SMALI_TAB_NAME)
    is ZipBinaryProvider -> if (isApkFileName(fileName)) listOf(APK_CLASSES_TAB_NAME) else emptyList()
    is GenericBinaryProvider -> emptyList()
    is ArchiveBinaryProvider -> emptyList()
    is ElfBinaryProvider -> {
        val base = listOf("Disassembly", "Disassembly (virtualized)")
        if (isMemoryBudgetEligible(header, sections)) base + "Memory" else base
    }
    is PeBinaryProvider -> buildList {
        // Both checks read only the CLI header; the IL panel decodes metadata when it is opened.
        if (isManaged) add(CLR_IL_TAB_NAME)
        if (!hasOnlyManagedCode) addAll(listOf("Disassembly", "Disassembly (virtualized)"))
    }
    is NativeBinaryProvider -> {
        // SBOM depends on nativeImageInfo which is lazy/expensive — skip it in discovery.
        // The SBOM tab will appear when the full resolution completes.
        listOf("Disassembly", "Disassembly (virtualized)")
    }
    is WasmBinaryVirtualFileProvider -> {
        if (canProvideWasmModule()) {
            buildList {
                add("Dominators")
                add("Wat")
                add("WAT (virtualized)")
                add("Type Graph")
                discoverDwarfPanelNames()?.let { addAll(it) }
            }
        } else listOf("Something is wrong :(", "Wat")
    }
    is WasmComponentBinaryVirtualFileProvider -> {
        if (canProvideWasmModule()) listOf("Dependencies", "Wat", "WAT (virtualized)")
        else listOf("Something is wrong :(", "Wat")
    }
    is WasmBinaryProvider -> {
        if (canProvideWasmModule()) {
            buildList {
                add("Dominators")
                add("WAT (virtualized)")
                add("Type Graph")
                discoverDwarfPanelNames()?.let { addAll(it) }
            }
        } else emptyList()
    }
    is WasmComponentBinaryProvider -> {
        if (canProvideWasmModule()) listOf("Dependencies", "WAT (virtualized)")
        else emptyList()
    }
    else -> emptyList()
}

/**
 * Cheaply discovers which DWARF panels will be available. Both the section list and the grouping
 * rule come from the same places [dwarfPanels] uses, so the placeholder tabs cannot drift from the
 * resolved ones. Never calls `roots()`, so no section is parsed to name a tab.
 */
private fun BinaryProvider.discoverDwarfPanelNames(): List<String>? =
    groupPanels(DWARF_GROUP_TAB_NAME, dwarfOutlineSectionsOf(this).map { Panel(it.name) {} })
        .map { it.description }
        .ifEmpty { null }

/**
 * A centered loading spinner shown inside tab content while the full
 * panel content is being resolved on a background thread.
 */
@Composable
internal fun LoadingTabContent(message: String = "Loading\u2026") {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text(message)
        }
    }
}
