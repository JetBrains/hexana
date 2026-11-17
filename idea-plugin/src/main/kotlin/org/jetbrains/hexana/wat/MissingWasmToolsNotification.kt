package org.jetbrains.hexana.wat

import com.intellij.codeInsight.hint.HintUtil
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.HexanaConfigurable
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.HexanaSettings
import java.nio.file.Path
import java.util.function.Function
import javax.swing.JComponent
import kotlin.io.path.exists


class MissingWasmToolsNotification : EditorNotificationProvider, DumbAware {

    companion object {
        val WASM_CONVERSION_ERROR = Key.create<HexanaService.WatConversionResult.Error>("hexana_wasm_conversion_error")
    }

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        if (file.getUserData(WASM_CONVERSION_ERROR) == null) {
            return null
        }
        if (wasmToolsExist()) {
            return null
        }
        return Function {
            val panel = EditorNotificationPanel(HintUtil.WARNING_COLOR_KEY, EditorNotificationPanel.Status.Warning)
            panel.text = HexanaBundle.message("hexana.notification.wasm2wat.not.found")
            panel.createActionLabel(HexanaBundle.message("hexana.notification.wasm2wat.not.found.configure"), Runnable {
                if (project.isDisposed) {
                    return@Runnable
                }
                ShowSettingsUtil.getInstance().showSettingsDialog(project, HexanaConfigurable::class.java)
            })
            return@Function panel
        }
    }

    private fun wasmToolsExist(): Boolean {
        val wasmTools = Path.of(HexanaSettings.getInstance().wasmToolsPath)
        return if (wasmTools.isAbsolute) {
            wasmTools.exists()
        } else {
            PathEnvironmentVariableUtil.findInPath(wasmTools.toString()) != null
        }
    }
}
