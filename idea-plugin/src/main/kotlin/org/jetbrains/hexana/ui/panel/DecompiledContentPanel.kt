package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.UISettingsUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.HyperlinkLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * Creates a [Panel] that embeds a read-only IntelliJ [TextEditor] showing the
 * decompiled content of [decompiledFile]. A fresh [TextEditor] is created each
 * time the composable enters the composition (i.e. each tab switch) and disposed
 * when it leaves, mirroring the WAT tab's lifecycle pattern.
 */
internal fun decompiledContentPanel(
    project: Project,
    decompiledFile: VirtualFile,
    originalFile: VirtualFile? = null,
): Panel = Panel("Decompiled") {
    decompiledEditorComposable(project, decompiledFile, originalFile)
}

/**
 * Composable that embeds a read-only IntelliJ [TextEditor] showing the
 * decompiled content of [decompiledFile]. Can be used standalone inside
 * any composable context (e.g. a lazy decompilation panel).
 */
@Composable
internal fun decompiledEditorComposable(
    project: Project,
    decompiledFile: VirtualFile,
    originalFile: VirtualFile? = null,
) {
    val textEditor = remember {
        val te = TextEditorProvider.getInstance().createEditor(project, decompiledFile) as TextEditor
        te.editor.settings.isLineNumbersShown = true
        te.editor.settings.isFoldingOutlineShown = true
        te.editor.settings.isUseSoftWraps = false
        val editorEx = te.editor as? EditorEx
        editorEx?.isViewer = true
        // Re-apply the highlighter so it picks up the file's language/type.
        editorEx?.highlighter = com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
            .getInstance().createEditorHighlighter(project, decompiledFile)
        te.editor.document.setReadOnly(true)
        te
    }

    // Disable inspections outside the Compose recomposition/paint path.
    // setHighlightingSettingForRoot triggers a write action (via DaemonListeners),
    // which would deadlock if executed during the Skia render pass.
    DisposableEffect(Unit) {
        ApplicationManager.getApplication().invokeLater {
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(decompiledFile)
            if (psiFile != null) {
                com.intellij.codeInsight.daemon.impl.analysis.HighlightingSettingsPerFile
                    .getInstance(project)
                    .setHighlightingSettingForRoot(
                        psiFile,
                        com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting.SKIP_HIGHLIGHTING,
                    )
            }
        }
        onDispose {
            Disposer.dispose(textEditor)
        }
    }

    val editorComponent = textEditor.component
    val currentDensity = LocalDensity.current
    val ideScale = UISettingsUtils.getInstance().currentIdeScale
    val unscaledDensity = if (ideScale != 0f) {
        Density(currentDensity.density / ideScale, currentDensity.fontScale)
    } else {
        currentDensity
    }
    CompositionLocalProvider(LocalDensity provides unscaledDensity) {
        SwingPanel(
            modifier = Modifier.fillMaxSize(),
            update = {
                ApplicationManager.getApplication().invokeLater {
                    UIUtil.findComponentOfType(it, EditorComponentImpl::class.java)?.requestFocusInWindow()
                }
            },
            factory = {
                val linkPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    border = JBUI.Borders.empty(2, 4)
                    val link = HyperlinkLabel("Open in editor").apply {
                        icon = AllIcons.Ide.External_link_arrow
                        addHyperlinkListener {
                            FileEditorManager.getInstance(project).openFile(originalFile ?: decompiledFile, true)
                        }
                    }
                    add(link)
                }

                val panel = JPanel(BorderLayout())
                panel.add(linkPanel, BorderLayout.NORTH)
                panel.add(editorComponent, BorderLayout.CENTER)
                panel
            },
        )
    }
}
