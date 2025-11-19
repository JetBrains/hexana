package org.jetbrains.hexana

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LoadingDecorator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import org.jetbrains.annotations.Nls
import org.jetbrains.hexana.wat.WatFileType
import java.awt.BorderLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

class WasmFileViewProvider : HexanaFileViewProvider {
    override fun getViews(project: Project, file: VirtualFile): List<HexanaFileView> {
        if (file.fileType != WasmFileType.INSTANCE) {
            return emptyList()
        }

        val result = arrayListOf<HexanaFileView>()
        createWatView(project, file)?.let {
            result.add(it)
        }
        return result
    }

    private fun createWatView(project: Project, file: VirtualFile): HexanaFileView? {
        val ioFile = VfsUtil.virtualToIoFile(file)
        if (!ioFile.exists()) {
            return null
        }

        val viewPanel = JPanel(BorderLayout())
        val watView = object : HexanaFileView {
            override val tabName: @Nls(capitalization = Nls.Capitalization.Title) String
                get() = HexanaBundle.message("hexana.wasm.watFileView")

            override val component: JComponent
                get() = viewPanel

            override fun dispose() {
            }
        }

        val loadingDecorator = LoadingDecorator(JPanel(), watView, 0)
        loadingDecorator.startLoading(false)
        watView.component.add(loadingDecorator.component, BorderLayout.CENTER)

        ApplicationManager.getApplication().executeOnPooledThread {
            val conversionResult = convertWasmToWat(ioFile)
            when (conversionResult) {
                is WatConversionResult.Success -> {
                    val watFile = object : LightVirtualFile("test", WatFileType.INSTANCE, conversionResult.wat) {
                        override fun isWritable(): Boolean {
                            return false
                        }
                    }

                    ApplicationManager.getApplication().invokeLater {
                        val watEditor = TextEditorProvider.getInstance().createEditor(project, watFile)
                        Disposer.register(watView) {
                            watEditor.dispose()
                        }
                        watView.component.removeAll()
                        watView.component.add(watEditor.component, BorderLayout.CENTER)
                    }
                }
                is WatConversionResult.Error -> {
                    ApplicationManager.getApplication().invokeLater {
                        watView.component.removeAll()
                        val errorPanel = JPanel()
                        val label = JBLabel(conversionResult.error)
                        label.setForeground(JBColor.red);
                        errorPanel.add(label)
                        watView.component.add(errorPanel, BorderLayout.CENTER)
                    }
                }
            }
        }
        return watView
    }

    sealed interface WatConversionResult {
        class Success(val wat: String) : WatConversionResult
        class Error(val error: String) : WatConversionResult
    }

    @RequiresBackgroundThread
    private fun convertWasmToWat(file: File): WatConversionResult {
        val cmd = GeneralCommandLine("wasm2wat", "--fold-exprs", "--generate-names", file.absolutePath)
        return try {
            val processOutput = ExecUtil.execAndGetOutput(cmd)
            if (processOutput.exitCode != 0) {
                WatConversionResult.Error(processOutput.stderr)
            } else {
                WatConversionResult.Success(processOutput.stdout)
            }
        }
        catch (e: Exception) {
            WatConversionResult.Error(e.message ?: "Failed to convert WASM to WAT")
        }
    }
}
