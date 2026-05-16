package org.jetbrains.hexana

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.statistics.HexanaCounterUsagesCollector
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode

class HexanaFileEditorProvider : FileEditorProvider, DumbAware {
    companion object {
        const val EDITOR_TYPE_ID: String = "hexana-editor"
    }

    override fun accept(
        project: Project,
        file: VirtualFile
    ): Boolean {
        return file.fileType is WasmFileType ||
            file.fileType is BinaryFileType ||
            file.extension.equals("class", ignoreCase = true) ||
            file.extension.equals("jar", ignoreCase = true) ||
            file.extension.equals("zip", ignoreCase = true) ||
            file.extension.equals("war", ignoreCase = true) ||
            file.extension.equals("apk", ignoreCase = true) ||
            file.extension.equals("jit", ignoreCase = true)
    }

    override fun createEditor(
        project: Project,
        file: VirtualFile
    ): FileEditor {
        val service = HexanaService.getInstance()
        if (file.fileType is WasmFileType) {
            HexanaCounterUsagesCollector.logWasmFileOpened(project)
            service.captureAnalytics(
                HexanaEvent.WasmFileOpened(file.length, HexanaEvent.Surface.IDE_PLUGIN)
            )
        }
        val editor = HexanaFileEditor(project, file)
        if (file.fileType is WasmFileType) {
            reportUnparsedOpcodes(editor)
        }
        return editor
    }

    override fun getEditorTypeId(): @NonNls String {
        return EDITOR_TYPE_ID
    }

    override fun getPolicy(): FileEditorPolicy {
        return FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
    }

    private fun reportUnparsedOpcodes(editor: HexanaFileEditor) {
        val provider = editor.binaryProvider as? WasmBinaryProvider ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val codeRecords = provider.codeRecords
            val totalFunctions = codeRecords.size
            val unparsedCount = codeRecords.count { record ->
                record.opCodes.any { it is UnparsedOpCode }
            }
            if (unparsedCount > 0) {
                HexanaService.getInstance().captureAnalytics(
                    HexanaEvent.WasmUnparsedOpcodes(
                        unparsedCount = unparsedCount,
                        totalFunctions = totalFunctions
                    )
                )
            }
        }
    }
}
