package org.jetbrains.hexana.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.providers.WasmBinaryProvider

class ShowStructurePopupAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val hexanaEditor = e.getData(HexanaFileEditor.KEY)
        val wasmBinaryProvider = hexanaEditor?.binaryProvider as? WasmBinaryProvider
        e.presentation.isEnabledAndVisible = wasmBinaryProvider != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val hexanaEditor = e.getData(HexanaFileEditor.KEY) ?: return
        hexanaEditor.stateHolder.setShowStructurePopup(true)
    }
}
