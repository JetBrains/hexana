package org.jetbrains.hexana

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.Nls
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class HexanaFileEditor(
    val myProject: Project,
    val myFile: VirtualFile
): FileEditor, UserDataHolderBase() {
    val panel: JPanel

    init {
        panel = JPanel()
        panel.add(JLabel("WASM"))
    }

    override fun getComponent(): JComponent {
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return component
    }

    override fun getName(): @Nls(capitalization = Nls.Capitalization.Title) String {
        return "WASM"
    }

    override fun setState(state: FileEditorState) {

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

    }
}
