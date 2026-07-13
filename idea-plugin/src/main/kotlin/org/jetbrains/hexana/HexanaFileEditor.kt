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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.providers.format.ComponentDiffResult
import org.jetbrains.hexana.providers.format.SubmoduleKind
import org.jetbrains.hexana.providers.format.SubmodulePairing
import org.jetbrains.hexana.providers.format.SubmoduleRef
import org.jetbrains.hexana.providers.format.WasmComponentDiffOrchestrator
import org.jetbrains.hexana.providers.format.WasmEntityDiffOrchestrator
import org.jetbrains.hexana.providers.format.WasmEntityDiffResult
import org.jetbrains.hexana.providers.format.WasmSizeDiffOrchestrator
import org.jetbrains.hexana.sourcemap.parseSourceMap
import org.jetbrains.hexana.ui.BinaryEditorView
import org.jetbrains.hexana.ui.CLASS_FILE_NAV_TARGET_KEY
import org.jetbrains.hexana.ui.ClassFileNavTarget
import org.jetbrains.hexana.ui.HexSearchField
import org.jetbrains.hexana.ui.WASM_FUNCTION_NAV_TARGET_KEY
import org.jetbrains.hexana.ui.WasmFunctionNavTarget
import org.jetbrains.hexana.ui.createComposePanel
import org.jetbrains.hexana.ui.panel.DiffLoadable
import org.jetbrains.hexana.ui.panel.Panel
import org.jetbrains.hexana.ui.panel.SizeReady
import org.jetbrains.hexana.ui.panel.decompiledContentPanel
import org.jetbrains.hexana.ui.panel.decompiledEditorComposable
import org.jetbrains.hexana.ui.panel.WasmComponentDiffEditorView
import org.jetbrains.hexana.ui.panel.WasmDiffEditorView
import org.jetbrains.hexana.ui.panel.buildRows
import org.jetbrains.hexana.ui.setupSearchActions
import org.jetbrains.hexana.wasm.analysis.diff.summarize
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import org.jetbrains.hexana.wat.WatFileset
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
     * WASM-diff models, computed once off the EDT and held for the lifetime of this
     * editor (this tab). Switching the inner tabs — or the IDE editor tab, which may
     * tear down and rebuild the Compose composition — re-collects these flows rather
     * than recomputing; the diff is recomputed only when the tab is closed and a fresh
     * [HexanaFileEditor] is created on reopen. Scoped by [wasmDiffScope], cancelled in
     * [dispose]. Declared BEFORE [init] so they are initialised before [initPanel]
     * (called from `init`) launches into [wasmDiffScope] during construction.
     */
    private val wasmDiffScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wasmSizeDiffFlow = MutableStateFlow<DiffLoadable<SizeReady>>(DiffLoadable.Loading)
    private val wasmEntityDiffFlow = MutableStateFlow<DiffLoadable<WasmEntityDiffResult>>(DiffLoadable.Loading)
    private val wasmComponentDiffFlow = MutableStateFlow<DiffLoadable<ComponentDiffResult>>(DiffLoadable.Loading)

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
        // A WASM size-diff compares two modules; it has no single-file hex/edit
        // surface, so it short-circuits the normal binary-editor wiring.
        val diffFile = myFile as? HexanaWasmDiffVirtualFile
        if (diffFile != null) {
            initWasmDiffPanel(diffFile)
            return
        }

        // A Component Model diff compares the *submodules* of two components; like the
        // core diff it has no single-file hex surface, so it short-circuits too.
        val componentDiffFile = myFile as? HexanaComponentDiffVirtualFile
        if (componentDiffFile != null) {
            initComponentDiffPanel(componentDiffFile)
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

        binaryProvider = resolveProvider(ext, inMemoryBuffer, commonBuffer, sourcePath)

        val parentFile = myFile.getUserData(HexanaBinaryVirtualFile.PARENT_FILE_KEY)

        val decompPanel = createDecompiledPanel(decompilerFile = resolveDecompilerFile() ?: myFile)

        val hexView = createComposePanel {
            val hexFocusRequester = remember { FocusRequester() }
            Column {
                if (stateHolder.showSearchDialog.value) {
                    HexSearchField(binaryProvider!!, stateHolder, hexFocusRequester)
                }
                BinaryEditorView(
                    binaryProvider!!, stateHolder, hexFocusRequester,
                    fileName = myFile.name,
                    parentFileName = parentFile?.name,
                    onNavigateToParent = parentFile?.let { pf ->
                        { FileEditorManager.getInstance(myProject).openFile(pf, true) }
                    },
                    wasmFunctionNavTargetFlow = wasmFunctionNavTargetFlow,
                    classFileNavTargetFlow = classFileNavTargetFlow,
                    onOpenZipEntry = { entry -> openZipEntry(entry, commonBuffer) },
                    decompiledContentPanel = decompPanel,
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
                        decompiledEditorComposable(myProject, lightFile)
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
                    decompiledEditorComposable(myProject, lightFile)
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
                    decompiledEditorComposable(myProject, lightFile)
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
                val jarFs = com.intellij.openapi.vfs.JarFileSystem.getInstance()
                val jarEntry = jarFs.findFileByPath("${parentPath}!/${myFile.name}")
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

    private fun resolveProvider(
        ext: String?,
        inMemoryBuffer: CommonByteBuffer?,
        commonBuffer: CommonByteBuffer,
        sourcePath: String?
    ): BinaryProvider {
        if (myFile.fileType is ProtobufMessageFileType) {
            return ProtobufMessageBinaryProvider(commonBuffer)
        }

        if (ext == "parquet" || myFile.fileType is ParquetFileType || (ext == null && isParquetMagic(commonBuffer))) {
            return ParquetBinaryProvider(commonBuffer, sourcePath)
        }

        if (ext == "arrow" || myFile.fileType is ArrowFileType || (ext == null && isArrowFileMagic(commonBuffer))) {
            return ArrowBinaryProvider(commonBuffer, ArrowFormat.FILE, sourcePath)
        }

        if (ext != "wasm") {
            return nativeOrGenericBinaryProvider(commonBuffer, sourcePath)
        }

        if (inMemoryBuffer != null) {
            return when (WasmBinaryProviderSelector.select(commonBuffer)) {
                BinaryProviderType.Wasm -> WasmBinaryProvider(commonBuffer)
                BinaryProviderType.WasmComponent -> WasmComponentBinaryProvider(commonBuffer)
                BinaryProviderType.Generic -> nativeOrGenericBinaryProvider(commonBuffer, sourcePath)
            }
        }

        val ref = AtomicReference(HexanaSourceLocationProvider.EMPTY)
        initSourceLocationProvider(ref)

        return when (WasmBinaryProviderSelector.select(commonBuffer)) {
            BinaryProviderType.Wasm -> {
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
                            stateHolder.setEditor(textEditor.editor)
                            textEditor
                        }
                    }

                    override val sourceLocationProvider: HexanaSourceLocationProvider
                        get() {
                            return wasmModule?.let {
                                DwarfSourceLocationProvider(
                                    bytes,
                                    WasmDwarfAccessor(it)
                                )
                            } ?: ref.get()
                        }
                }
            }

            BinaryProviderType.WasmComponent -> {
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
                            stateHolder.setEditor(textEditor.editor)
                            textEditor
                        }
                    }

                    override val sourceLocationProvider: HexanaSourceLocationProvider
                        get() = ref.get()
                }
            }

            BinaryProviderType.Generic -> GenericBinaryVirtualFileProvider(myFile)
        }
    }


    private fun initWasmDiffPanel(file: HexanaWasmDiffVirtualFile) {
        // Compute both diffs once, off the EDT, into editor-scoped flows. These outlive
        // any tab switch and are recomputed only on close + reopen (a fresh editor).
        wasmDiffScope.launch {
            val diff = WasmSizeDiffOrchestrator.diff(
                WasmBinaryProvider(file.leftBuffer.duplicate()),
                WasmBinaryProvider(file.rightBuffer.duplicate()),
                file.leftSymbolMap,
                file.rightSymbolMap,
            )
            wasmSizeDiffFlow.value = if (diff == null) {
                DiffLoadable.Error(WASM_DIFF_PARSE_ERROR)
            } else {
                DiffLoadable.Ready(SizeReady(diff.summarize(), buildRows(diff)))
            }
        }
        wasmDiffScope.launch {
            val result = WasmEntityDiffOrchestrator.diff(
                WasmBinaryProvider(file.leftBuffer.duplicate()),
                WasmBinaryProvider(file.rightBuffer.duplicate()),
                file.leftSymbolMap,
                file.rightSymbolMap,
            )
            wasmEntityDiffFlow.value = if (result == null) {
                DiffLoadable.Error(WASM_DIFF_PARSE_ERROR)
            } else {
                DiffLoadable.Ready(result)
            }
        }

        val view = createComposePanel {
            WasmDiffEditorView(
                leftBuffer = file.leftBuffer,
                rightBuffer = file.rightBuffer,
                leftName = file.leftName,
                rightName = file.rightName,
                sizeModelFlow = wasmSizeDiffFlow,
                entityModelFlow = wasmEntityDiffFlow,
                leftSymbolMap = file.leftSymbolMap,
                rightSymbolMap = file.rightSymbolMap,
            )
        }
        panel.add(view)
    }

    private fun initComponentDiffPanel(file: HexanaComponentDiffVirtualFile) {
        // Cheap submodule enumeration + pairing, computed once off the EDT into an
        // editor-scoped flow. The expensive per-submodule diff is deferred to a click.
        wasmDiffScope.launch {
            val result = WasmComponentDiffOrchestrator.diff(
                WasmComponentBinaryProvider(file.leftBuffer.duplicate()),
                WasmComponentBinaryProvider(file.rightBuffer.duplicate()),
            )
            wasmComponentDiffFlow.value = if (result == null) {
                DiffLoadable.Error(WASM_DIFF_PARSE_ERROR)
            } else {
                DiffLoadable.Ready(result)
            }
        }

        val view = createComposePanel {
            WasmComponentDiffEditorView(
                leftName = file.leftName,
                rightName = file.rightName,
                modelFlow = wasmComponentDiffFlow,
                onOpenSubmodule = { pairing -> openSubmoduleDiff(file, pairing) },
            )
        }
        panel.add(view)
    }

    /**
     * Opens the clicked submodule pair in its own tab. The two submodule byte ranges are
     * sliced from the parent component buffers and *materialized* (so the child tab's
     * lifetime is independent of this one), then routed by kind: a core-module pair reuses
     * the existing [HexanaWasmDiffVirtualFile] size/entity/WAT diff, a nested-component pair
     * recurses into another [HexanaComponentDiffVirtualFile], and a one-sided submodule
     * opens that single side's structure in a [HexanaBinaryVirtualFile].
     *
     * Slicing/materialization runs off the EDT; only [FileEditorManager.openFile] is on it.
     */
    private fun openSubmoduleDiff(parent: HexanaComponentDiffVirtualFile, pairing: SubmodulePairing) {
        wasmDiffScope.launch {
            val child = buildSubmoduleDiffFile(parent, pairing) ?: return@launch
            withContext(Dispatchers.EDT) {
                FileEditorManager.getInstance(myProject).openFile(child, true)
            }
        }
    }

    private fun buildSubmoduleDiffFile(
        parent: HexanaComponentDiffVirtualFile,
        pairing: SubmodulePairing,
    ): VirtualFile? {
        val left = pairing.left
        val right = pairing.right
        return when {
            left != null && right != null -> {
                val leftBuffer = materializeSubmodule(parent.leftBuffer, left)
                val rightBuffer = materializeSubmodule(parent.rightBuffer, right)
                val leftName = "${parent.leftName}∷${left.label}"
                val rightName = "${parent.rightName}∷${right.label}"
                val name = "$leftName ↔ $rightName"
                when (pairing.kind) {
                    // A pair always shares its kind (pairing is within-kind), so the kind is
                    // authoritative — no need to re-sniff the slice.
                    SubmoduleKind.COMPONENT -> HexanaComponentDiffVirtualFile(
                        name, leftBuffer, rightBuffer, leftName, rightName,
                        parent.leftSymbolMap, parent.rightSymbolMap,
                    )

                    SubmoduleKind.CORE_MODULE -> HexanaWasmDiffVirtualFile(
                        name, leftBuffer, rightBuffer, leftName, rightName,
                        parent.leftSymbolMap, parent.rightSymbolMap,
                    )
                }
            }

            left != null -> openSingleSubmodule(parent.leftBuffer, left, parent.leftName)
            right != null -> openSingleSubmodule(parent.rightBuffer, right, parent.rightName)
            else -> null
        }
    }

    /** A one-sided (added/removed) submodule has nothing to diff against — show its structure. */
    private fun openSingleSubmodule(buffer: CommonByteBuffer, ref: SubmoduleRef, parentName: String): VirtualFile =
        HexanaBinaryVirtualFile("$parentName∷${ref.label}", materializeSubmodule(buffer, ref))

    private fun materializeSubmodule(parentBuffer: CommonByteBuffer, ref: SubmoduleRef): CommonByteBuffer =
        parentBuffer.slice(ref.contentOffset, ref.contentSize).toByteArray().toCommonByteBuffer()

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
        disposeClosedEditors()
        myTextEditor?.let {
            Disposer.dispose(it)
        }
    }

    override fun getStructureViewBuilder(): StructureViewBuilder? {
        val provider = binaryProvider ?: return null
        if (provider !is WasmBinaryProvider && provider !is WasmComponentBinaryProvider && provider !is ElfBinaryProvider) return null
        return org.jetbrains.hexana.ui.HexanaTreeStructureViewBuilder(this, provider)
    }

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


    private fun openZipEntry(
        entry: org.jetbrains.hexana.zip.ZipEntry,
        parentBuffer: CommonByteBuffer,
    ) {
        val childName = entry.name.substringAfterLast('/').ifEmpty { entry.name }

        if (isHexanaSupportedExtension(childName) || isUnsupportedByIntelliJ(childName)) {
            // Binary entries and files unknown to IntelliJ need the hex viewer — always use HexanaBinaryVirtualFile.
            val bytes = decompressZipEntry(entry, parentBuffer)
            if (bytes == null) {
                HexanaLog.LOG.warn(
                    "Cannot open ${entry.name}: unsupported compression method ${entry.compressionMethod} or decode failure",
                )
                return
            }
            val childFile = HexanaBinaryVirtualFile(
                name = childName,
                buffer = bytes.toCommonByteBuffer(),
                baseOffset = entry.dataOffset.toInt(),
                parentFile = myFile,
            )
            FileEditorManager.getInstance(myProject).openFile(childFile, true)
            return
        }

        // For text entries, prefer JarFileSystem when the parent archive is a real on-disk file.
        val localPath = myFile.toNioPathOrNull()
        if (localPath != null) {
            val jarFs = com.intellij.openapi.vfs.JarFileSystem.getInstance()
            val entryFile = jarFs.findFileByPath("${myFile.path}!/${entry.name}")
            if (entryFile != null) {
                FileEditorManager.getInstance(myProject).openFile(entryFile, true)
                return
            }
        }

        // Fallback for in-memory parents (nested archives) — decompress and wrap manually.
        val bytes = decompressZipEntry(entry, parentBuffer)
        if (bytes == null) {
            HexanaLog.LOG.warn(
                "Cannot open ${entry.name}: unsupported compression method ${entry.compressionMethod} or decode failure",
            )
            return
        }
        FileEditorManager.getInstance(myProject).openFile(
            LightVirtualFile(childName, String(bytes)),
            true,
        )
    }
}

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

private const val WASM_DIFF_PARSE_ERROR =
    "Could not analyze one or both modules (not a core WASM module?)."

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
private fun decompressZipEntry(
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


private fun HexanaFileEditor.initSourceLocationProvider(ref: AtomicReference<HexanaSourceLocationProvider>) {
    HexanaService.getInstance().coroutineScope.launch(Dispatchers.IO) {
        val sourceMap = myFile.parent?.findChild("${myFile.name}.map")
        if (sourceMap?.exists() != true) {
            return@launch
        }
        val sourceMapPath = sourceMap.toNioPathOrNull()
        val sourceLocationProvider = parseSourceMap(sourceMapPath, VfsUtil.loadText(sourceMap))
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

        }
        ref.set(filteringLocationProvider)
    }
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
