package org.jetbrains.hexana.wat

import com.intellij.codeInsight.hint.HintUtil
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.EditorComposite
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import fleet.util.safeAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.HexanaConfigurable
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.HexanaSettings
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.wat.WatPreviewProvider.Companion.WASM_CONVERSION_ERROR
import java.nio.file.Path
import java.util.function.Function
import javax.swing.JComponent
import kotlin.io.path.exists


/**
 * Adds the "Text" tab for WASM files which are shown as hex by default.
 */
class WatPreviewProvider : AsyncFileEditorProvider, DumbAware {
    companion object {
        const val EDITOR_TYPE_ID: String = "wat-preview-for-wasm"

        val ORIGINAL_WASM_FILE = Key.create<VirtualFile>("hexana_wat_wasm_file")
        val WASM_CONVERSION_ERROR = Key.create<HexanaService.WatConversionResult.Error>("hexana_wasm2wat_error")

        /**
         * Handles navigation in the preview wat files.
         * Returns true if the navigation was handled, false if
         * the default navigation logic should be used.
         *
         * The preview wat file created on the fly requires special handling because
         * EditorComposite (the thing which contains all editors of the file) has only
         * one VirtualFile (the original wasm file).
         *
         * When the user navigates in the wat preview file, Idea fails to find
         * the EditorComposite for it (wat file != wasm file) and opens
         * the preview wat file in a new composite.
         *
         * To work that around, we find the composite and navigate to the
         * desired offset manually.
         */
        fun navigateInWatPreviewFile(project: Project, watFile: VirtualFile, offset: Int, requestFocus: Boolean): Boolean {
            val originalWasmFile = watFile.getUserData(ORIGINAL_WASM_FILE) ?: return false
            val wasmComposite = FileEditorManager.getInstance(project).getComposite(originalWasmFile) as? EditorComposite
                ?: return false
            val watEditor = wasmComposite.allEditors.firstOrNull { it.file == watFile }.safeAs<TextEditor>()?.editor ?: return false
            val descriptor = OpenFileDescriptor(project, originalWasmFile, 0)
            descriptor.navigate(requestFocus)
            watEditor.caretModel.moveToOffset(offset)
            return true
        }
    }

    override fun accept(
        project: Project,
        file: VirtualFile
    ): Boolean {
        return file.fileType is WasmFileType
    }

    /**
     * Overriding [createFileEditor] instead of [createEditorAsync] so that we can run wasm2wat
     * command-line without a readAction (by default, createEditorAsync is executed under read action).
     */
    override suspend fun createFileEditor(
        project: Project,
        file: VirtualFile,
        document: Document?,
        editorCoroutineScope: CoroutineScope
    ): FileEditor {
        val ioFile = VfsUtil.virtualToIoFile(file)
        val conversionResult = HexanaService.getInstance().convertWasmToWat(ioFile)
        val watText = when (conversionResult) {
            is HexanaService.WatConversionResult.Success -> conversionResult.wat
            is HexanaService.WatConversionResult.Error -> "(;" + conversionResult.error.trim() + ";)"
        }
        val watPreviewFile = LightVirtualFile(file.name, WatFileType.INSTANCE, watText)
        watPreviewFile.putUserData(ORIGINAL_WASM_FILE, file)
        if (conversionResult is HexanaService.WatConversionResult.Error) {
            watPreviewFile.putUserData(WASM_CONVERSION_ERROR, conversionResult)
        }
        return withContext(Dispatchers.EDT) {
            TextEditorProvider.getInstance().createEditor(project, watPreviewFile)
        }
    }

    override fun createEditor(
        project: Project,
        file: VirtualFile
    ): FileEditor {
        // editor should be created asynchronously
        throw UnsupportedOperationException()
    }

    override fun getEditorTypeId(): @NonNls String {
        return EDITOR_TYPE_ID
    }

    override fun getPolicy(): FileEditorPolicy {
        return FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
    }
}


class MissingWasm2WatNotification : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        if (file.getUserData(WASM_CONVERSION_ERROR) == null) {
            return null
        }
        if (wasm2watExist()) {
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

    private fun wasm2watExist(): Boolean {
        val wasm2wat = Path.of(HexanaSettings.getInstance().wasm2watExecPath)
        return if (wasm2wat.isAbsolute) {
            wasm2wat.exists()
        } else {
            PathEnvironmentVariableUtil.findInPath(wasm2wat.toString()) != null
        }
    }
}
