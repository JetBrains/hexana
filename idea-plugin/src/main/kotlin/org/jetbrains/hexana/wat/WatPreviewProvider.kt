package org.jetbrains.hexana.wat

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.AsyncFileEditorProvider
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.EditorComposite
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import fleet.util.safeAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.WasmFileType

/**
 * Adds the "Text" tab for WASM files which are shown as hex by default.
 */
class WatPreviewProvider : AsyncFileEditorProvider, DumbAware {
    companion object {
        const val EDITOR_TYPE_ID: String = "wat-preview-for-wasm"

        val ORIGINAL_WASM_FILE = Key.create<VirtualFile>("hexana_wat_wasm_file")

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
        val conversionResult = HexanaService.Companion.getInstance().convertWasmToWat(ioFile)
        val watText = when (conversionResult) {
            is HexanaService.WatConversionResult.Success -> conversionResult.wat
            is HexanaService.WatConversionResult.Error -> "(;" + conversionResult.error.trim() + ";)"
        }
        val watPreviewFile = LightVirtualFile(file.name, WatFileType.INSTANCE, watText)
        watPreviewFile.putUserData(ORIGINAL_WASM_FILE, file)
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