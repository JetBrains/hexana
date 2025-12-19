package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CompositeShortcutSet
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.SearchTextField
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.hexana.*
import org.jetbrains.hexana.providers.BinaryProvider
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

@Composable
fun HexSearchField(
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    hexFocusRequester: FocusRequester,
) {
    val closeFlow = remember { MutableStateFlow<Unit?>(null) }
    SwingSearchComponent(
        state,
        onClose = {
            state.showSearchDialog.value = false
            closeFlow.tryEmit(Unit)
        },
        onSubmit = { text ->
            val bytesPerRow = state.bytesPerRow.value
            val needle = convertSearchTextToBytes(text)
            val hay = binaryProvider.bytes
            val lastMatch = state.selection.start.getByteOffset(bytesPerRow)
            val reverse = state.searchReverse
            val startIndex = if (lastMatch >= 0) {
                when {
                    reverse -> lastMatch - 1
                    else -> lastMatch + 1
                }.coerceIn(hay.indices)
            } else {
                val cursor = state.hoverPosition.getByteOffset(bytesPerRow)
                cursor.coerceIn(hay.indices)
            }
            var index = find(needle, hay, startIndex, reverse)
            if (index == -1) {
                index = find(needle, hay, if (reverse) hay.lastIndex else 0, reverse)
            }
            if (index >= 0) {
                state.scrollHexViewTo(index.toLong())
                state.setCursorPosition(index.toLong(), bytesPerRow)
                state.setSelection(index.toLong() until  index.toLong() + needle.size)
            }
        }
    )

    LaunchedEffect(Unit) {
        closeFlow.collect {
            if (it != null) {
                hexFocusRequester.requestFocus()
            }
        }
    }
}

@Composable
private fun SwingSearchComponent(
    state: HexViewStateHolder,
    onClose: () -> Unit,
    onSubmit: (String) -> Unit
) {
    SwingPanel(
        factory = {
            val panel = JPanel(BorderLayout())

            // historyPopupEnabled = true (we cannot have named params for java entities)
            val searchTextField = SearchTextField(true)

            searchTextField.textEditor.addActionListener { actionEvent ->
                onSubmit(searchTextField.text)
            }

            setupSearchActions(searchTextField, state)

            ApplicationManager.getApplication().invokeLater { searchTextField.requestFocusInWindow() }

            panel.add(searchTextField)

            val escapeShortcuts = KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_EDITOR_ESCAPE)
            val escapeAction = object : DumbAwareAction() {
                override fun actionPerformed(actionEvent: AnActionEvent) {
                    onClose()

                    ApplicationManager.getApplication().invokeLater {
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
            ApplicationManager.getApplication().invokeLater {
                UIUtil.findComponentOfType(it, SearchTextField::class.java)?.requestFocusInWindow()
            }
        },
        modifier = Modifier.fillMaxWidth().height(height = 24.dp).background(Color.Red)
    )
}


class HexFindAction(
    val component: JComponent,
    val state: HexViewStateHolder,
    val reverse: Boolean
) : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(actionEvent: AnActionEvent) {
        actionEvent.presentation.isEnabledAndVisible = state.viewMode == HexViewMode.Hex
    }

    override fun actionPerformed(actionEvent: AnActionEvent) {
        state.setSearchReverse(reverse)
        showAndFocusSearchTextField(component, state)
    }
}


fun setupSearchActions(
    component: JComponent,
    state: HexViewStateHolder,
) {
    val find = KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_FIND)
    val findNext = KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_FIND_NEXT)
    val findNextAction = HexFindAction(component, state, false)
    findNextAction.registerCustomShortcutSet(CompositeShortcutSet(find, findNext), component)

    val findPrev = KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_FIND_PREVIOUS)
    val findPrevAction = HexFindAction(component, state, true)
    findPrevAction.registerCustomShortcutSet(findPrev, component)
}


fun showAndFocusSearchTextField(
    parent: JComponent,
    state: HexViewStateHolder
) {
    if (state.showSearchDialog.value) {
        // Already shown. Focus and find next/prev occurence.
        val field = UIUtil.findComponentOfType(parent, SearchTextField::class.java)
        if (field != null) {
            ApplicationManager.getApplication().invokeLater {
                field.requestFocusInWindow()
                field.textEditor.postActionEvent()
            }
        }
    }
    else {
        state.showSearchDialog.value = true
    }
}