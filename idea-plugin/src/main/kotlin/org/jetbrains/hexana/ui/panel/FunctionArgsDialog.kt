package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import org.jetbrains.hexana.run.PopupMode

internal class FunctionArgDescriptor(
    val label: String,
    val typeName: String,
    val defaultValue: String = "0",
    val enumEntries: List<String>? = null,
    val validate: ((String) -> ValidationInfo?)? = null,
)

internal class FunctionArgsDialog(
    project: Project,
    exportName: String,
    private val args: List<FunctionArgDescriptor>,
    isComponentModel: Boolean = false,
    popupMode: PopupMode = PopupMode.RUN_AND_DEBUG,
) : WasmRunDialog(project, "Arguments for '$exportName'", isComponentModel, popupMode, isExportInvocation = true) {

    val argValues = MutableList(args.size) { i -> args[i].defaultValue }

    init {
        init()
    }

    override fun Panel.addContentRows() {
        for ((i, arg) in args.withIndex()) {
            row("${arg.label} (${arg.typeName}):") {
                val entries = arg.enumEntries
                if (entries != null) {
                    val model = com.intellij.openapi.ui.ComboBox(entries.toTypedArray())
                    cell(model)
                        .bind(
                            { component -> component.selectedItem as? String ?: "" },
                            { component, value -> component.selectedItem = value },
                            MutableProperty({ argValues[i] }, { argValues[i] = it }),
                        )
                } else {
                    textField()
                        .bindText({ argValues[i] }, { argValues[i] = it })
                        .validationInfo { field ->
                            arg.validate?.invoke(field.text)
                        }
                }
            }
        }
    }
}
