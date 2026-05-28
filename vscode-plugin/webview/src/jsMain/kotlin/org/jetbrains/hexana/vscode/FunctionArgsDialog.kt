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
 * Result returned by the function-arguments dialog, carrying both the
 * collected argument values and the runtime / action chosen by the user.
 */
data class DialogRunResult(
    val args: List<String>,
    val runtime: String,
    val action: String,
)

/**
 * Available runtime options for the dialog dropdown.
 * [id] is the value sent to the extension host; [label] is shown in the UI.
 * [supportsDebug] controls whether the Debug button is enabled.
 */
data class RuntimeOption(
    val id: String,
    val label: String,
    val supportsDebug: Boolean = true,
)

/**
 * Standard runtime choices for core Wasm modules.
 */
val coreRuntimeOptions = listOf(
    RuntimeOption("wasmtime", "Wasmtime", supportsDebug = true),
    RuntimeOption("wamr", "WAMR", supportsDebug = true),
    RuntimeOption("graalwasm", "GraalWasm", supportsDebug = false),
    RuntimeOption("browser", "Default Browser", supportsDebug = false),
)

/**
 * Runtime choices for component-model binaries (only Wasmtime supports them).
 */
val componentRuntimeOptions = listOf(
    RuntimeOption("wasmtime", "Wasmtime", supportsDebug = true),
)

/**
 * Runtime configured via the `hexana.wasmRuntime` VS Code setting.
 * Updated from the `load-wasm` message and whenever the user picks a
 * runtime in a dialog (the choice is also persisted back to the setting).
 * A value of `"ask"` or `null` means "no preference — use the first option".
 */
var configuredRuntimeId: String? = null

/**
 * Composable modal overlay that collects function argument values.
 *
 * The dialog includes a runtime selector dropdown and both "Run" and "Debug"
 * buttons so the user can choose the runtime and action directly in the
 * dialog, avoiding the VS Code native picker.
 *
 * @param exportName the function name shown in the dialog title
 * @param args descriptors for each argument field
 * @param isComponent whether this is a component-model binary
 * @param forcedAction when set to `"run"` or `"debug"`, only the
 *   corresponding button is displayed; when `null` both buttons appear
 * @param onConfirm called with the collected argument values, runtime, and action
 * @param onCancel called when the user cancels
 */
@Composable
fun FunctionArgsDialog(
    exportName: String,
    args: List<FunctionArgDescriptor>,
    isComponent: Boolean = false,
    forcedAction: String? = null,
    onConfirm: (DialogRunResult) -> Unit,
    onCancel: () -> Unit,
) {
    val allRuntimeOptions = if (isComponent) componentRuntimeOptions else coreRuntimeOptions
    val runtimeOptions = if (forcedAction == "debug") allRuntimeOptions.filter { it.supportsDebug } else allRuntimeOptions
    if (runtimeOptions.isEmpty()) {
        onCancel()
        return
    }
    val values = remember(args) { mutableStateListOf(*args.map { it.defaultValue }.toTypedArray()) }
    val errors = remember(args) { mutableStateListOf(*Array(args.size) { null as String? }) }
    var selectedRuntime by remember {
        val defaultId = configuredRuntimeId
            ?.takeIf { id -> id != "ask" && runtimeOptions.any { it.id == id } }
            ?: runtimeOptions.first().id
        mutableStateOf(defaultId)
    }

    val hasErrors = errors.any { it != null }

    fun validateAll(): Boolean {
        var valid = true
        for ((i, arg) in args.withIndex()) {
            val err = arg.validate?.invoke(values[i])
            errors[i] = err
            if (err != null) valid = false
        }
        return valid
    }

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

                // Runtime selector
                Div({ classes("hexana-dialog-runtime-row") }) {
                    Label(forId = "hexana-runtime-select") {
                        Text("Runtime:")
                    }
                    Select({
                        id("hexana-runtime-select")
                        classes("hexana-dialog-runtime-select")
                        onChange { event ->
                            selectedRuntime = event.value ?: runtimeOptions.first().id
                        }
                    }) {
                        for (option in runtimeOptions) {
                            Option(option.id, { if (selectedRuntime == option.id) attr("selected", "") }) {
                                Text(option.label)
                            }
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

                if (forcedAction != "run") {
                    val debugSupported = runtimeOptions.firstOrNull { it.id == selectedRuntime }?.supportsDebug ?: false
                    Button({
                        classes("hexana-dialog-btn", "hexana-dialog-btn-debug")
                        if (hasErrors || !debugSupported) attr("disabled", "")
                        onClick {
                            if (validateAll()) {
                                configuredRuntimeId = selectedRuntime
                                postUpdateRuntimeSettingToHost(selectedRuntime)
                                onConfirm(DialogRunResult(values.toList(), selectedRuntime, "debug"))
                            }
                        }
                    }) {
                        Span({ classes("hexana-dialog-btn-icon", "hexana-dialog-icon-debug") })
                        Text(" Debug")
                    }
                }

                if (forcedAction != "debug") {
                    Button({
                        classes("hexana-dialog-btn", "hexana-dialog-btn-ok")
                        if (hasErrors) attr("disabled", "")
                        onClick {
                            if (validateAll()) {
                                configuredRuntimeId = selectedRuntime
                                postUpdateRuntimeSettingToHost(selectedRuntime)
                                onConfirm(DialogRunResult(values.toList(), selectedRuntime, "run"))
                            }
                        }
                    }) { Text("\u25B6 Run") }
                }
            }
        }
    }
}

