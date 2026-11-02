package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.shared.features.FILE_ASSOCIATIONS_PATH
import org.jetbrains.hexana.shared.features.FileAssociations

/**
 * Lists the parsers chosen by hand for individual files — the VS Code counterpart of the
 * IntelliJ `File Parser Associations` settings page.
 *
 * A dialog inside the editor webview for the reason [RuntimeTargetsDialog] is one: the list
 * is `.hexana/file_associations.json`, shared with the IntelliJ plugin. Entries are added
 * from the format badge of the file itself; here they are reviewed, changed, and removed.
 *
 * @param onConfirm receives the edited associations; the caller serializes them and hands
 *   them to the host, which owns the file write
 */
@Composable
fun FileAssociationsDialog(
    current: FileAssociations,
    onConfirm: (FileAssociations) -> Unit,
    onCancel: () -> Unit,
) {
    val rows = remember(current) {
        mutableStateListOf<Pair<String, BinaryParser>>().apply { addAll(current.associations.toList()) }
    }

    Div({
        classes("hexana-dialog-overlay")
        onClick { onCancel() }
    }) {
        Div({
            classes("hexana-dialog", "hexana-file-associations-dialog")
            onClick { it.stopPropagation() }
        }) {
            Div({ classes("hexana-dialog-title") }) { Text("File Parser Associations") }

            Div({ classes("hexana-dialog-body") }) {
                P {
                    Text("Files Hexana opens with a parser you chose instead of the one it detects. ")
                    Text("Choose one from the format badge at the top of the editor.")
                }
                P({ classes("hexana-dialog-hint") }) {
                    Text("Saved to $FILE_ASSOCIATIONS_PATH in the workspace, so the IntelliJ plugin ")
                    Text("uses the same choices.")
                }

                if (rows.isEmpty()) {
                    P { Text("No parser chosen for any file. Every file is detected automatically.") }
                }
                for ((index, row) in rows.withIndex()) {
                    val (path, parser) = row
                    // Keyed by path: removing a row must not hand its DOM node — and the
                    // browser-owned value of its <select> — to the row that moves up.
                    key(path) { Div({ classes("hexana-file-associations-row") }) {
                        Span({
                            classes("hexana-file-associations-path")
                            attr("title", path)
                        }) { Text(path) }
                        Select({
                            classes("hexana-dialog-input")
                            onChange { event ->
                                val chosen = event.value?.let(BinaryParser::fromId) ?: return@onChange
                                rows[index] = path to chosen
                            }
                        }) {
                            for (entry in BinaryParser.entries) {
                                Option(entry.id, { if (entry == parser) attr("selected", "") }) {
                                    Text(entry.displayName)
                                }
                            }
                        }
                        Button({
                            classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                            attr("title", "Detect this file automatically again")
                            onClick { rows.removeAt(index) }
                        }) { Text("Remove") }
                    } }
                }
            }

            Div({ classes("hexana-dialog-buttons") }) {
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                    onClick { onCancel() }
                }) { Text("Cancel") }
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-ok")
                    onClick { onConfirm(FileAssociations(rows.toMap(), current.schemaVersion)) }
                }) { Text("Save") }
            }
        }
    }
}
