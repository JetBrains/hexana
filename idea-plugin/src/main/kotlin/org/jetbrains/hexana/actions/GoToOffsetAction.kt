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
        val offsetRange = 0..<hexanaEditor.file.length

        val bytesPerRow = hexanaEditor.stateHolder.bytesPerRow.value
        val position = hexanaEditor.stateHolder.hoverPosition
        val currentOffset = position.getByteOffset(bytesPerRow)
        val dialog = GoToOffsetDialog(project, currentOffset.toLong().coerceIn(offsetRange), offsetRange)
        if (dialog.showAndGet()) {
            val address = dialog.offset.coerceIn(offsetRange)
            hexanaEditor.stateHolder.scrollHexViewTo(address)

            val line = (address / bytesPerRow).toInt()
            val lineOffset  = address.rem(bytesPerRow).toInt()
            hexanaEditor.stateHolder.setCursorPosition(line, lineOffset)
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
                    .comment("0 .. 0x${offsetRange.last.toHexString(hexFormat)} (${offsetRange.last})")
                    .focused()
                    .bindText(
                        {
                            "0x${offset.toHexString(hexFormat)}"
                        },
                        { text ->
                            runCatching {
                                val parsedOffset = parseHexOrDec(text) ?: -1
                                offset = parsedOffset.coerceIn(offsetRange)
                            }
                        }
                    )
                    .validationInfo {
                        if (parseHexOrDec(it.text) == null) {
                            error(HexanaBundle.message("action.hexana.goToOffset.invalidOffset"))
                        } else {
                            null
                        }
                    }
            }
        }
    }

    private fun parseHexOrDec(s: String): Long? {
        if (s.startsWith("0x")) {
            return try {
                s.removePrefix("0x").hexToLong()
            }
            catch (_: Exception) {
                null
            }
        }

        try {
            return s.toLong()
        } catch (_: Exception) {
            // try parsing as hex
        }

        try {
            return s.hexToLong()
        } catch (_: Exception) {
        }
        return null
    }

    override fun doValidate(): ValidationInfo? {
        return super.doValidate()
    }
}