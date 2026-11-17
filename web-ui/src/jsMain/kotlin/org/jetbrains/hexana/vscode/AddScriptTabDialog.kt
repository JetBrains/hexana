package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.shared.scripting.ScriptApiReference

/**
 * Dialog shown when the user clicks the "+" button to add a custom script tab.
 *
 * Besides picking an existing `.js` file it offers a runnable starting point —
 * the starter template or one of the bundled examples — because the hardest part
 * of writing a script tab used to be the empty file. The API listing is rendered
 * from [ScriptApiReference], the same source as the bundled `.d.ts` and the
 * IntelliJ dialog, so the three cannot drift apart.
 *
 * @param scriptPath the currently picked script file path (empty when nothing is selected yet)
 * @param existingScriptPaths paths of scripts already open as tabs (used for duplicate detection)
 * @param onBrowse called when the user clicks "Browse…" to trigger the host file picker
 * @param onCreateFromTemplate called with a file name and optional inline source; a
 *   null source means the host should copy the bundled example of that name
 * @param onOpenDts called with `raw = true` for the format-specific `binary.raw` types
 * @param showExamples whether the host can supply the bundled example scripts
 * @param onConfirm called with the selected path when the user clicks OK
 * @param onCancel called when the user cancels the dialog
 */
@Composable
fun AddScriptTabDialog(
    scriptPath: String,
    existingScriptPaths: Set<String>,
    onBrowse: () -> Unit,
    onCreateFromTemplate: (fileName: String, template: String?) -> Unit,
    onOpenDts: (raw: Boolean) -> Unit,
    showExamples: Boolean,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val isDuplicate = scriptPath.isNotBlank() && scriptPath in existingScriptPaths
    val canConfirm = scriptPath.isNotBlank() && !isDuplicate

    // Overlay backdrop
    Div({
        classes("hexana-dialog-overlay")
        onClick { onCancel() }
    }) {
        // Dialog box
        Div({
            classes("hexana-dialog")
            onClick { it.stopPropagation() }
        }) {
            // Title
            Div({ classes("hexana-dialog-title") }) {
                Text("Add Custom Script Tab")
            }

            // Body
            Div({ classes("hexana-dialog-body") }) {
                P {
                    Text("Create a custom analysis tab from a JavaScript file. ")
                    Text("The script runs against the open binary and renders text, or a ")
                    Text("searchable table when it starts with a table(...) header row.")
                }

                // Script file chooser
                Div({
                    classes("hexana-dialog-field")
                    style { property("margin-top", "12px") }
                }) {
                    Label { Text("Script file:") }
                    Div({
                        style {
                            property("display", "flex")
                            property("gap", "6px")
                            property("align-items", "center")
                        }
                    }) {
                        // Read-only text field showing the picked path
                        Input(org.jetbrains.compose.web.attributes.InputType.Text) {
                            classes("hexana-dialog-input")
                            value(scriptPath)
                            attr("readonly", "")
                            attr("placeholder", "No file selected")
                            style {
                                property("flex", "1")
                            }
                        }
                        Button({
                            classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                            onClick { onBrowse() }
                            style {
                                property("white-space", "nowrap")
                            }
                        }) {
                            Text("Browse…")
                        }
                    }
                    if (isDuplicate) {
                        Div({ classes("hexana-dialog-error") }) {
                            Text("This script is already open as a tab.")
                        }
                    }
                }

                // Runnable starting points
                Div({ style { property("margin-top", "12px") } }) {
                    Button({
                        classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                        onClick {
                            onCreateFromTemplate(
                                ScriptApiReference.STARTER_FILE_NAME,
                                ScriptApiReference.starterTemplate(),
                            )
                        }
                    }) {
                        Text("New Script from Template")
                    }
                    Div({
                        style {
                            property("margin-top", "4px")
                            property("opacity", "0.75")
                            property("font-size", "0.9em")
                        }
                    }) {
                        Text(
                            "Writes a runnable script into the workspace, opens it, and selects it here. " +
                                "The type definitions are placed next to it, so binary. completes as you edit."
                        )
                    }
                }

                if (showExamples) {
                    Div({
                        style {
                            property("margin-top", "12px")
                            property("margin-bottom", "4px")
                            property("font-weight", "bold")
                        }
                    }) {
                        Text("Or start from an example")
                    }
                    Ul({ style { property("margin", "0") } }) {
                        for (example in ScriptApiReference.examples) {
                            Li {
                                A(href = "#", {
                                    onClick { event ->
                                        event.preventDefault()
                                        onCreateFromTemplate(example.fileName, null)
                                    }
                                    style {
                                        property("color", "var(--vscode-textLink-foreground, #3794ff)")
                                        property("cursor", "pointer")
                                    }
                                }) {
                                    Text(example.title)
                                }
                                Span({ style { property("opacity", "0.75") } }) {
                                    Text(" — ${example.summary}")
                                }
                            }
                        }
                    }
                }

                // Available APIs, rendered from the shared reference. Collapsed by
                // default: it is reference material, and expanded it makes the
                // dialog several times taller than the controls that matter.
                var apisExpanded by remember { mutableStateOf(false) }
                Div({
                    style {
                        property("margin-top", "16px")
                        property("font-weight", "bold")
                        property("cursor", "pointer")
                        property("user-select", "none")
                    }
                    onClick { apisExpanded = !apisExpanded }
                }) {
                    Text(if (apisExpanded) "\u25BE Available APIs" else "\u25B8 Available APIs")
                }
                if (apisExpanded) {
                    Div({
                        style {
                            property("margin-top", "8px")
                            property("font-family", "var(--vscode-editor-font-family, monospace)")
                            property("line-height", "1.6")
                        }
                    }) {
                        for (line in ScriptApiReference.summaryLines()) {
                            val separator = line.indexOf(" \u2014 ")
                            Div {
                                if (separator < 0) {
                                    Text(line)
                                } else {
                                    B { Text(line.substring(0, separator)) }
                                    Text(line.substring(separator))
                                }
                            }
                        }
                    }
                }

                // Links to the generated references
                Div({ style { property("margin-top", "8px") } }) {
                    ReferenceLink("Open the scripting API reference") { onOpenDts(false) }
                }
                Div({ style { property("margin-top", "4px") } }) {
                    ReferenceLink("Open the format-specific types behind binary.raw") { onOpenDts(true) }
                }
            }

            // Buttons
            Div({ classes("hexana-dialog-buttons") }) {
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                    onClick { onCancel() }
                }) {
                    Text("Cancel")
                }
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-ok")
                    if (!canConfirm) attr("disabled", "")
                    onClick { if (canConfirm) onConfirm(scriptPath) }
                }) {
                    Text("OK")
                }
            }
        }
    }
}

@Composable
private fun ReferenceLink(label: String, onClick: () -> Unit) {
    A(href = "#", {
        onClick { event ->
            event.preventDefault()
            onClick()
        }
        style {
            property("color", "var(--vscode-textLink-foreground, #3794ff)")
            property("cursor", "pointer")
        }
    }) {
        Text(label)
    }
}
