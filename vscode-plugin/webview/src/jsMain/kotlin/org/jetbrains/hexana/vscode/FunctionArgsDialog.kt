package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.*

/**
 * Descriptor for a single function argument field in the dialog.
 */
data class FunctionArgDescriptor(
    val label: String,
    val typeName: String,
    val defaultValue: String = "0",
    val enumEntries: List<String>? = null,
    val validate: ((String) -> String?)? = null,
)

/**
 * Composable modal overlay that collects function argument values.
 *
 * @param exportName the function name shown in the dialog title
 * @param args descriptors for each argument field
 * @param onConfirm called with the collected argument values when the user confirms
 * @param onCancel called when the user cancels
 */
@Composable
fun FunctionArgsDialog(
    exportName: String,
    args: List<FunctionArgDescriptor>,
    onConfirm: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val values = remember(args) { mutableStateListOf(*args.map { it.defaultValue }.toTypedArray()) }
    val errors = remember(args) { mutableStateListOf(*Array(args.size) { null as String? }) }

    // Overlay backdrop
    Div({
        classes("hexana-dialog-overlay")
        onClick { onCancel() }
    }) {
        // Dialog box — stop propagation so clicking inside doesn't close it
        Div({
            classes("hexana-dialog")
            onClick { it.stopPropagation() }
        }) {
            // Title
            Div({ classes("hexana-dialog-title") }) {
                Text("Arguments for '$exportName'")
            }

            // Argument fields
            Div({ classes("hexana-dialog-body") }) {
                for ((i, arg) in args.withIndex()) {
                    Div({ classes("hexana-dialog-field") }) {
                        Label(forId = "hexana-arg-$i") {
                            Text("${arg.label} (${arg.typeName}):")
                        }
                        val entries = arg.enumEntries
                        if (entries != null) {
                            Select({
                                id("hexana-arg-$i")
                                classes("hexana-dialog-input")
                                onChange { event ->
                                    val selected = event.value ?: entries.firstOrNull() ?: ""
                                    values[i] = selected
                                    errors[i] = arg.validate?.invoke(selected)
                                }
                            }) {
                                for (entry in entries) {
                                    Option(entry, { if (values[i] == entry) attr("selected", "") }) {
                                        Text(entry)
                                    }
                                }
                            }
                        } else {
                            Input(InputType.Text) {
                                id("hexana-arg-$i")
                                classes("hexana-dialog-input")
                                value(values[i])
                                placeholder(arg.typeName)
                                onInput { event ->
                                    values[i] = event.value
                                    errors[i] = arg.validate?.invoke(event.value)
                                }
                            }
                        }
                        val error = errors[i]
                        if (error != null) {
                            Div({ classes("hexana-dialog-error") }) { Text(error) }
                        }
                    }
                }
            }

            // Buttons
            Div({ classes("hexana-dialog-buttons") }) {
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                    onClick { onCancel() }
                }) { Text("Cancel") }
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-ok")
                    val hasErrors = errors.any { it != null }
                    if (hasErrors) attr("disabled", "")
                    onClick {
                        // Re-validate all fields before confirming
                        var valid = true
                        for ((i, arg) in args.withIndex()) {
                            val err = arg.validate?.invoke(values[i])
                            errors[i] = err
                            if (err != null) valid = false
                        }
                        if (valid) {
                            onConfirm(values.toList())
                        }
                    }
                }) { Text("Run") }
            }
        }
    }
}

/**
 * Composable modal overlay that collects generic program arguments
 * (a single free-text field), matching the IntelliJ `ProgramArgsDialog`.
 *
 * Used for `wasi:cli/run` exports and `_start`/`_initialize` toolbar runs.
 */
@Composable
fun ProgramArgsDialog(
    exportName: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    Div({
        classes("hexana-dialog-overlay")
        onClick { onCancel() }
    }) {
        Div({
            classes("hexana-dialog")
            onClick { it.stopPropagation() }
        }) {
            Div({ classes("hexana-dialog-title") }) {
                Text("Program Arguments for '$exportName'")
            }
            Div({ classes("hexana-dialog-body") }) {
                Div({ classes("hexana-dialog-field") }) {
                    Label(forId = "hexana-program-args") {
                        Text("Arguments:")
                    }
                    Input(InputType.Text) {
                        id("hexana-program-args")
                        classes("hexana-dialog-input")
                        value(text)
                        placeholder("Space-separated command-line arguments")
                        onInput { event -> text = event.value }
                    }
                }
            }
            Div({ classes("hexana-dialog-buttons") }) {
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                    onClick { onCancel() }
                }) { Text("Cancel") }
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-ok")
                    onClick { onConfirm(text) }
                }) { Text("Run") }
            }
        }
    }
}
