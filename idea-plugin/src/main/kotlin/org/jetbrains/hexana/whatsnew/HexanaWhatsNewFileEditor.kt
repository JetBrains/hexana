package org.jetbrains.hexana.whatsnew

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.util.UserDataHolderBase
import org.jetbrains.annotations.Nls
import org.jetbrains.hexana.ui.createComposePanel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

internal const val HEXANA_MARKETPLACE_URL =
    "https://plugins.jetbrains.com/plugin/29090-hexana--webassembly--binary-inspector"
internal const val HEXANA_GITHUB_URL = "https://github.com/JetBrains/hexana"

internal class HexanaWhatsNewFileEditor(
    private val whatsNewFile: HexanaWhatsNewVirtualFile,
) : FileEditor, UserDataHolderBase() {
    private val content = requireNotNull(HexanaWhatsNewContentService.createContent(whatsNewFile.version)) {
        "Missing What's New content for ${whatsNewFile.version}"
    }
    private val model = loadWhatsNewUiModel(content)
    private val panel: JComponent = createComposePanel {
        HexanaWhatsNewScreen(
            model = model,
            onOpenMarketplace = { BrowserUtil.browse(HEXANA_MARKETPLACE_URL) },
            onOpenGitHub = { BrowserUtil.browse(HEXANA_GITHUB_URL) },
        )
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): @Nls(capitalization = Nls.Capitalization.Title) String = "What's New"

    override fun setState(state: FileEditorState) = Unit

    override fun getState(level: FileEditorStateLevel): FileEditorState = HexanaWhatsNewFileEditorState

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile(): HexanaWhatsNewVirtualFile = whatsNewFile

    override fun dispose() {
        content.dispose()
    }
}

private object HexanaWhatsNewFileEditorState : FileEditorState {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean = false
}
