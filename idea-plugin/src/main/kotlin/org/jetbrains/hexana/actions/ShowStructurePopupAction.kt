package org.jetbrains.hexana.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.DraggablePopupTitle
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.StructureViewTree
import org.jetbrains.jewel.bridge.compose
import java.awt.BorderLayout
import javax.swing.JPanel


class ShowStructurePopupAction : AnAction() {

    companion object {
        const val DIMENSION_KEY = "hexanaStructurePopup"
    }

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

        val project = e.project ?: return
        val binaryProvider = hexanaEditor.binaryProvider ?: return

        val closeFlow = MutableStateFlow<Unit?>(null)
        val panel = JPanel(BorderLayout())
        panel.preferredSize = JBUI.size(400, 500)
        val composeComponent = compose {
            Column(
                modifier = Modifier.background(HexanaTheme.backgroundColor)
            ) {
                DraggablePopupTitle(panel, hexanaEditor.myFile.name)
                StructureViewTree(binaryProvider = binaryProvider, state = hexanaEditor.stateHolder, closeFlow = closeFlow)
            }
        }

        panel.add(composeComponent, BorderLayout.CENTER)
        val myPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, composeComponent)
            .setMovable(true)
            .setResizable(true)
            .setBelongsToGlobalPopupStack(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(false)
            .setFocusable(true)
            .setRequestFocus(true)
            .setDimensionServiceKey(project, DIMENSION_KEY, true)
            .createPopup()

        myPopup.showCenteredInCurrentWindow(project)

        HexanaService.getInstance().coroutineScope.launch {
            closeFlow.collect {
                if (it != null) {
                    withContext(Dispatchers.EDT) {
                        myPopup.cancel()
                    }
                }
            }
        }
    }
}
