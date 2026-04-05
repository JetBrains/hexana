package org.jetbrains.hexana

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.sourcemap.parseSourceMap
import org.jetbrains.hexana.ui.BinaryEditorView
import org.jetbrains.hexana.ui.HexSearchField
import org.jetbrains.hexana.ui.createComposePanel
import org.jetbrains.hexana.ui.setupSearchActions
import org.jetbrains.hexana.wat.WatFileset
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
        val inMemoryBuffer = myFile.getUserData(HexanaBinaryVirtualFile.BUFFER_KEY)
        val commonBuffer = if (inMemoryBuffer != null) {
            stateHolder.displayBaseOffset = myFile.getUserData(HexanaBinaryVirtualFile.BASE_OFFSET_KEY) ?: 0
            inMemoryBuffer
        } else {
            file.toCommonByteBuffer() ?: return
        }

        binaryProvider = when (myFile.extension) {
            "wasm" -> {
                if (inMemoryBuffer != null) {
                    when (WasmBinaryProviderSelector.select(commonBuffer)) {
                        BinaryProviderType.Wasm -> WasmBinaryProvider(commonBuffer)
                        BinaryProviderType.WasmComponent -> WasmComponentBinaryProvider(commonBuffer)
                        BinaryProviderType.Generic -> GenericBinaryProvider(commonBuffer)
                    }
                } else {
                    val ref = AtomicReference(HexanaSourceLocationProvider.EMPTY)
                    initSourceLocationProvider(ref)

                    when (WasmBinaryProviderSelector.select(commonBuffer)) {
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
                                    get() = ref.get()
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

                        BinaryProviderType.Generic -> GenericBinaryProvider(commonBuffer)
                    }
                }
            }
            else -> GenericBinaryProvider(commonBuffer)
        }

        val hexView = createComposePanel {
            val hexFocusRequester = remember { FocusRequester() }
            Column {
                if (stateHolder.showSearchDialog.value) {
                    HexSearchField(binaryProvider!!, stateHolder, hexFocusRequester)
                }
                BinaryEditorView(binaryProvider!!, stateHolder, hexFocusRequester)
            }
        }
        panel.add(hexView)

        setupSearchActions(hexView, stateHolder)
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
        disposeClosedEditors()
        myTextEditor?.let {
            Disposer.dispose(it)
        }
    }

    private fun disposeClosedEditors() {
        myClosedTextEditors.forEach {
            Disposer.dispose(it)
        }
        myClosedTextEditors.clear()
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
