package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.hexana.run.PopupMode
import org.jetbrains.hexana.run.WasmEngine
import org.jetbrains.hexana.run.WasmRunMode
import javax.swing.Action
import javax.swing.JComponent

/**
 * Result of the run dialog: the chosen engine and whether the user
 * clicked Run or Debug.
 */
internal data class WasmRunDialogResult(
    val engine: WasmEngine,
    val mode: WasmRunMode,
)

/**
 * A dialog that shows a runtime selector as the first field, followed by
 * an optional content panel (e.g. function arguments), and Run / Debug
 * buttons instead of the standard OK / Cancel.
 *
 * When [popupMode] is [PopupMode.RUN_ONLY] only the Run button is shown;
 * when [PopupMode.DEBUG_ONLY] only Debug; when [PopupMode.RUN_AND_DEBUG]
 * both buttons appear.
 */
internal open class WasmRunDialog(
    project: Project,
    dialogTitle: String,
    private val isComponentModel: Boolean = false,
    private val popupMode: PopupMode = PopupMode.RUN_AND_DEBUG,
) : DialogWrapper(project, false) {

    private val engines: List<WasmEngine> =
        WasmEngine.entries.filter { !isComponentModel || it.supportsComponentsModel }

    private val engineCombo = com.intellij.openapi.ui.ComboBox(engines.toTypedArray()).apply {
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                super.getListCellRendererComponent(list, (value as? WasmEngine)?.displayName ?: value, index, isSelected, cellHasFocus)
                return this
            }
        }
        addActionListener { updateDebugEnabled() }
    }

    /** The result set when the user clicks Run or Debug; `null` if cancelled. */
    var result: WasmRunDialogResult? = null
        private set

    private val runAction = object : DialogWrapperAction("Run") {
        init {
            putValue(Action.SMALL_ICON, com.intellij.icons.AllIcons.Actions.Execute)
        }

        override fun doAction(e: java.awt.event.ActionEvent?) {
            applyFields()
            result = WasmRunDialogResult(engineCombo.item, WasmRunMode.RUN)
            close(OK_EXIT_CODE)
        }
    }

    private val debugAction = object : DialogWrapperAction("Debug") {
        init {
            putValue(Action.SMALL_ICON, com.intellij.icons.AllIcons.Actions.StartDebugger)
        }

        override fun doAction(e: java.awt.event.ActionEvent?) {
            applyFields()
            result = WasmRunDialogResult(engineCombo.item, WasmRunMode.DEBUG)
            close(OK_EXIT_CODE)
        }
    }

    init {
        title = dialogTitle
    }

    /**
     * Subclasses override this to add additional rows below the
     * runtime selector row directly into the same DSL panel.
     */
    protected open fun Panel.addContentRows() {}

    final override fun createCenterPanel(): JComponent {
        return panel {
            row("Runtime:") {
                cell(engineCombo).focused()
            }
            addContentRows()
        }
    }

    private fun updateDebugEnabled() {
        val engine = engineCombo.item
        debugAction.isEnabled = engine?.supportsDebug == true
    }

    override fun createActions(): Array<Action> {
        val actions = mutableListOf<Action>()
        when (popupMode) {
            PopupMode.RUN_AND_DEBUG -> {
                actions.add(debugAction)
                actions.add(runAction)
                updateDebugEnabled()
            }
            PopupMode.RUN_ONLY -> actions.add(runAction)
            PopupMode.DEBUG_ONLY -> actions.add(debugAction)
        }
        actions.add(cancelAction)
        return actions.toTypedArray()
    }

    override fun getPreferredFocusedComponent(): JComponent = engineCombo

    companion object {
        /**
         * Creates a simple run dialog with only the runtime selector
         * (no extra content). The dialog is fully initialised and ready
         * to be shown via [showAndGet].
         */
        fun createSimple(
            project: Project,
            dialogTitle: String,
            isComponentModel: Boolean = false,
            popupMode: PopupMode = PopupMode.RUN_AND_DEBUG,
        ): WasmRunDialog {
            return WasmRunDialog(project, dialogTitle, isComponentModel, popupMode).also { it.init() }
        }
    }
}
