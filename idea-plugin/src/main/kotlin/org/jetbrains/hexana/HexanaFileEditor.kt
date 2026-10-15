package org.jetbrains.hexana

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.breakpoints.BinaryBreakpointRegistry
import org.jetbrains.hexana.breakpoints.BinaryIdentityProvider
import org.jetbrains.hexana.breakpoints.BreakpointMaterializationCoordinator
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.DEX_CLASSES_TAB_NAME
import org.jetbrains.hexana.ui.panel.GutterMarksModel
import org.jetbrains.annotations.Nls
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.sourcemap.parseSourceMap
import org.jetbrains.hexana.sourcemap.sourceMapTextFromDataUrl
import org.jetbrains.hexana.ui.BinaryEditorView
import org.jetbrains.hexana.ui.CLASS_FILE_NAV_TARGET_KEY
import org.jetbrains.hexana.ui.ClassFileNavTarget
import org.jetbrains.hexana.ui.HexSearchField
import org.jetbrains.hexana.ui.WASM_FUNCTION_NAV_TARGET_KEY
import org.jetbrains.hexana.ui.WasmFunctionNavTarget
import org.jetbrains.hexana.ui.createComposePanel
import org.jetbrains.hexana.ui.panel.BinaryDiffEditorView
import org.jetbrains.hexana.ui.panel.BinaryDiffModel
import org.jetbrains.hexana.ui.panel.DiffLoadable
import org.jetbrains.hexana.disassembly.capstone.CapstoneWasmDisassembler
import org.jetbrains.hexana.disassembly.capstone.loadFromClasspath
import org.jetbrains.hexana.shared.diff.BinaryDiffHost
import org.jetbrains.hexana.shared.diff.BinaryDiffProviderRegistry
import org.jetbrains.hexana.shared.diff.DiffOpenRequest
import org.jetbrains.hexana.shared.diff.nativeSizeDiff
import org.jetbrains.hexana.ui.panel.Panel
import org.jetbrains.hexana.ui.panel.decompiledContentPanel
import org.jetbrains.hexana.ui.panel.decompiledEditorComposable
import org.jetbrains.hexana.ui.setupSearchActions
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import org.jetbrains.hexana.wat.LightWatFile
import org.jetbrains.hexana.wat.WatFileset
import org.jetbrains.hexana.wat.WatViewEditors
import com.intellij.ide.structureView.StructureViewBuilder
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.io.Serializable
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JPanel

