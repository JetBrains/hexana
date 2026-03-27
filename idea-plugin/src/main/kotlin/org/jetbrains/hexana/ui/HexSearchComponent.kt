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
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.hexana.*
import org.jetbrains.hexana.providers.BinaryProvider
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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
            state.searchText.value = ""
            state.searchResults.value = emptyList()
            state.currentSearchIndex.value = -1
            closeFlow.tryEmit(Unit)
        },
        onSubmit = { text, reverse ->
            performSearch(text, reverse, state, binaryProvider)
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

fun performSearch(
    text: String,
    reverse: Boolean,
    state: HexViewStateHolder,
    binaryProvider: BinaryProvider
) {
    state.searchText.value = text
    val bytesPerRow = state.bytesPerRow.value
    val needle = convertSearchTextToBytes(text, state.searchBinaryMode)
    val hay = binaryProvider.bytes

    if (needle.isNotEmpty()) {
        val results = mutableListOf<LongRange>()
        var current = 0
        while (true) {
            val idx = find(needle, hay, current)
            if (idx == -1) break
            results.add(idx.toLong() until (idx.toLong() + needle.size))
            current = idx + 1
        }
        state.searchResults.value = results

        val lastMatch = state.selection.start.getByteOffset(bytesPerRow)
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
            val range = index.toLong() until index.toLong() + needle.size
            state.setSelection(range)

            // Update current search index
            state.currentSearchIndex.value = results.indexOf(range)
        } else {
            state.currentSearchIndex.value = -1
        }
    } else {
        state.searchResults.value = emptyList()
        state.currentSearchIndex.value = -1
    }
}

@Composable
private fun SwingSearchComponent(
    state: HexViewStateHolder,
    onClose: () -> Unit,
    onSubmit: (String, Boolean) -> Unit
) {
    SwingPanel(
        factory = {
            val panel = JPanel(BorderLayout())

            val searchTextField = SearchTextField(true)

            searchTextField.textEditor.addActionListener {
                onSubmit(searchTextField.text, state.searchReverse)
            }

            searchTextField.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) {
                    onSubmit(searchTextField.text, state.searchReverse)
                }

                override fun removeUpdate(e: DocumentEvent) {
                    onSubmit(searchTextField.text, state.searchReverse)
                }

                override fun changedUpdate(e: DocumentEvent) {
                    onSubmit(searchTextField.text, state.searchReverse)
                }
            })

            setupSearchActions(searchTextField, state)

            ApplicationManager.getApplication().invokeLater { searchTextField.requestFocusInWindow() }

            val statusLabel = JBLabel().apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(0, 5)
            }

            val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))

            val prevAction = object : AnAction("Previous", "Previous occurrence", AllIcons.Actions.PreviousOccurence) {
                override fun actionPerformed(e: AnActionEvent) {
                    onSubmit(searchTextField.text, true)
                }
            }
            val nextAction = object : AnAction("Next", "Next occurrence", AllIcons.Actions.NextOccurence) {
                override fun actionPerformed(e: AnActionEvent) {
                    onSubmit(searchTextField.text, false)
                }
            }

            val prevBtn = ActionButton(prevAction, prevAction.templatePresentation.clone(), ActionPlaces.UNKNOWN, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
            val nextBtn = ActionButton(nextAction, nextAction.templatePresentation.clone(), ActionPlaces.UNKNOWN, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)

            val binaryModeAction = object : ToggleAction("Binary Mode", "Search by byte sequence", AllIcons.FileTypes.BinaryData) {
                override fun isSelected(e: AnActionEvent): Boolean {
                    return state.searchBinaryMode
                }

                override fun setSelected(e: AnActionEvent, stateVal: Boolean) {
                    state.setSearchBinaryMode(stateVal)
                    onSubmit(searchTextField.text, state.searchReverse)
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }
            }
            val binaryBtn = ActionButton(binaryModeAction, binaryModeAction.templatePresentation.clone(), ActionPlaces.UNKNOWN, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)

            actionsPanel.add(statusLabel)
            actionsPanel.add(binaryBtn)
            actionsPanel.add(prevBtn)
            actionsPanel.add(nextBtn)

            panel.add(searchTextField, BorderLayout.CENTER)
            panel.add(actionsPanel, BorderLayout.EAST)

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
        update = { panel ->
            val results = state.searchResults.value
            val currentIndex = state.currentSearchIndex.value
            val statusLabel = UIUtil.findComponentOfType(panel, JBLabel::class.java)
            if (statusLabel != null) {
                if (results.isEmpty()) {
                    statusLabel.text = ""
                } else {
                    statusLabel.text = "${currentIndex + 1} of ${results.size}"
                }
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