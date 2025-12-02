package org.jetbrains.hexana.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.HexanaFileEditor
import javax.swing.JComponent

class GoToOffsetAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val hexanaEditor = e.getData(HexanaFileEditor.KEY)
        e.presentation.isEnabledAndVisible = hexanaEditor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val hexanaEditor = e.getData(HexanaFileEditor.KEY) ?: return
        val project = e.project ?: return
        val addressRange = 0..<hexanaEditor.file.length
        val dialog = GoToOffsetDialog(project, 0, addressRange)
        if (dialog.showAndGet()) {
            val address = dialog.offset.coerceIn(addressRange)
            hexanaEditor.stateHolder.scrollHexViewTo(address)
        }
    }
}


class GoToOffsetDialog(
    project: Project,
    var offset: Long,
    val offsetRange: LongRange
) : DialogWrapper(project, false) {

    val hexFormat = HexFormat {
        number { removeLeadingZeros = true }
    }

    init {
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                textField()
                    .label(HexanaBundle.message("action.hexana.goToOffset.offset"))
                    .comment("0 .. ${offsetRange.last.toHexString(hexFormat)}")
                    .focused()
                    .bindText(
                        {
                            offset.toHexString(hexFormat)
                        },
                        { text ->
                            runCatching {
                                offset = text.hexToLong().coerceIn(offsetRange)
                            }
                        }
                    )
                    .validationInfo {
                        try {
                            it.text.hexToLong()
                            null
                        } catch (_: Exception) {
                            error(HexanaBundle.message("action.hexana.goToOffset.invalidOffset"))
                        }
                    }
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        return super.doValidate()
    }
}