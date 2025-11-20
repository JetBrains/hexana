package org.jetbrains.hexana

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.ui.createComposePanel
import com.intellij.ui.components.JBTabbedPane
import org.jetbrains.annotations.Nls
import org.jetbrains.hexana.ui.createHexEditView
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

class HexanaFileEditor(
    val myProject: Project,
    val myFile: VirtualFile
) : FileEditor, UserDataHolderBase() {
    val panel: JPanel

    val additionalViews = HexanaFileViewProvider.getAllViews(myProject, file)

    init {
        panel = JPanel(BorderLayout())
        // TODO: 🐛 if we will have the same code in init section without moving it to a separate method
        // all closure variables will be lost
        initPanel()
    }

    fun initPanel() {
        val file = WasmFsUtil.getLocalFile(myFile) ?: myFile

        val buf = file.contentsToByteArray()
        val hexView = createComposePanel {
            createHexEditView(buf)
        }
        panel.add(hexView)

//        if (additionalViews.isEmpty()) {
//            panel.add(hexView)
//        } else {
//            val splitter = Splitter(true, 0.5f)
//            splitter.firstComponent = hexView
//
//            val tabsPane = JBTabbedPane()
//            for (view in additionalViews) {
//                tabsPane.addTab(view.tabName, null, view.component)
//            }
//            splitter.secondComponent = tabsPane
//            panel.add(splitter)
//        }
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
