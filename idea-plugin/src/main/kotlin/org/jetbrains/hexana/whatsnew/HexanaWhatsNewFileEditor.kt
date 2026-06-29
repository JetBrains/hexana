package org.jetbrains.hexana.whatsnew

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import org.jetbrains.annotations.Nls
import org.jetbrains.hexana.HexanaBundle
import java.awt.BorderLayout
import java.awt.Font
import java.beans.PropertyChangeListener
import javax.swing.JButton
import javax.swing.JComponent

internal const val HEXANA_MARKETPLACE_URL =
    "https://plugins.jetbrains.com/plugin/29090-hexana--webassembly--binary-inspector"
internal const val HEXANA_GITHUB_URL = "https://github.com/JetBrains/hexana"

internal class HexanaWhatsNewFileEditor(
    private val whatsNewFile: HexanaWhatsNewVirtualFile,
) : FileEditor, UserDataHolderBase() {
    private val content: HexanaWhatsNewContent? = HexanaWhatsNewContentService.createContent(whatsNewFile.version)
    private val browser: JBCefBrowser?
    private val component: JComponent

    init {
        if (content != null && JBCefApp.isSupported()) {
            browser = createBrowser(content)
            component = browser.component
        } else {
            browser = null
            component = createFallbackPanel(whatsNewFile.version)
        }
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent = component

    override fun getName(): @Nls(capitalization = Nls.Capitalization.Title) String = "What's New"

    override fun setState(state: FileEditorState) = Unit

    override fun getState(level: FileEditorStateLevel): FileEditorState = HexanaWhatsNewFileEditorState

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile(): HexanaWhatsNewVirtualFile = whatsNewFile

    override fun dispose() {
        browser?.let { Disposer.dispose(it) }
        content?.dispose()
    }
}

private object HexanaWhatsNewFileEditorState : FileEditorState {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean = false
}

private fun createBrowser(content: HexanaWhatsNewContent): JBCefBrowser {
    val browser = JBCefBrowser.createBuilder()
        .setUrl("about:blank")
        .setClient(JBCefApp.getInstance().createClient())
        .build()
    browser.jbCefClient.addRequestHandler(HexanaWhatsNewRequestHandler(content.rootUrl), browser.cefBrowser)
    browser.loadURL(content.indexUrl)
    return browser
}

private class HexanaWhatsNewRequestHandler(
    private val allowedRootUrl: String,
) : CefRequestHandlerAdapter() {
    override fun onBeforeBrowse(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest?,
        user_gesture: Boolean,
        is_redirect: Boolean,
    ): Boolean {
        val url = request?.url ?: return false
        if (url == "about:blank" || url.startsWith(allowedRootUrl)) {
            return false
        }

        if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")) {
            ApplicationManager.getApplication().invokeLater {
                BrowserUtil.browse(url)
            }
        }
        return true
    }
}

private fun createFallbackPanel(version: String): JComponent {
    val panel = JBPanel<JBPanel<*>>(BorderLayout())
    val content = JBPanel<JBPanel<*>>(VerticalFlowLayout(16, 12))
    content.border = javax.swing.BorderFactory.createEmptyBorder(32, 32, 32, 32)

    content.add(JBLabel(HexanaBundle.message("hexana.whatsNew.heading", version)).apply {
        font = font.deriveFont(Font.BOLD, 24f)
    })
    content.add(JBTextArea(HexanaBundle.message("hexana.whatsNew.jcefUnavailable")).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
    })
    content.add(JButton(HexanaBundle.message("hexana.whatsNew.openMarketplace")).apply {
        addActionListener { BrowserUtil.browse(HEXANA_MARKETPLACE_URL) }
    })

    panel.add(content, BorderLayout.NORTH)
    return panel
}
