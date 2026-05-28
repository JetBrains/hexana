package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import org.jetbrains.hexana.run.PopupMode

internal class ProgramArgsDialog(
    project: Project,
    exportName: String,
    isComponentModel: Boolean = false,
    popupMode: PopupMode = PopupMode.RUN_AND_DEBUG,
) : WasmRunDialog(project, "Program Arguments for '$exportName'", isComponentModel, popupMode) {

    var programArgs: String = ""
        private set

    init {
        init()
    }

    override fun Panel.addContentRows() {
        row("Arguments:") {
            textField()
                .bindText(::programArgs)
                .comment("Space-separated command-line arguments passed to the component")
        }
    }
}