class HexanaFileEditor(
    val myProject: Project,
    val myFile: VirtualFile
) : FileEditor, UserDataHolderBase() {

    companion object {
        val KEY = DataKey.create<HexanaFileEditor>("hexana_file_editor")
    }

    val panel: JComponent

    val stateHolder = HexViewStateHolder(myProject)

    private var myTextEditor: TextEditor? = null

    /** Path of the open file, or null when it has no file on disk. */
    private var binarySourcePath: String? = null

    private val myClosedTextEditors = CopyOnWriteArrayList<TextEditor>()

    /**
     * Pending bytecode-view nav request. Initialised from the file's UserData
     * (which is how callers communicate with a not-yet-open editor); subsequent
     * requests for an already-open editor are pushed in via [requestClassFileNav].
     */
    private val classFileNavTargetFlow: MutableStateFlow<ClassFileNavTarget?> =
        MutableStateFlow(myFile.getUserData(CLASS_FILE_NAV_TARGET_KEY)).also {
            myFile.putUserData(CLASS_FILE_NAV_TARGET_KEY, null)
        }

    /**
     * Drive the embedded bytecode view to a specific method + BCI. Safe to call
     * from any thread; the flow is consumed inside the Compose tree.
     */
    fun requestClassFileNav(target: ClassFileNavTarget) {
        classFileNavTargetFlow.value = target
    }

    /**
     * Pending WASM function navigation request. Seeded from the file's
     * [WASM_FUNCTION_NAV_TARGET_KEY] user-data on construction (one-shot
     * consume); subsequent requests for an already-open editor are pushed via
     * [requestWasmFunctionNav]. The flow is consumed inside [WasmPanelWatVirtualized]
     * and reset to null after each navigation so equal re-clicks still fire.
     */
    private val wasmFunctionNavTargetFlow: MutableStateFlow<WasmFunctionNavTarget?> =
        MutableStateFlow(myFile.getUserData(WASM_FUNCTION_NAV_TARGET_KEY)).also {
            myFile.putUserData(WASM_FUNCTION_NAV_TARGET_KEY, null)
        }

    /**
     * Drive the embedded WAT virtualized view to the body of function [target].
     * Safe to call from any thread; the flow is consumed inside the Compose tree.
     *
     * If the WAT (virtualized) panel is not currently visible the navigation is
     * deferred — the [LaunchedEffect] inside [WasmPanelWatVirtualized] fires
     * the moment the panel composes.
     */
    fun requestWasmFunctionNav(target: WasmFunctionNavTarget) {
        wasmFunctionNavTargetFlow.value = target
    }

    /**
     * Pending byte-offset navigation request for the WAT virtualized panel.
     * Structure-view navigation pushes a byte offset here; the panel resolves
     * it to the containing chunk and scrolls there.
     */
    private val wasmByteOffsetNavFlow: MutableStateFlow<Int?> = MutableStateFlow(null)

    /**
     * Drive the embedded WAT virtualized view to the chunk that contains
     * [byteOffset]. Safe to call from any thread; the flow is consumed
     * inside the Compose tree.
     */
    fun requestWasmByteOffsetNav(byteOffset: Int) {
        wasmByteOffsetNavFlow.value = byteOffset
    }

    /**
     * Pending type-definition navigation request (a type index) for the WAT
     * virtualized panel, mirroring [wasmByteOffsetNavFlow].
     */
    private val wasmTypeNavFlow: MutableStateFlow<Int?> = MutableStateFlow(null)

    /** Scroll the WAT virtualized view to the definition row of type [typeIdx]. */
    fun requestWasmTypeNav(typeIdx: Int) {
        wasmTypeNavFlow.value = typeIdx
    }

    /**
     * Pending Type Graph focus request (a type index). Consumed by the Type
     * Graph panel; selecting the "Type Graph" tab is a separate step.
     */
    private val wasmTypeGraphFocusFlow: MutableStateFlow<Int?> = MutableStateFlow(null)

    /** Focus the Type Graph panel on [typeIdx]. Safe to call from any thread. */
    fun requestWasmTypeGraphFocus(typeIdx: Int) {
        wasmTypeGraphFocusFlow.value = typeIdx
    }

    /**
     * Pending type-info popup request (a type index) for the WAT virtualized
     * panel. Used by UI-test capture drills to open the popup deterministically.
     */
    private val wasmTypeInfoNavFlow: MutableStateFlow<Int?> = MutableStateFlow(null)

    /** Open the WAT virtualized panel's type-info popup for [typeIdx]. */
    fun requestWasmTypeInfoPopup(typeIdx: Int) {
        wasmTypeInfoNavFlow.value = typeIdx
    }

    /**
     * UI-state breadcrumbs published by wasm panels (type graph build stats,
     * type navigation results, type-info popup data). Read by UI-test probes
     * to gate assertions instead of sleeping. Keys: "typeGraph",
     * "typeNavResult", "typeInfo".
     */
    val wasmUiProbeState = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val wasmUiProbeSink: (String, String) -> Unit = { key, value -> wasmUiProbeState[key] = value }

    /**
     * WASM-diff models, computed once off the EDT and held for the lifetime of this
     * editor (this tab). Switching the inner tabs — or the IDE editor tab, which may
     * tear down and rebuild the Compose composition — re-collects these flows rather
     * than recomputing; the diff is recomputed only when the tab is closed and a fresh
     * [HexanaFileEditor] is created on reopen. Scoped by [wasmDiffScope], cancelled in
     * [dispose]. Declared BEFORE [init] so they are initialised before [initPanel]
     * (called from `init`) launches into [wasmDiffScope] during construction.
     */
    private val wasmDiffScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Separate scope for identity resolution and gutter-model lifetime. Cancelled in [dispose]. */
    private val editorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Gutter model; null until identity is resolved (async, off-EDT). */
    val gutterMarksModelFlow: MutableStateFlow<GutterMarksModel?> = MutableStateFlow(null)

    /** Execution position in terms of byte offset (WASM) or VA (native). Driven by [BinaryExecutionNavigator]. */
    val executionOffsetFlow: MutableStateFlow<Long?> = MutableStateFlow(null)

    @Volatile var cachedIdentity: org.jetbrains.hexana.shared.BinaryIdentity? = null
        private set

    /** The open diff's editor-scoped state; null unless this editor hosts a [HexanaDiffVirtualFile]. */
    private var diffModel: BinaryDiffModel? = null

    init {
        panel = UiDataProvider.wrapComponent(JPanel(BorderLayout())) { sink ->
            sink[KEY] = this
        }
        // TODO: 🐛 if we will have the same code in init section without moving it to a separate method
        // all closure variables will be lost
        initPanel()
    }

    var binaryProvider: BinaryProvider? = null

    fun initPanel() {
        // A diff compares two binaries; it has no single-file hex/edit surface, so it
        // short-circuits the normal binary-editor wiring.
        val diffFile = myFile as? HexanaDiffVirtualFile
        if (diffFile != null) {
            initDiffPanel(diffFile)
            return
        }

        val inMemoryBuffer = myFile.getUserData(HexanaBinaryVirtualFile.BUFFER_KEY)
        val commonBuffer = if (inMemoryBuffer != null) {
            stateHolder.displayBaseOffset = myFile.getUserData(HexanaBinaryVirtualFile.BASE_OFFSET_KEY) ?: 0
            inMemoryBuffer
        } else {
            file.toCommonByteBuffer() ?: return
        }

        val ext = myFile.extension?.lowercase()

        // .jit files use JitFileEditorView (not BinaryEditorView), so they
        // short-circuit the normal binary-editor wiring.
        if (ext == "jit") {
            initJitFilePanel(commonBuffer)
            return
        }

        val sourcePath = if (inMemoryBuffer == null) myFile.toNioPathOrNull()?.toString() else null
        // Kept for the script tabs, which are built later in the Compose content:
        // `binary.path` must be null for an in-memory file exactly as it is here,
        // because there is no file for a script to reach a sibling of.
        binarySourcePath = sourcePath

        binaryProvider = resolveProvider(myFile.name, inMemoryBuffer, commonBuffer, sourcePath)

        // Compute binary identity and wire up the gutter model asynchronously.
        // In-memory buffers (HexanaBinaryVirtualFile) have no backing file to hash.
        val identityFile = if (inMemoryBuffer == null) myFile else null
        if (identityFile != null) {
            editorScope.launch {
                val elfBuildId = (binaryProvider as? ElfBinaryProvider)?.buildId
                val identity = BinaryIdentityProvider.identityFor(identityFile, elfBuildId)
                if (identity != null) {
                    cachedIdentity = identity
                    val registry = BinaryBreakpointRegistry.getInstance(myProject)
                    val coordinator = myProject.getService(BreakpointMaterializationCoordinator::class.java)
                    gutterMarksModelFlow.value = GutterMarksModel(
                        breakpointsFlow = registry.breakpoints,
                        sessionStatesFlow = coordinator.states,
                        identity = identity,
                        scope = editorScope,
                    )
                }
            }
        }

        val parentFile = myFile.getUserData(HexanaBinaryVirtualFile.PARENT_FILE_KEY)

        // Local files need no resolution and keep the synchronous path. For in-memory and
        // non-local files, resolveDecompilerFile() does disk I/O and a synchronous VFS
        // refresh, which SlowOperations forbids on the EDT (createEditor runs there), so
        // the resolve goes to the editor scope and the panel arrives via the flow.
        val decompPanelFlow = MutableStateFlow<Panel?>(null)
        if (myFile.toNioPathOrNull() != null && myFile !is LightVirtualFile) {
            decompPanelFlow.value = createDecompiledPanel(decompilerFile = myFile)
        } else {
            editorScope.launch {
                val decompilerFile = resolveDecompilerFile() ?: myFile
                withContext(Dispatchers.EDT) {
                    decompPanelFlow.value = createDecompiledPanel(decompilerFile)
                }
            }
        }

        val hexView = createComposePanel {
            val hexFocusRequester = remember { FocusRequester() }
            Column {
                if (stateHolder.showSearchDialog.value) {
                    HexSearchField(binaryProvider!!, stateHolder, hexFocusRequester)
                }
                val editor = this@HexanaFileEditor
                BinaryEditorView(
                    binaryProvider!!, stateHolder, hexFocusRequester,
                    fileName = myFile.name,
                    filePath = binarySourcePath,
                    parentFileName = parentFile?.name,
                    onNavigateToParent = parentFile?.let { pf ->
                        { FileEditorManager.getInstance(myProject).openFile(pf, true) }
                    },
                    wasmFunctionNavTargetFlow = wasmFunctionNavTargetFlow,
                    wasmByteOffsetNavFlow = wasmByteOffsetNavFlow,
                    wasmTypeNavFlow = wasmTypeNavFlow,
                    wasmTypeGraphFocusFlow = wasmTypeGraphFocusFlow,
                    wasmTypeInfoNavFlow = wasmTypeInfoNavFlow,
                    wasmUiProbeSink = wasmUiProbeSink,
                    classFileNavTargetFlow = classFileNavTargetFlow,
                    onOpenZipEntry = { entry -> openZipEntry(entry, commonBuffer) },
                    onRevealApkDexClass = { entry, classDefIndex ->
                        revealDexClassInZipEntry(entry, classDefIndex, commonBuffer)
                    },
                    onOpenTarEntry = { entry ->
                        val tarBuffer = (binaryProvider as? TarBinaryProvider)?.bytes ?: commonBuffer
                        openTarEntry(entry, tarBuffer)
                    },
                    decompiledContentPanelFlow = decompPanelFlow,
                    gutterMarksModelFlow = editor.gutterMarksModelFlow,
                    executionOffsetFlow = editor.executionOffsetFlow,
                    onToggleWasmBreakpoint = { funcIdx, byteOffset, instrOrdinal ->
                        val identity = editor.cachedIdentity ?: run {
                            org.jetbrains.hexana.HexanaLog.LOG.warn(
                                "hexana-bp: wasm gutter click IGNORED, identity not computed yet for ${myFile.path}"
                            )
                            return@BinaryEditorView
                        }
                        BinaryBreakpointRegistry.getInstance(myProject).toggle(
                            identity = identity,
                            anchor = BinaryAnchor.Wasm(funcIdx, byteOffset, null, instrOrdinal),
                            filePath = myFile.path,
                        )
                    },
                    onToggleNativeBreakpoint = { va ->
                        val identity = editor.cachedIdentity ?: run {
                            org.jetbrains.hexana.HexanaLog.LOG.warn(
                                "hexana-bp: native gutter click IGNORED, identity not computed yet for ${myFile.path}"
                            )
                            return@BinaryEditorView
                        }
                        BinaryBreakpointRegistry.getInstance(myProject).toggle(
                            identity = identity,
                            anchor = BinaryAnchor.Native(va.toULong(), null, 0uL),
                            filePath = myFile.path,
                        )
                    },
                    onDeleteBreakpoint = { id ->
                        BinaryBreakpointRegistry.getInstance(myProject).remove(id)
                    },
                )
            }
        }
        panel.add(hexView)

        setupSearchActions(hexView, stateHolder)
    }

    /**
     * If the file's type has a decompiler registered (either a
     * [BinaryFileTypeDecompilers] entry or a [com.intellij.psi.compiled.ClassFileDecompilers.Light]),
     * returns a [Panel] that embeds a read-only IntelliJ text editor showing the
     * decompiled source. Returns `null` when no decompiler is available.
     */
    private fun createDecompiledPanel(decompilerFile: VirtualFile): Panel? {
        val fileType = decompilerFile.fileType

        // 1. Try BinaryFileTypeDecompilers (simple binary types).
        //    Skip this path for .class files — the generic binary decompiler produces
        //    only stubs; step 2 below uses Fernflower for full decompilation.
        val ext = (decompilerFile.extension ?: myFile.extension)?.lowercase()
        val binaryDecompiler = if (ext != "class") BinaryFileTypeDecompilers.getInstance().forFileType(fileType) else null
        if (binaryDecompiler != null) {
            val decompiled = try {
                binaryDecompiler.decompile(decompilerFile)
            } catch (e: Exception) {
                HexanaLog.LOG.warn("Decompiler failed for ${myFile.name}", e)
                return null
            }
            val language = com.intellij.lang.LanguageUtil.getFileTypeLanguage(fileType)
            val lightFileName = decompiledFileName(myFile.nameWithoutExtension, myFile.extension, language)
            val lightFile = if (language != null) {
                LightVirtualFile(lightFileName, language, decompiled)
            } else {
                LightVirtualFile(lightFileName, decompiled)
            }
            return decompiledContentPanel(myProject, lightFile)
        }

        // 2. Try ClassFileDecompilers.Light (e.g. Fernflower for .class files).
        //    Only attempt this for .class files — other binary formats (JARs, ZIPs)
        //    should not show a "Decompiled" tab.
        if (ext != "class") return null

        // Return a lazy panel — the legal notice dialog and actual decompilation
        // are deferred until the user switches to the "Decompiled" tab.
        return createLazyClassDecompiledPanel(decompilerFile)
    }

    /**
     * Creates a [Panel] for `.class` file decompilation that defers the legal
     * notice dialog and actual Fernflower invocation until the tab is first
     * composed (i.e. the user switches to the "Decompiled" tab).
     */
    private fun createLazyClassDecompiledPanel(decompilerFile: VirtualFile): Panel =
        Panel("Decompiled") {
            val decompileState = remember { androidx.compose.runtime.mutableStateOf<LazyDecompileState>(LazyDecompileState.Initial) }

            when (val current = decompileState.value) {
                LazyDecompileState.Initial -> {
                    // First composition — check notice and decompile.
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        val props = com.intellij.ide.util.PropertiesComponent.getInstance()
                        val noticeAccepted = props.isValueSet("decompiler.legal.notice.accepted")
                        val noticeSuppressed = props.getBoolean("hexana.decompiler.legal.notice.suppressed", false)
                        if (!noticeAccepted && !noticeSuppressed) {
                            val accepted = withContext(Dispatchers.EDT) {
                                showFernflowerLegalNoticeDialog(decompilerFile)
                            }
                            if (!accepted) {
                                // Fall back to stub decompiler.
                                val fallback = BinaryFileTypeDecompilers.getInstance().forFileType(decompilerFile.fileType)
                                val stubText = try { fallback?.decompile(decompilerFile) } catch (_: Exception) { null }
                                decompileState.value = LazyDecompileState.NoticeRejected(stubText ?: "// Decompilation unavailable")
                                return@LaunchedEffect
                            }
                        }
                        val text = tryDecompileViaClassFileDecompiler(decompilerFile)
                        if (text != null) {
                            decompileState.value = LazyDecompileState.Decompiled(text)
                        } else {
                            // Fernflower unavailable — try fallback stub decompiler.
                            val fallback = BinaryFileTypeDecompilers.getInstance().forFileType(decompilerFile.fileType)
                            val fallbackText = try { fallback?.decompile(decompilerFile) } catch (_: Exception) { null }
                            if (fallbackText != null) {
                                decompileState.value = LazyDecompileState.Decompiled(fallbackText)
                            } else {
                                decompileState.value = LazyDecompileState.Failed
                            }
                        }
                    }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        org.jetbrains.jewel.ui.component.Text("Loading\u2026")
                    }
                }

                is LazyDecompileState.NoticeRejected -> {
                    val fileType = decompilerFile.fileType
                    val language = com.intellij.lang.Language.findLanguageByID("JAVA")
                        ?: com.intellij.lang.LanguageUtil.getFileTypeLanguage(fileType)
                    val lightFileName = decompiledFileName(myFile.nameWithoutExtension, myFile.extension, language)
                    val lightFile = remember(current.stubText) {
                        if (language != null) {
                            LightVirtualFile(lightFileName, language, current.stubText)
                        } else {
                            LightVirtualFile(lightFileName, current.stubText)
                        }
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(org.jetbrains.jewel.foundation.theme.JewelTheme.globalColors.outlines.warning)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            org.jetbrains.jewel.ui.component.Text(
                                "Showing stub decompilation — accept the Fernflower legal notice for full decompilation.",
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                org.jetbrains.jewel.ui.component.OutlinedButton(
                                    onClick = {
                                        com.intellij.ide.util.PropertiesComponent.getInstance()
                                            .setValue("hexana.decompiler.legal.notice.suppressed", true)
                                        decompileState.value = LazyDecompileState.NoticeSuppressed(current.stubText)
                                    },
                                ) {
                                    org.jetbrains.jewel.ui.component.Text("Don\u2019t show again")
                                }
                                org.jetbrains.jewel.ui.component.DefaultButton(
                                    onClick = {
                                        val accepted = showFernflowerLegalNoticeDialog(decompilerFile)
                                        if (accepted) {
                                            decompileState.value = LazyDecompileState.Initial
                                        }
                                    },
                                ) {
                                    org.jetbrains.jewel.ui.component.Text("Accept and Decompile")
                                }
                            }
                        }
                        decompiledEditorComposable(myProject, lightFile, decompilerFile)
                    }
                }

                is LazyDecompileState.NoticeSuppressed -> {
                    val fileType = decompilerFile.fileType
                    val language = com.intellij.lang.Language.findLanguageByID("JAVA")
                        ?: com.intellij.lang.LanguageUtil.getFileTypeLanguage(fileType)
                    val lightFileName = decompiledFileName(myFile.nameWithoutExtension, myFile.extension, language)
                    val lightFile = remember(current.stubText) {
                        if (language != null) {
                            LightVirtualFile(lightFileName, language, current.stubText)
                        } else {
                            LightVirtualFile(lightFileName, current.stubText)
                        }
                    }
                    decompiledEditorComposable(myProject, lightFile, decompilerFile)
                }

                is LazyDecompileState.Decompiled -> {
                    val fileType = decompilerFile.fileType
                    val language = com.intellij.lang.Language.findLanguageByID("JAVA")
                        ?: com.intellij.lang.LanguageUtil.getFileTypeLanguage(fileType)
                    val lightFileName = decompiledFileName(myFile.nameWithoutExtension, myFile.extension, language)
                    val lightFile = remember(current.text) {
                        if (language != null) {
                            LightVirtualFile(lightFileName, language, current.text)
                        } else {
                            LightVirtualFile(lightFileName, current.text)
                        }
                    }
                    decompiledEditorComposable(myProject, lightFile, decompilerFile)
                }

                LazyDecompileState.Failed -> {
                    org.jetbrains.jewel.ui.component.Text(
                        "Decompilation failed — see idea.log for details.",
                    )
                }
            }
        }

    /**
     * Shows the Fernflower legal notice dialog using the platform's
     * [com.intellij.ui.components.LegalNoticeDialog]. Returns `true` when the
     * user accepts, `false` otherwise (postponed or rejected).
     */
    private fun showFernflowerLegalNoticeDialog(file: VirtualFile): Boolean {
        val title = "JetBrains Decompiler - ${file.name}"
        val message = "<b>IMPORTANT: BY ACCESSING AND USING JETBRAINS DECOMPILER, YOU AGREE TO THE CERTAIN TERMS AND CONDITIONS " +
            "SET FORTH IN THE END-USER LICENSE AGREEMENT AND QUOTED BELOW. IF YOU DO NOT AGREE WITH THESE TERMS OR CONDITIONS, " +
            "DO NOT ACCESS OR USE JETBRAINS DECOMPILER.</b>" +
            "<br><br>" +
            "The Software includes decompiling functionality (\"JetBrains Decompiler\") that enables reproducing source code " +
            "from the original binary code. Licensee acknowledges that binary code and source code might be protected " +
            "by copyright and trademark laws. Before using JetBrains Decompiler, Licensee should make sure " +
            "that decompilation of binary code is not prohibited by the applicable license agreement " +
            "(except to the extent that Licensee may be expressly permitted under applicable law) " +
            "or that Licensee has obtained permission to decompile the binary code from the copyright owner." +
            "<br><br>" +
            "Using JetBrains Decompiler is entirely optional. Licensor does neither encourage nor condone " +
            "the use of JetBrains Decompiler, and disclaims any liability for Licensee's use of JetBrains Decompiler " +
            "in violation of applicable laws."
        return try {
            val result = com.intellij.ui.components.LegalNoticeDialog
                .build(title, message)
                .show()
            if (result == 0) {
                com.intellij.ide.util.PropertiesComponent.getInstance()
                    .setValue("decompiler.legal.notice.accepted", true)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            HexanaLog.LOG.warn("Failed to show Fernflower legal notice dialog", e)
            false
        }
    }

    /**
     * Resolves a [VirtualFile] suitable for decompilation when [myFile] is an
     * in-memory file (e.g. opened from a ZIP/JAR entry via [openZipEntry]) or
     * lives on a non-local file system.
     *
     * Prefers [com.intellij.openapi.vfs.JarFileSystem] resolution when the parent
     * archive is on disk — this gives decompilers like Fernflower access to sibling
     * classes in the same JAR, enabling full decompilation with method bodies
     * (not just signatures/stubs). Falls back to extracting bytes to a temporary
     * file when JarFileSystem resolution is not possible.
     *
     * Returns `null` when the file is already on the local file system (no
     * extraction needed) or when the bytes cannot be obtained.
     *
     * Must not run on the EDT: the temp-file fallback does disk I/O and a
     * synchronous VFS refresh ([com.intellij.openapi.vfs.LocalFileSystem.refreshAndFindFileByNioFile]),
     * which `SlowOperations` forbids there.
     */
    private fun resolveDecompilerFile(): VirtualFile? {
        // If the file is already on the local file system, no extraction needed.
        if (myFile.toNioPathOrNull() != null && myFile !is LightVirtualFile) return null

        // 1. Prefer JarFileSystem resolution when the parent archive is on disk.
        //    This gives decompilers like Fernflower access to sibling classes in the
        //    same JAR, enabling full decompilation (not just signatures/stubs).
        val parentFile = myFile.getUserData(HexanaBinaryVirtualFile.PARENT_FILE_KEY)
        if (parentFile != null) {
            val parentPath = parentFile.toNioPathOrNull()
            if (parentPath != null) {
                // ENTRY_PATH_KEY holds the full path inside the archive; myFile.name is only
                // the leaf name and misses entries in subdirectories (e.g. classes in packages).
                val entryPath = myFile.getUserData(HexanaBinaryVirtualFile.ENTRY_PATH_KEY) ?: myFile.name
                val jarFs = com.intellij.openapi.vfs.JarFileSystem.getInstance()
                val jarEntry = jarFs.findFileByPath("${parentPath}!/${entryPath}")
                if (jarEntry != null) return jarEntry
            }
        }

        // 2. Fallback: extract bytes to a temp file for decompilers that need a
        //    real file on disk (won't have classpath context, may produce stubs).
        val bytes: ByteArray = try {
            val inMemoryBuffer = myFile.getUserData(HexanaBinaryVirtualFile.BUFFER_KEY)
            if (inMemoryBuffer != null) {
                val size = inMemoryBuffer.size
                ByteArray(size) { inMemoryBuffer[it] }
            } else {
                myFile.contentsToByteArray()
            }
        } catch (e: Exception) {
            HexanaLog.LOG.warn("Cannot read bytes from ${myFile.name} for decompilation", e)
            return null
        }

        return try {
            val tempDir = java.nio.file.Files.createTempDirectory("hexana-decompile")
            val tempFile = tempDir.resolve(myFile.name)
            java.nio.file.Files.write(tempFile, bytes)
            tempFile.toFile().deleteOnExit()
            tempDir.toFile().deleteOnExit()
            com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(tempFile)
        } catch (e: Exception) {
            HexanaLog.LOG.warn("Cannot create temp file for decompilation of ${myFile.name}", e)
            null
        }
    }

    /**
     * Chooses the [BinaryProvider] this editor is built on.
     *
     * The format decision itself belongs to [resolveBinaryProviderKind], which every
     * Hexana host shares, so a format the provider layer supports shows the same tabs
     * in the IDE, in VS Code and in the browser. What stays here is the part that is
     * genuinely IntelliJ's: the "Open As" actions that force a provider outright, the
     * IDE file type registry as a last-resort hint ([resolveIdeBinaryProviderKind]),
     * gzip inflation
     * (which has to be stashed on the [VirtualFile] so the WAT view reads the inflated
     * bytes too), and the `VirtualFile`-backed provider subclasses the WAT side view
     * needs.
     *
     * [fileName] is the name the extension is taken from. It is a parameter rather
     * than `myFile.name` because the `.gz` branch recurses with the stripped name, so
     * `foo.parquet.gz` resolves by the inner `parquet`.
     */
    private fun resolveProvider(
        fileName: String,
        inMemoryBuffer: CommonByteBuffer?,
        commonBuffer: CommonByteBuffer,
        sourcePath: String?
    ): BinaryProvider {
        val ext = fileName.substringAfterLast('.', "").lowercase().ifEmpty { null }

        // "Open As Protobuf Message" is the user saying what this file is, so it beats
        // every other signal including the content — the whole point of the action is
        // to decode bytes that look like something else as a wire message. Matched on
        // the synthetic file the action creates rather than on the file *type*, which
        // a plain `.pb` on disk also carries and which belongs in [fileTypeKind] with
        // the other passive hints.
        if (myFile is HexanaProtobufMessageVirtualFile) {
            return ProtobufMessageBinaryProvider(commonBuffer)
        }

        if (ext == "tgz" || fileName.endsWith(".tar.gz", ignoreCase = true)) {
            val decompressed = decompressGzip(commonBuffer)
            return if (decompressed != null) {
                TarBinaryProvider(decompressed)
            } else {
                nativeOrGenericBinaryProvider(commonBuffer, sourcePath)
            }
        }

        // Generic gzip: decompress and resolve the inner content by the
        // stripped extension (e.g. "foo.wasm.gz" → ext "wasm").
        if (ext == "gz") {
            val decompressed = decompressGzip(commonBuffer)
                ?: return nativeOrGenericBinaryProvider(commonBuffer, sourcePath)
            // Store the decompressed bytes on the virtual file so that
            // VirtualFile-backed providers (WasmBinaryVirtualFileProvider etc.)
            // read the decompressed content instead of the raw gzip stream.
            myFile.putUserData(HexanaBinaryVirtualFile.BUFFER_KEY, decompressed)
            val innerName = fileName.removeSuffix(".gz").removeSuffix(".GZ")
            return resolveProvider(innerName, decompressed, decompressed, sourcePath)
        }

        val ref = AtomicReference(HexanaSourceLocationProvider.EMPTY)

        val kind = resolveIdeBinaryProviderKind(commonBuffer, fileName, myFile.fileType)

        return when (kind) {
            BinaryProviderKind.Wasm -> {
                object : WasmBinaryVirtualFileProvider(myFile) {
                    override suspend fun createTextEditor(file: VirtualFile): TextEditor {
                        return withContext(Dispatchers.EDT) {
                            disposeClosedEditors()
                            myTextEditor?.let {
                                myClosedTextEditors.add(it)
                            }
                            val textEditor =
                                TextEditorProvider.getInstance().createEditor(myProject, file)
                            textEditor as TextEditor
                            myTextEditor = textEditor
                            // Lets navigation land in this editor instead of opening the in-memory
                            // WAT document as an editor of its own. Registered here because this is
                            // the single funnel every WAT view goes through, split or not.
                            (file as? LightWatFile)?.let { WatViewEditors.register(it, textEditor.editor) }
                            stateHolder.setEditor(textEditor.editor)
                            textEditor
                        }
                    }

                    // DWARF first (cached — the provider builds its line-row
                    // table lazily), then the async-loaded `.map` sidecar in
                    // [ref]. Falling back per QUERY (not per null-module)
                    // matters: Kotlin/Wasm modules parse fine but carry no
                    // DWARF, and previously never reached the source map.
                    private val dwarfSourceLocations: HexanaSourceLocationProvider? by lazy {
                        wasmModule?.let {
                            DwarfSourceLocationProvider(
                                bytes,
                                WasmDwarfAccessor(it),
                                addressBase = it.codeSection?.contentOffset?.toLong() ?: 0L,
                            )
                        }
                    }

                    override val sourceLocationProvider: HexanaSourceLocationProvider
                        get() = object : HexanaSourceLocationProvider {
                            override fun getSourceLocationForOffset(byteOffset: Int): HexanaSourceLocation? =
                                dwarfSourceLocations?.getSourceLocationForOffset(byteOffset)
                                    ?: ref.get().getSourceLocationForOffset(byteOffset)

                            override fun getFirstSourceLocationInRange(startOffset: Int, endOffset: Int): HexanaSourceLocation? =
                                dwarfSourceLocations?.getFirstSourceLocationInRange(startOffset, endOffset)
                                    ?: ref.get().getFirstSourceLocationInRange(startOffset, endOffset)
                        }
                }.also { initSourceLocationProvider(ref, it) }
            }

            BinaryProviderKind.WasmComponent -> {
                object : WasmComponentBinaryVirtualFileProvider(myFile) {
                    override suspend fun createTextEditor(file: VirtualFile): TextEditor {
                        return withContext(Dispatchers.EDT) {
                            disposeClosedEditors()
                            myTextEditor?.let {
                                myClosedTextEditors.add(it)
                            }
                            val textEditor =
                                TextEditorProvider.getInstance().createEditor(myProject, file)
                            textEditor as TextEditor
                            myTextEditor = textEditor
                            // Lets navigation land in this editor instead of opening the in-memory
                            // WAT document as an editor of its own. Registered here because this is
                            // the single funnel every WAT view goes through, split or not.
                            (file as? LightWatFile)?.let { WatViewEditors.register(it, textEditor.editor) }
                            stateHolder.setEditor(textEditor.editor)
                            textEditor
                        }
                    }

                    override val sourceLocationProvider: HexanaSourceLocationProvider
                        get() = ref.get()
                }.also { initSourceLocationProvider(ref, null) }
            }

            else -> {
                val provider = createBinaryProvider(kind, commonBuffer, sourcePath)
                // A file nothing recognized keeps its VirtualFile: the description bar's
                // reveal-in-files action and the structure view read it back off the
                // provider, and a plain GenericBinaryProvider has nowhere to carry one.
                // In-memory buffers (a nested module sliced out of a parent) are excluded
                // — there is no file of their own to point at.
                if (inMemoryBuffer == null && provider.javaClass == GenericBinaryProvider::class.java) {
                    GenericBinaryVirtualFileProvider(myFile)
                } else {
                    provider
                }
            }
        }
    }



    private fun initDiffPanel(file: HexanaDiffVirtualFile) {
        val provider = BinaryDiffProviderRegistry.forInput(file.input)
        if (provider == null) {
            panel.add(createComposePanel { org.jetbrains.jewel.ui.component.Text("These two binaries are not the same kind and cannot be compared.") })
            return
        }
        // Every tab is computed once, off the EDT, into editor-scoped flows that outlive
        // any tab switch; recomputed only on close + reopen (a fresh editor).
        // Capstone decodes native functions for the per-function diff; loaded on first use.
        val host = BinaryDiffHost(nativeDisassembler = { CapstoneWasmDisassembler.loadFromClasspath() })
        val model = BinaryDiffModel(provider.session(file.input, host), wasmDiffScope, ::openDiffRequest)
        diffModel = model
        panel.add(createComposePanel { BinaryDiffEditorView(model, stateHolder) })
    }

    internal fun nativeDiffStateForUiTest(): NativeDiffUiTestState {
        val input = (myFile as? HexanaDiffVirtualFile)?.input
            ?: return NativeDiffUiTestState(status = "error", error = "Not a diff editor")
        val sizeTab = diffModel?.let { m -> m.session.tabs.indexOfFirst { it.name == "Size Impact" }.takeIf { it >= 0 }?.let(m.tabs::get) }
        if (sizeTab?.value == DiffLoadable.Loading) {
            return NativeDiffUiTestState(status = "loading")
        }
        val diff = nativeSizeDiff(input)
            ?: return NativeDiffUiTestState(status = "error", error = "Size analysis is not available for one or both binaries.")
        return NativeDiffUiTestState(
            status = "ready",
            changedRowCount = diff.rows.count { it.classification != org.jetbrains.hexana.wasm.analysis.diff.DiffClass.IDENTICAL },
            sections = diff.rows.map { "${it.classification}:${it.name}" },
        )
    }

    /**
     * Opens what a diff row asked for in its own tab: another diff (a pair of submodules,
     * routed back through the registry) or a single binary with nothing to compare against
     * (a one-sided submodule), shown as its structure in a [HexanaBinaryVirtualFile].
     */
    private fun openDiffRequest(request: DiffOpenRequest) {
        wasmDiffScope.launch {
            // Materializing the request copies the submodule bytes; do it here, off the EDT.
            val child: VirtualFile = when (request) {
                is DiffOpenRequest.Diff -> HexanaDiffVirtualFile(request.title, request.input())
                is DiffOpenRequest.Single -> HexanaBinaryVirtualFile(request.title, request.bytes())
            }
            withContext(Dispatchers.EDT) {
                FileEditorManager.getInstance(myProject).openFile(child, true)
            }
        }
    }

    override fun getComponent(): JComponent {
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return component
    }

    override fun getName(): @Nls(capitalization = Nls.Capitalization.Title) String {
        return "Hex"
    }

    override fun setState(state: FileEditorState) {
        if (state is HexViewState) {
            stateHolder.currentState.value = state

            val virtualFile = myTextEditor?.editor?.virtualFile
            val fileset = virtualFile?.getUserData(WatFileset.KEY)
            if (fileset != null) {
                val f = fileset.files.getOrNull(state.fileIndex)
                if (f != null) {
                    fileset.navigateToFile(f, state.fileOffset, true)
                }
            }
            else {
                state.watFileEditorState?.let {
                    myTextEditor?.setState(it)
                }
            }
        }
    }

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        val editor = myTextEditor?.editor
        if (editor != null) {
            val file = editor.virtualFile
            val fileset = file?.getUserData(WatFileset.KEY)
            if (fileset != null) {
                val fileIndex = fileset?.files?.indexOf(file) ?: 0
                val fileOffset = editor.caretModel.offset
                return stateHolder.currentState.value.copy(
                    fileIndex = fileIndex,
                    fileOffset = fileOffset,
                )
            }
            else {
                val watEditorState = myTextEditor?.getState(level)
                return stateHolder.currentState.value.copy(
                    watFileEditorState = watEditorState,
                )
            }
        }

        return stateHolder.currentState.value
    }

    override fun isModified(): Boolean {
        return false
    }

    override fun isValid(): Boolean {
        return true
    }

    override fun addPropertyChangeListener(p0: PropertyChangeListener) {

    }

    override fun removePropertyChangeListener(p0: PropertyChangeListener) {

    }

    override fun getFile(): VirtualFile {
        return myFile
    }

    override fun dispose() {
        wasmDiffScope.cancel()
        editorScope.cancel()
        disposeClosedEditors()
        myTextEditor?.let {
            Disposer.dispose(it)
        }
    }

    override fun getStructureViewBuilder(): StructureViewBuilder? =
        org.jetbrains.hexana.ui.hexanaStructureViewBuilder(this)

    private fun disposeClosedEditors() {
        myClosedTextEditors.forEach {
            Disposer.dispose(it)
        }
        myClosedTextEditors.clear()
    }


    private fun initJitFilePanel(commonBuffer: CommonByteBuffer) {
        val jit = try {
            org.jetbrains.hexana.jvm.jit.JitFile.parse(commonBuffer.duplicate().position(0))
        } catch (t: Throwable) {
            HexanaLog.LOG.warn("Failed to parse ${myFile.name} as a JIT dump", t)
            null
        }
        if (jit != null) {
            captureJitDumpOpened(jit)
        }
        // Global hex view: one provider for the entire `.jit` file. The
        // editor scrolls it to the selected sym's fileOffset and adds a
        // sym-range highlight; the sym's native bytes are then visible
        // alongside the rest of the dump's structure.
        binaryProvider = GenericBinaryProvider(commonBuffer.duplicate().position(0))
        val provider = binaryProvider!!
        val view = createComposePanel {
            val hexFocusRequester = remember { FocusRequester() }
            if (jit == null) {
                org.jetbrains.jewel.ui.component.Text(
                    "Failed to parse ${myFile.name} as a JIT dump — see idea.log for details.",
                )
            } else {
                org.jetbrains.hexana.jit.JitFileEditorView(
                    project = myProject,
                    jit = jit,
                    binaryProvider = provider,
                    state = stateHolder,
                    hexFocusRequester = hexFocusRequester,
                )
            }
        }
        panel.add(view)
    }

    /**
     * Fires `jit_dump_opened` analytics. Runs symbol enumeration on the
     * IDE pooled thread because [org.jetbrains.hexana.jvm.jit.JitFile.symbols]
     * walks the entire sym chain on first access — fine on a background
     * thread, but worth keeping off the editor-construction EDT path.
     */
    private fun captureJitDumpOpened(jit: org.jetbrains.hexana.jvm.jit.JitFile) {
        val service = HexanaService.getInstance()
        val sizeBytes = myFile.length
        val version = jit.header.version.toInt()
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val count = try {
                jit.symbols.size
            } catch (t: Throwable) {
                HexanaLog.LOG.warn(
                    "Failed to enumerate symbols in ${myFile.name} for jit_dump_opened analytics",
                    t,
                )
                null
            }
            service.captureAnalytics(
                org.jetbrains.hexana.analytics.HexanaEvent.JitDumpOpened(
                    fileSizeBytes = sizeBytes,
                    surface = org.jetbrains.hexana.analytics.HexanaEvent.Surface.IDE_PLUGIN,
                    version = version,
                    compiledMethodCount = count,
                )
            )
        }
    }


    /**
     * Opens the DEX [entry] of this APK in a new editor tab, with class [classDefIndex] (an
     * index into its `class_def_item`s) selected on that editor's Classes tab.
     */
    private fun revealDexClassInZipEntry(
        entry: org.jetbrains.hexana.zip.ZipEntry,
        classDefIndex: Int,
        parentBuffer: CommonByteBuffer,
    ) {
        openZipEntry(entry, parentBuffer) { editors ->
            editors.firstNotNullOfOrNull { it as? HexanaFileEditor }
                ?.stateHolder
                ?.requestSelectTableRow(DEX_CLASSES_TAB_NAME, classDefIndex)
        }
    }

    /** The open editor file showing entry [entryPath] of this archive, if there is one. */
    private fun findOpenZipEntryFile(entryPath: String): VirtualFile? =
        FileEditorManager.getInstance(myProject).openFiles.firstOrNull { file ->
            file is HexanaBinaryVirtualFile &&
                file.parentFile == myFile &&
                file.getUserData(HexanaBinaryVirtualFile.ENTRY_PATH_KEY) == entryPath
        }

    /**
     * Opens [entry] in an editor tab and hands the editors opened for it, if any, to [onOpened]
     * on the EDT. An entry that has to be inflated is inflated off the EDT first, so a large one
     * does not freeze the IDE; [onOpened] is then called later, and not at all if that fails.
     */
    private fun openZipEntry(
        entry: org.jetbrains.hexana.zip.ZipEntry,
        parentBuffer: CommonByteBuffer,
        onOpened: (List<FileEditor>) -> Unit = {},
    ) {
        val childName = entry.name.substringAfterLast('/').ifEmpty { entry.name }
        val editorManager = FileEditorManager.getInstance(myProject)

        if (isHexanaSupportedExtension(childName) || isUnsupportedByIntelliJ(childName)) {
            // Binary entries and files unknown to IntelliJ need the hex viewer — always use HexanaBinaryVirtualFile.
            // An entry already open from this archive is brought forward rather than opened twice.
            findOpenZipEntryFile(entry.name)?.let { open ->
                onOpened(editorManager.openFile(open, true).toList())
                return
            }
            inflateZipEntryThenOnEdt(entry, parentBuffer) { bytes ->
                // Checked again: a second request may have opened the entry while this one inflated.
                val childFile = findOpenZipEntryFile(entry.name) ?: HexanaBinaryVirtualFile(
                    name = childName,
                    buffer = bytes.toCommonByteBuffer(),
                    baseOffset = entry.dataOffset.toInt(),
                    parentFile = myFile,
                    entryPath = entry.name,
                )
                onOpened(editorManager.openFile(childFile, true).toList())
            }
            return
        }

        // For text entries, prefer JarFileSystem when the parent archive is a real on-disk file.
        val localPath = myFile.toNioPathOrNull()
        if (localPath != null) {
            val jarFs = com.intellij.openapi.vfs.JarFileSystem.getInstance()
            val entryFile = jarFs.findFileByPath("${myFile.path}!/${entry.name}")
            if (entryFile != null) {
                onOpened(editorManager.openFile(entryFile, true).toList())
                return
            }
        }

        // Fallback for in-memory parents (nested archives) — decompress and wrap manually.
        inflateZipEntryThenOnEdt(entry, parentBuffer) { bytes ->
            onOpened(editorManager.openFile(LightVirtualFile(childName, String(bytes)), true).toList())
        }
    }

    /** Inflates [entry] in the editor scope, off the EDT, then hands its bytes to [open] on the EDT. */
    private fun inflateZipEntryThenOnEdt(
        entry: org.jetbrains.hexana.zip.ZipEntry,
        parentBuffer: CommonByteBuffer,
        open: (ByteArray) -> Unit,
    ) {
        editorScope.launch {
            val bytes = decompressZipEntry(entry, parentBuffer)
            if (bytes == null) {
                HexanaLog.LOG.warn(
                    "Cannot open ${entry.name}: unsupported compression method ${entry.compressionMethod} or decode failure",
                )
                return@launch
            }
            withContext(Dispatchers.EDT) { open(bytes) }
        }
    }

    private fun openTarEntry(
        entry: org.jetbrains.hexana.tar.TarEntry,
        parentBuffer: CommonByteBuffer,
    ) {
        val childName = entry.name.substringAfterLast('/').ifEmpty { entry.name }
        val bytes = extractTarEntry(entry, parentBuffer) ?: return

        if (isHexanaSupportedExtension(childName) || isUnsupportedByIntelliJ(childName)) {
            val childFile = HexanaBinaryVirtualFile(
                name = childName,
                buffer = bytes.toCommonByteBuffer(),
                baseOffset = entry.dataOffset.toInt(),
                parentFile = myFile,
            )
            FileEditorManager.getInstance(myProject).openFile(childFile, true)
            return
        }

        FileEditorManager.getInstance(myProject).openFile(
            LightVirtualFile(childName, String(bytes)),
            true,
        )
    }
}

