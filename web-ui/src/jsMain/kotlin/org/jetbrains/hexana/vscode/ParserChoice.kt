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
import org.jetbrains.hexana.shared.features.ParserRef
import org.jetbrains.hexana.shared.features.ref
import org.jetbrains.hexana.shared.scripting.ProviderRegistrationInfo

/**
 * What the format badge needs to offer a parser choice for the open file — the web
 * counterpart of the IntelliJ `ParserChoice`.
 *
 * Only built when the host can write `.hexana/file_associations.json` and the file has a
 * key in it, so a `null` choice leaves the badge a plain label.
 *
 * @param chosen the parser the associations name for this file, or `null` when detected
 * @param failure why [chosen] is not fully in effect, shown in the tooltip; `null` when it is
 * @param labelOverride the badge text instead of the detected format's, for a scripted
 *   provider in effect
 * @param scriptedProviders the providers the workspace's provider scripts define
 * @param onChoose records a new choice; `null` returns the file to automatic detection
 * @param onEdit opens `.hexana/file_associations.json` itself for editing
 * @param onCreateScript has the host create a provider script; it is assigned to this file
 */
class ParserChoice(
    val chosen: ParserRef?,
    val failure: String?,
    val labelOverride: String?,
    val scriptedProviders: List<ProviderRegistrationInfo>,
    val onChoose: (ParserRef?) -> Unit,
    val onEdit: () -> Unit,
    val onCreateScript: () -> Unit,
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
    val failure = choice.failure?.takeIf { choice.chosen != null }
    val tooltip = when {
        choice.chosen == null -> "Detected automatically. Click to choose a parser for this file."
        failure != null -> "$failure Click to choose another parser."
        else -> "Parser chosen for this file in $FILE_ASSOCIATIONS_PATH. Click to change it."
    }

    Div({ classes("hexana-format-picker") }) {
        Span({
            classes("hexana-badge", "hexana-format-badge", "hexana-format-badge-clickable")
            if (failure != null) classes("hexana-format-badge-warning")
            attr("title", tooltip)
            onClick { event ->
                event.stopPropagation()
                menuVisible = !menuVisible
            }
        }) {
            Text("${choice.labelOverride ?: label} \u25BE")
        }

        if (menuVisible) {
            // Invisible overlay closing the menu on a click anywhere else, as the Share
            // Feedback dropdown does.
            Div({
                classes("hexana-format-menu-dismiss")
                onClick { menuVisible = false }
            }) {}

            fun choose(ref: ParserRef?) {
                menuVisible = false
                if (ref != choice.chosen) choice.onChoose(ref)
            }

            Div({ classes("hexana-format-menu") }) {
                Div({ classes("hexana-format-menu-title") }) { Text("Parse File As") }
                ParserMenuItem("Auto-detect", selected = choice.chosen == null) { choose(null) }
                Div({ classes("hexana-format-menu-separator") }) {}
                for (parser in BinaryParser.entries) {
                    ParserMenuItem(parser.displayName, selected = choice.chosen == parser.ref()) { choose(parser.ref()) }
                    if (parser == BinaryParser.GENERIC) {
                        Div({ classes("hexana-format-menu-separator") }) {}
                    }
                }
                // A chosen provider whose script is not loaded stays listed, so the
                // checkmark has a row and the choice can be seen for what it is.
                val scripted = choice.scriptedProviders.map { ParserRef.Scripted(it.id) to it.name } +
                    listOfNotNull(
                        (choice.chosen as? ParserRef.Scripted)
                            ?.takeIf { chosen -> choice.scriptedProviders.none { it.id == chosen.id } }
                            ?.let { it to "${it.id} (not loaded)" },
                    )
                Div({ classes("hexana-format-menu-separator") }) {}
                Div({ classes("hexana-format-menu-title") }) { Text("Scripts") }
                for ((ref, name) in scripted) {
                    ParserMenuItem(name, selected = choice.chosen == ref) { choose(ref) }
                }
                ParserMenuItem("Create New Provider Script\u2026", selected = false) {
                    menuVisible = false
                    choice.onCreateScript()
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
