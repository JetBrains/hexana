package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

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
) : DialogWrapper(project, false) {

    val argValues = MutableList(args.size) { i -> args[i].defaultValue }

    init {
        title = "Arguments for '$exportName'"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            for ((i, arg) in args.withIndex()) {
                row("${arg.label} (${arg.typeName}):") {
                    val entries = arg.enumEntries
                    if (entries != null) {
                        val model = com.intellij.openapi.ui.ComboBox(entries.toTypedArray())
                        val combo = cell(model)
                            .bind(
                                { component -> component.selectedItem as? String ?: "" },
                                { component, value -> component.selectedItem = value },
                                MutableProperty({ argValues[i] }, { argValues[i] = it }),
                            )
                        if (i == 0) combo.focused()
                    } else {
                        val field = textField()
                            .bindText({ argValues[i] }, { argValues[i] = it })
                            .validationInfo { field ->
                                arg.validate?.invoke(field.text)
                            }
                        if (i == 0) field.focused()
                    }
                }
            }
        }
    }
}