internal data class NativeDiffUiTestState(
    val status: String,
    val changedRowCount: Int = 0,
    val sections: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Attempts to decompile [file] using `ClassFileDecompilers.Light` (e.g. Fernflower
 * for `.class` files). Access is reflective to avoid a hard compile-time dependency
 * on the optional Java plugin.
 *
 * The caller must ensure that the Fernflower legal notice has been accepted
 * (`PropertiesComponent "decompiler.legal.notice.accepted"`) before calling this
 * function; otherwise Fernflower silently falls back to stubs.
 *
 * Returns the decompiled text, or `null` when the Java plugin is not loaded,
 * no decompiler accepts the file, or only a stub is produced.
 */
private fun tryDecompileViaClassFileDecompiler(file: VirtualFile): CharSequence? {
    // Use ClassFileDecompilers.Light directly (reflective to avoid hard dependency
    // on the optional Java plugin). This calls IdeaDecompiler.getText() which runs
    // the full Fernflower pipeline — unlike PsiManager.findFile().text which may
    // return only a stub during editor construction.
    //
    // IdeaDecompiler.getText() checks whether the user has accepted the Fernflower
    // legal notice (PropertiesComponent "decompiler.legal.notice.accepted"). The
    // caller (createDecompiledPanel) is responsible for showing the legal notice
    // dialog and setting the property before invoking this function.
    return try {
        val clsDecompilers = Class.forName("com.intellij.psi.compiled.ClassFileDecompilers")
        val getInstance = clsDecompilers.getMethod("getInstance")
        val instance = getInstance.invoke(null)
        val lightClass = Class.forName("com.intellij.psi.compiled.ClassFileDecompilers\$Light")
        val findMethod = clsDecompilers.getMethod("find", VirtualFile::class.java, Class::class.java)
        val decompiler = findMethod.invoke(instance, file, lightClass) ?: return null
        val getTextMethod = lightClass.getMethod("getText", VirtualFile::class.java)
        val text = getTextMethod.invoke(decompiler, file) as? CharSequence
        if (text.isNullOrBlank() || text.contains("Implementation of methods is not available")) null else text
    } catch (e: Exception) {
        HexanaLog.LOG.warn("ClassFileDecompiler lookup failed for ${file.name}", e)
        null
    }
}

private fun isHexanaSupportedExtension(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val hexanaExtensions = setOf("wasm", "class", "jar", "zip", "war", "apk", "jit", "dex")
    if (ext in hexanaExtensions) return true
    val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
        .getFileTypeByFileName(fileName)
    return fileType is BinaryFileType || fileType is NativeBinaryFileType
}

/**
 * Returns `true` when IntelliJ has no dedicated editor for [fileName] and the file
 * looks like a binary blob rather than a text file. Such files are better served by
 * Hexana's hex viewer than by a broken text editor.
 *
 * Files with unknown type (e.g. extensionless `AUTHORS`, `LICENSE`) are **not**
 * treated as unsupported — they are likely plain text and should be opened by the
 * platform's text editor, not the hex viewer.
 */
private fun isUnsupportedByIntelliJ(fileName: String): Boolean {
    val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
        .getFileTypeByFileName(fileName)
    return fileType is com.intellij.openapi.fileTypes.UnknownFileType || fileType.isBinary
            && BinaryFileTypeDecompilers.getInstance().forFileType(fileType) == null
}

/**
 * Decompresses a ZIP entry's data into a fresh ByteArray. Returns `null` for
 * unsupported compression methods (anything other than `stored` / `deflate`)
 * or on inflate failure.
 */
internal fun decompressZipEntry(
    entry: org.jetbrains.hexana.zip.ZipEntry,
    parentBuffer: CommonByteBuffer,
): ByteArray? {
    val dataStart = entry.dataOffset.toInt()
    return when (entry.compressionMethod) {
        0 -> {
            // Stored: direct slice of the parent buffer.
            val length = entry.uncompressedSize.toInt()
            ByteArray(length).also { for (i in 0 until length) it[i] = parentBuffer[dataStart + i] }
        }
        8 -> {
            try {
                val compressed = ByteArray(entry.compressedSize.toInt()).also {
                    for (i in 0 until it.size) it[i] = parentBuffer[dataStart + i]
                }
                val inflater = java.util.zip.Inflater(/* nowrap = */ true)
                inflater.setInput(compressed)
                val out = ByteArray(entry.uncompressedSize.toInt())
                var written = 0
                while (written < out.size && !inflater.finished()) {
                    val n = inflater.inflate(out, written, out.size - written)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    written += n
                }
                inflater.end()
                if (written == out.size) out else null
            } catch (t: Throwable) {
                HexanaLog.LOG.warn("Inflate failed for ${entry.name}", t)
                null
            }
        }
        else -> null
    }
}

/**
 * Extracts a TAR entry's data into a fresh ByteArray. TAR entries are stored
 * uncompressed, so this is a simple byte slice. Returns `null` on failure.
 */
private fun extractTarEntry(
    entry: org.jetbrains.hexana.tar.TarEntry,
    parentBuffer: CommonByteBuffer,
): ByteArray? = try {
    val dataStart = entry.dataOffset.toInt()
    val length = entry.size.toInt()
    ByteArray(length).also { for (i in 0 until length) it[i] = parentBuffer[dataStart + i] }
} catch (t: Throwable) {
    HexanaLog.LOG.warn("Failed to extract tar entry ${entry.name}", t)
    null
}

/**
 * The shared [resolveBinaryProviderKind] decision, refined with the one signal only the
 * IDE has.
 *
 * The shared resolution knows the bytes and the name. [fileType] additionally carries
 * what the *user* declared under Settings | Editor | File Types, where any pattern can
 * be bound to a registered type — someone whose pipeline writes `*.pq` can map it to
 * Parquet, and that file has to open as Parquet. It also carries whatever
 * [ColumnarBinaryFileTypeDetector] concluded, which matters for a file whose trailing
 * magic is missing because it was truncated or is still being written: exactly the file
 * someone reaches for a hex viewer to look at.
 *
 * The file type is consulted only once content and name have both come up empty, so a
 * declared association can never override a format the bytes themselves prove. It
 * covers the three types the name alone identifies; everything else the registry knows
 * (ELF, PE, pcap, tar, descriptor sets) is content-detected by
 * [nativeOrGenericBinaryProvider] anyway.
 */
internal fun resolveIdeBinaryProviderKind(
    bytes: CommonByteBuffer,
    fileName: String,
    fileType: FileType,
): BinaryProviderKind {
    val contentKind = resolveBinaryProviderKind(bytes, fileName)
    if (contentKind != BinaryProviderKind.NativeOrGeneric) return contentKind
    return when (fileType) {
        is ParquetFileType -> BinaryProviderKind.Parquet
        // Same rule the shared factory applies once a name has said "Arrow": the
        // continuation word is only trustworthy after something else already narrowed
        // the format down to Arrow.
        is ArrowFileType -> BinaryProviderKind.Arrow(
            if (isArrowStreamMagic(bytes.duplicate().position(0))) ArrowFormat.STREAM else ArrowFormat.FILE,
        )
        is ProtobufMessageFileType -> BinaryProviderKind.ProtobufMessage
        else -> contentKind
    }
}

/**
 * Hard cap on an inflated gzip payload, mirroring `MAX_SBOM_INFLATED_BYTES` in
 * `binaryProvider`'s SBOM extraction and `MAX_INFLATED_BYTES` in the VS Code host.
 *
 * A gzip stream declares nothing about its output size, so a few kilobytes on disk can
 * inflate to gigabytes — and this runs in the IDE's own heap. Past the cap the file is
 * shown as the compressed bytes it is rather than taking the IDE down with it.
 */
private const val MAX_INFLATED_BYTES = 128 * 1024 * 1024

/**
 * Decompresses a gzip-compressed [CommonByteBuffer] into a new buffer.
 * Returns `null` on failure (not gzip, corrupt data, or an inflated payload that would
 * exceed [MAX_INFLATED_BYTES]).
 */
private fun decompressGzip(buffer: CommonByteBuffer): CommonByteBuffer? = try {
    val size = buffer.size
    val compressed = ByteArray(size).also { for (i in 0 until size) it[i] = buffer[i] }
    val bais = java.io.ByteArrayInputStream(compressed)
    val gzis = java.util.zip.GZIPInputStream(bais)
    val baos = java.io.ByteArrayOutputStream(size * 2)
    val tmp = ByteArray(8192)
    var n: Int
    var inflated = 0L
    var overflowed = false
    while (gzis.read(tmp).also { n = it } >= 0) {
        inflated += n
        if (inflated > MAX_INFLATED_BYTES) {
            // Stop reading rather than finish and then reject: the point of the cap is
            // to never hold the oversized payload in the first place.
            overflowed = true
            break
        }
        baos.write(tmp, 0, n)
    }
    gzis.close()
    if (overflowed) {
        HexanaLog.LOG.warn("Gzip payload exceeds $MAX_INFLATED_BYTES bytes inflated; showing the compressed bytes")
        null
    } else {
        baos.toByteArray().toCommonByteBuffer()
    }
} catch (_: Throwable) {
    null
}

/**
 * Builds a file name with a proper extension for the decompiled [LightVirtualFile].
 * A correct extension is essential so that [EditorHighlighterFactory] can resolve
 * the right syntax highlighter by file name.
 */
private fun decompiledFileName(baseName: String, originalExtension: String?, language: com.intellij.lang.Language?): String {
    // Hardcoded mappings for well-known compiled → source extensions.
    val hardcoded = when (originalExtension?.lowercase()) {
        "class" -> "java"
        else -> null
    }
    if (hardcoded != null) return "$baseName.$hardcoded"
    if (language != null) {
        val ext = language.associatedFileType?.defaultExtension
        if (ext != null) return "$baseName.$ext"
    }
    return "$baseName.decompiled"
}

data class HexViewState(
    val topFraction: Float = 0.6f,
    val tabIndex: Int = 0,
    val fileIndex: Int = 0,
    val fileOffset: Int = 0,
    val watFileEditorState: FileEditorState? = null
) : FileEditorState, Serializable {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
        if (otherState is HexViewState) {

            val result = level == FileEditorStateLevel.NAVIGATION &&
                    topFraction == otherState.topFraction &&
                    tabIndex == otherState.tabIndex &&
                    fileIndex == otherState.fileIndex &&
                    fileOffset == otherState.fileOffset &&
                    (watFileEditorState == null && otherState.watFileEditorState == null ||
                            otherState.watFileEditorState != null && watFileEditorState?.canBeMergedWith(otherState.watFileEditorState, level) == true)
            return result

        }
        return false
    }
}


