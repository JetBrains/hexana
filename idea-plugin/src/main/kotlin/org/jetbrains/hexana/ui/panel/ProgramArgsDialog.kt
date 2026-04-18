package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

internal class ProgramArgsDialog(
    project: Project,
    private val exportName: String,
) : DialogWrapper(project, false) {

    var programArgs: String = ""
        private set

    init {
        title = "Program Arguments for '$exportName'"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Arguments:") {
                textField()
                    .bindText(::programArgs)
                    .focused()
                    .comment("Space-separated command-line arguments passed to the component")
            }
        }
    }
}
