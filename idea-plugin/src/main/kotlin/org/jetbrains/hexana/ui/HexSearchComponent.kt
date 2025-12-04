package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.SearchTextField
import com.intellij.ide.DataManager
import org.jetbrains.hexana.HexanaFileEditor
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

@Composable
fun HexSearchField(onClose: () -> Unit, onSubmit: (String) -> Unit) {
    SwingSearchComponent(onClose, onSubmit)
}


@Composable
private fun SwingSearchComponent(onClose: () -> Unit, onSubmit: (String) -> Unit) {
    SwingPanel(
        factory = {
            val panel = JPanel(BorderLayout())

            // historyPopupEnabled = true (we cannot have named params for java entities)
            val searchTextField = SearchTextField(true)

            searchTextField.textEditor.addActionListener { actionEvent ->
                onSubmit(searchTextField.text)
            }

            SwingUtilities.invokeLater { searchTextField.requestFocusInWindow() }

            panel.add(searchTextField)

            val escapeShortcuts = KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_EDITOR_ESCAPE)
            val escapeAction = object : DumbAwareAction() {
                override fun actionPerformed(actionEvent: AnActionEvent) {
                    onClose()

                    SwingUtilities.invokeLater {
                        val dataContext = DataManager.getInstance().getDataContext(searchTextField)
                        val editor = HexanaFileEditor.KEY.getData(dataContext)
                        editor?.component?.requestFocusInWindow()
                    }
                }
            }
            escapeAction.registerCustomShortcutSet(escapeShortcuts, searchTextField)

            panel
        },
        update = {

        },
        modifier = Modifier.fillMaxWidth().height(height = 24.dp).background(Color.Red)
    )
}