private fun HexanaFileEditor.initSourceLocationProvider(
    ref: AtomicReference<HexanaSourceLocationProvider>,
    provider: WasmBinaryProvider?,
) {
    HexanaService.getInstance().coroutineScope.launch(Dispatchers.IO) {
        val (sourceMapPath, sourceMapText) = loadSourceMapText(provider) ?: return@launch
        val sourceLocationProvider = parseSourceMap(sourceMapPath, sourceMapText)
        val resolvedPaths = hashSetOf<String>()
        for (path in sourceLocationProvider.sources) {
            try {
                val mappingPath = Path.of(path)
                val ioPath = if (mappingPath.isAbsolute) {
                    mappingPath
                } else {
                    sourceMapPath?.resolveSibling(path)
                }
                if (ioPath != null && VfsUtil.findFile(ioPath, true) != null) {
                    resolvedPaths.add(ioPath.toString())
                }
            } catch (_: InvalidPathException) {
            }
        }

        val filteringLocationProvider = object : HexanaSourceLocationProvider {
            override fun getSourceLocationForOffset(byteOffset: Int): HexanaSourceLocation? {
                val result = sourceLocationProvider.getSourceLocationForOffset(byteOffset)
                return result?.takeIf { it.filePath in resolvedPaths }
            }

            override fun getFirstSourceLocationInRange(startOffset: Int, endOffset: Int): HexanaSourceLocation? {
                val result = sourceLocationProvider.getFirstSourceLocationInRange(startOffset, endOffset)
                return result?.takeIf { it.filePath in resolvedPaths }
            }
        }
        ref.set(filteringLocationProvider)
    }
}

