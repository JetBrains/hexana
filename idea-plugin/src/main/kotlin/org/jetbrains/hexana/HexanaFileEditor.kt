package org.jetbrains.hexana

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.Nls
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.createComposePanel
import org.jetbrains.hexana.ui.createHexEditView
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.io.Serializable
import javax.swing.JComponent
import javax.swing.JPanel

class HexanaFileEditor(
    val myProject: Project,
    val myFile: VirtualFile
) : FileEditor, UserDataHolderBase() {
    val panel: JPanel

    val additionalViews = HexanaFileViewProvider.getAllViews(myProject, file)

    //private var hexViewState = HexViewState(0.6f)
    private val stateHolder = HexViewStateHolder()

    init {
        panel = JPanel(BorderLayout())
        // TODO: 🐛 if we will have the same code in init section without moving it to a separate method
        // all closure variables will be lost
        initPanel()
    }

    fun initPanel() {
        val file = WasmFsUtil.getLocalFile(myFile) ?: myFile

        val buf = file.contentsToByteArray()
        val binaryProvider = when (file.extension) {
            "wasm" -> WasmBinaryProvider(buf)
            else -> GenericBinaryProvider(buf)
        }

        val hexView = createComposePanel {
            createHexEditView(binaryProvider, stateHolder)
        }
        panel.add(hexView)
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
        }
    }

    override fun getState(level: FileEditorStateLevel): FileEditorState {
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
        additionalViews.forEach {
            it.runCatching {
                Disposer.dispose(it)
            }
        }
    }
}

data class HexViewState(
    val topFraction: Float = 0.6f
) : FileEditorState, Serializable {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
        return false
    }
}

class HexViewStateHolder {
    var currentState: MutableState<HexViewState> = mutableStateOf(HexViewState())

    fun setTopFraction(topFraction: Float) {
        currentState.value = currentState.value.copy(topFraction = topFraction)
    }
}
