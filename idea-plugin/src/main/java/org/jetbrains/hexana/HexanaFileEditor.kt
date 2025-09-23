package org.jetbrains.hexana

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.AncestorListenerAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Rectangle
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.event.AncestorEvent
import javax.swing.text.DefaultHighlighter


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

        val file = WasmFsUtil.getLocalFile(myFile) ?: myFile
        val buf = file.contentsToByteArray();
        val doc = HexanaDocument(buf)
        textArea.setDocument(doc)
        val scrollPane = JBScrollPane(textArea)
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        val range = WasmFsUtil.getRange(myFile)
        if (range != null && range.first < Int.MAX_VALUE && range.last < Int.MAX_VALUE) {
            val highlighter = textArea.highlighter
            val color = JBColor.LIGHT_GRAY
            val textRange = doc.byteRangeToTextRange(IntRange(range.first.toInt(), range.last.toInt()))
            highlighter?.addHighlight(textRange.first, textRange.last, DefaultHighlighter.DefaultHighlightPainter(color))
            textArea.addAncestorListener(object: AncestorListenerAdapter() {
                override fun ancestorAdded(event: AncestorEvent?) {
                    ApplicationManager.getApplication().invokeLater {
                        val rect = textArea.modelToView2D(textRange.first)
                        if (rect != null) {
                            val scrolledToTopRect = Rectangle(
                                rect.x.toInt(),
                                rect.y.toInt() + (scrollPane.height * 0.9).toInt(),
                                rect.width.toInt(),
                                rect.height.toInt())
                            textArea.scrollRectToVisible(scrolledToTopRect.bounds)
                        }
                    }
                }
            })
        }

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