/**
 * Resolves the module's source-map text, in priority order:
 *
 * 1. The `sourceMappingURL` custom section, when [provider] carries one —
 *    either a `data:` URL with the inline map (Kotlin/Wasm dev builds) or a
 *    path resolved against the wasm file's directory (absolute paths
 *    accepted). Remote http(s) URLs are not fetched.
 * 2. The `<wasmfile>.map` sidecar convention, also the fallback when the
 *    section's target cannot be loaded.
 *
 * Returns the nio path used to resolve relative `sources` entries (the wasm
 * file itself for inline maps) plus the JSON text, or null when no map loads.
 */
private fun HexanaFileEditor.loadSourceMapText(provider: WasmBinaryProvider?): Pair<Path?, String>? {
    val url = provider?.sourceMappingUrl
    if (url != null) {
        sourceMapTextFromDataUrl(url)?.let { text ->
            return myFile.toNioPathOrNull() to text
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            val mapFile = myFile.parent?.findFileByRelativePath(url)
                ?: runCatching { Path.of(url) }.getOrNull()
                    ?.takeIf { it.isAbsolute }
                    ?.let { VfsUtil.findFile(it, true) }
            if (mapFile?.exists() == true) {
                return mapFile.toNioPathOrNull() to VfsUtil.loadText(mapFile)
            }
        }
    }
    val sidecar = myFile.parent?.findChild("${myFile.name}.map") ?: return null
    if (!sidecar.exists()) return null
    return sidecar.toNioPathOrNull() to VfsUtil.loadText(sidecar)
}

/**
 * Internal state machine for the lazy class-file decompilation panel.
 */
private sealed class LazyDecompileState {
    data object Initial : LazyDecompileState()
    data class NoticeRejected(val stubText: CharSequence) : LazyDecompileState()
    data class NoticeSuppressed(val stubText: CharSequence) : LazyDecompileState()
    data class Decompiled(val text: CharSequence) : LazyDecompileState()
    data object Failed : LazyDecompileState()
}