/**
 * Result returned by the program-arguments dialog, carrying the
 * program arguments text and the runtime / action chosen by the user.
 */
data class ProgramArgsRunResult(
    val programArgs: String,
    val runtime: String,
    val action: String,
)

/**
 * Composable modal overlay that collects generic program arguments
 * (a single free-text field), matching the IntelliJ `ProgramArgsDialog`.
 *
 * The dialog includes a runtime selector dropdown and both "Run" and "Debug"
 * buttons so the user can choose the runtime and action directly in the
 * dialog, avoiding the VS Code native picker.
 *
 * Used for `wasi:cli/run` exports and `_start`/`_initialize` toolbar runs.
 *
 * @param forcedAction when set to `"run"` or `"debug"`, only the
 *   corresponding button is displayed; when `null` both buttons appear
 */
@Composable
fun ProgramArgsDialog(
    exportName: String,
    isComponent: Boolean = false,
    forcedAction: String? = null,
    onConfirm: (ProgramArgsRunResult) -> Unit,
    onCancel: () -> Unit,
) {
    val allRuntimeOptions = if (isComponent) componentRuntimeOptions else coreRuntimeOptions
    val runtimeOptions = if (forcedAction == "debug") allRuntimeOptions.filter { it.supportsDebug } else allRuntimeOptions
    if (runtimeOptions.isEmpty()) {
        onCancel()
        return
    }
    var text by remember { mutableStateOf("") }
    var selectedRuntime by remember {
        val defaultId = configuredRuntimeId
            ?.takeIf { id -> id != "ask" && runtimeOptions.any { it.id == id } }
            ?: runtimeOptions.first().id
        mutableStateOf(defaultId)
    }

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

                // Runtime selector
                Div({ classes("hexana-dialog-runtime-row") }) {
                    Label(forId = "hexana-program-runtime-select") {
                        Text("Runtime:")
                    }
                    Select({
                        id("hexana-program-runtime-select")
                        classes("hexana-dialog-runtime-select")
                        onChange { event ->
                            selectedRuntime = event.value ?: runtimeOptions.first().id
                        }
                    }) {
                        for (option in runtimeOptions) {
                            Option(option.id, { if (selectedRuntime == option.id) attr("selected", "") }) {
                                Text(option.label)
                            }
                        }
                    }
                }
            }
            Div({ classes("hexana-dialog-buttons") }) {
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                    onClick { onCancel() }
                }) { Text("Cancel") }

                if (forcedAction != "run") {
                    val debugSupported = runtimeOptions.firstOrNull { it.id == selectedRuntime }?.supportsDebug ?: false
                    Button({
                        classes("hexana-dialog-btn", "hexana-dialog-btn-debug")
                        if (!debugSupported) attr("disabled", "")
                        onClick {
                            configuredRuntimeId = selectedRuntime
                            postUpdateRuntimeSettingToHost(selectedRuntime)
                            onConfirm(ProgramArgsRunResult(text, selectedRuntime, "debug"))
                        }
                    }) {
                        Span({ classes("hexana-dialog-btn-icon", "hexana-dialog-icon-debug") })
                        Text(" Debug")
                    }
                }

                if (forcedAction != "debug") {
                    Button({
                        classes("hexana-dialog-btn", "hexana-dialog-btn-ok")
                        onClick {
                            configuredRuntimeId = selectedRuntime
                            postUpdateRuntimeSettingToHost(selectedRuntime)
                            onConfirm(ProgramArgsRunResult(text, selectedRuntime, "run"))
                        }
                    }) { Text("\u25B6 Run") }
                }
            }
        }
    }
}
