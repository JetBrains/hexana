package org.jetbrains.hexana

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
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
import org.jetbrains.hexana.ui.panel.SizeReady
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
                )
            }
        }
        panel.add(hexView)

        setupSearchActions(hexView, stateHolder)
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
        if (provider !is WasmBinaryProvider && provider !is ElfBinaryProvider) return null
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
