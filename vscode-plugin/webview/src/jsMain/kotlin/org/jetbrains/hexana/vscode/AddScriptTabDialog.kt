package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.dom.*

/**
 * Dialog shown when the user clicks the "+" button to add a custom script tab.
 *
 * Explains the concept, documents the available JS APIs (`print`, `binary`),
 * provides an inline file chooser (text field + Browse button) for `.js` files,
 * and a link to open the `.d.ts` API reference.
 *
 * @param scriptPath the currently picked script file path (empty when nothing is selected yet)
 * @param existingScriptPaths paths of scripts already open as tabs (used for duplicate detection)
 * @param onBrowse called when the user clicks "Browse…" to trigger the host file picker
 * @param onOpenDts called when the user clicks the .d.ts link
 * @param onConfirm called with the selected path when the user clicks OK
 * @param onCancel called when the user cancels the dialog
 */
@Composable
fun AddScriptTabDialog(
    scriptPath: String,
    existingScriptPaths: Set<String>,
    onBrowse: () -> Unit,
    onOpenDts: () -> Unit,
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
                    Text("Create a custom analysis tab by selecting a JavaScript file. ")
                    Text("The script runs with access to the opened binary and can produce ")
                    Text("text or tabular (CSV/TSV) output displayed in the tab.")
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

                // Available APIs
                Div({
                    style {
                        property("margin-top", "20px")
                        property("margin-bottom", "8px")
                        property("font-weight", "bold")
                    }
                }) {
                    Text("Available APIs")
                }
                Div({
                    style {
                        property("font-family", "var(--vscode-editor-font-family, monospace)")
                        property("line-height", "1.6")
                    }
                }) {
                    Div {
                        B { Text("print(text)") }
                        Text(" — appends a line to the tab output.")
                    }
                    Div {
                        B { Text("binary") }
                        Text(" — the current file's BinaryProvider object with properties such as:")
                    }
                    Div({
                        style { property("padding-left", "16px") }
                    }) {
                        Div {
                            B { Text("binary.strings") }
                            Text(" — list of extracted strings (each has .value)")
                        }
                        Div {
                            B { Text("binary.bytes") }
                            Text(" — raw byte buffer of the file")
                        }
                        Div {
                            B { Text("binary.sizeAnalysis") }
                            Text(" — hierarchical size breakdown (if available)")
                        }
                    }
                }

                // Link to .d.ts
                Div({
                    style { property("margin-top", "8px") }
                }) {
                    A(href = "#", {
                        onClick { e ->
                            e.preventDefault()
                            onOpenDts()
                        }
                        style {
                            property("color", "var(--vscode-textLink-foreground, #3794ff)")
                            property("cursor", "pointer")
                        }
                    }) {
                        Text("Open complete API .d.ts definitions")
                    }
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
