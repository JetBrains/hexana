package org.jetbrains.hexana

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import java.awt.Font
import java.beans.PropertyChangeListener
import java.util.Random
import javax.swing.*


class HexanaFileEditor(
    val myProject: Project,
    val myFile: VirtualFile
): FileEditor, UserDataHolderBase() {
    val panel: JPanel

    init {
        panel = JPanel(BorderLayout())
        val textArea = JTextArea()
        textArea.isEditable = false
        textArea.setFont(Font(Font.MONOSPACED, Font.PLAIN, 14))

        val buf = myFile.contentsToByteArray();
        textArea.setDocument(HexanaDocument(buf))
        val scrollPane = JBScrollPane(textArea)
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(scrollPane, BorderLayout.CENTER)
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
