package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.shared.features.FILE_ASSOCIATIONS_PATH

/**
 * What the format badge needs to offer a parser choice for the open file — the web
 * counterpart of the IntelliJ `ParserChoice`.
 *
 * Only built when the host can write `.hexana/file_associations.json` and the file has a
 * key in it, so a `null` choice leaves the badge a plain label.
 *
 * @param chosen the parser the associations name for this file, or `null` when detected
 * @param failed whether [chosen] could not read the bytes, so detection was used instead
 * @param onChoose records a new choice; `null` returns the file to automatic detection
 * @param onEdit opens `.hexana/file_associations.json` itself for editing
 */
class ParserChoice(
    val chosen: BinaryParser?,
    val failed: Boolean,
    val onChoose: (BinaryParser?) -> Unit,
    val onEdit: () -> Unit,
)

/**
 * The format badge of the toolbar. With a [choice] it opens a menu of every parser,
 * automatic detection first, and marks the current one.
 */
@Composable
internal fun FormatBadge(label: String, choice: ParserChoice?) {
    if (choice == null) {
        Span({ classes("hexana-badge", "hexana-format-badge") }) { Text(label) }
        return
    }

    var menuVisible by remember { mutableStateOf(false) }
    val failed = choice.chosen != null && choice.failed
    val tooltip = when {
        choice.chosen == null -> "Detected automatically. Click to choose a parser for this file."
        failed -> "The ${choice.chosen.displayName} parser chosen in $FILE_ASSOCIATIONS_PATH cannot read " +
            "this file, so it was detected automatically. Click to choose another."
        else -> "Parser chosen for this file in $FILE_ASSOCIATIONS_PATH. Click to change it."
    }

    Div({ classes("hexana-format-picker") }) {
        Span({
            classes("hexana-badge", "hexana-format-badge", "hexana-format-badge-clickable")
            if (failed) classes("hexana-format-badge-warning")
            attr("title", tooltip)
            onClick { event ->
                event.stopPropagation()
                menuVisible = !menuVisible
            }
        }) {
            Text("$label ▾")
        }

        if (menuVisible) {
            // Invisible overlay closing the menu on a click anywhere else, as the Share
            // Feedback dropdown does.
            Div({
                classes("hexana-format-menu-dismiss")
                onClick { menuVisible = false }
            }) {}

            Div({ classes("hexana-format-menu") }) {
                Div({ classes("hexana-format-menu-title") }) { Text("Parse File As") }
                ParserMenuItem("Auto-detect", selected = choice.chosen == null) {
                    menuVisible = false
                    if (choice.chosen != null) choice.onChoose(null)
                }
                Div({ classes("hexana-format-menu-separator") }) {}
                for (parser in BinaryParser.entries) {
                    ParserMenuItem(parser.displayName, selected = choice.chosen == parser) {
                        menuVisible = false
                        if (choice.chosen != parser) choice.onChoose(parser)
                    }
                    if (parser == BinaryParser.GENERIC) {
                        Div({ classes("hexana-format-menu-separator") }) {}
                    }
                }
                Div({ classes("hexana-format-menu-separator") }) {}
                ParserMenuItem("Edit File Associations\u2026", selected = false) {
                    menuVisible = false
                    choice.onEdit()
                }
            }
        }
    }
}

@Composable
private fun ParserMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Div({
        classes("hexana-format-menu-item")
        if (selected) classes("hexana-format-menu-item-selected")
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        // A fixed-width check column, so the labels line up whichever row is selected.
        Span({ classes("hexana-format-menu-check") }) { Text(if (selected) "✓" else "") }
        Text(label)
    }
}
