package org.jetbrains.hexana.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.shared.features.FILE_ASSOCIATIONS_PATH
import org.jetbrains.hexana.shared.features.ParserRef
import org.jetbrains.hexana.shared.features.ref
import org.jetbrains.hexana.shared.scripting.ProviderRegistrationInfo
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.awt.MouseInfo
import javax.swing.Icon

/**
 * What the format badge needs to offer a parser choice for the open file.
 *
 * Only built for files that can carry an association — on the local disk, inside the
 * project — so a `null` [ParserChoice] means the badge stays a plain label.
 *
 * @param chosen the parser `.hexana/file_associations.json` names for the file, or `null`
 *   when it was detected automatically
 * @param failure why [chosen] is not fully in effect — its parser cannot read the bytes, its
 *   provider script is missing or failed — shown in the badge's tooltip; `null` when it is
 * @param labelOverride the badge text instead of the detected format's, for a scripted
 *   provider in effect
 * @param scriptedProviders the providers the project's provider scripts define, offered in
 *   the menu's "Scripts" section
 * @param onChoose records a new choice; `null` returns the file to automatic detection
 * @param onEdit opens the settings page listing every file's choice
 * @param onCreateScript writes a new provider script, assigns it to this file and opens it
 */
internal class ParserChoice(
    val chosen: ParserRef?,
    val failure: String?,
    val labelOverride: String?,
    val scriptedProviders: List<ProviderRegistrationInfo>,
    val onChoose: (ParserRef?) -> Unit,
    val onEdit: () -> Unit,
    val onCreateScript: () -> Unit,
)

/**
 * The format label at the head of the description bar. With a [choice] it is also the
 * parser picker: clicking it lists every parser, with automatic detection first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FormatBadge(
    label: String,
    choice: ParserChoice?,
    background: Color,
    foreground: Color,
) {
    if (choice == null) {
        Text(
            text = label,
            modifier = Modifier
                .background(background, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = TextStyle(fontSize = 11.sp, color = foreground),
        )
        return
    }

    val failure = choice.failure?.takeIf { choice.chosen != null }
    val badgeBackground = if (failure != null) warningBadgeBackground() else background
    val badgeForeground = if (failure != null) warningBadgeForeground() else foreground
    val tooltip = when {
        choice.chosen == null -> "Detected automatically. Click to choose a parser for this file."
        failure != null -> "$failure Click to choose another parser."
        else -> "Parser chosen for this file in $FILE_ASSOCIATIONS_PATH. Click to change it."
    }

    Tooltip(tooltip = { Text(tooltip) }) {
        Text(
            text = "${choice.labelOverride ?: label} \u25BE",
            modifier = Modifier
                .background(badgeBackground, RoundedCornerShape(4.dp))
                .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
                .clickable { showParserPopup(choice) }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = TextStyle(fontSize = 11.sp, color = badgeForeground),
        )
    }
}

/**
 * One row of the parser popup: a parser to choose ([ref] `null` for automatic detection), or —
 * with an [action] — a command such as creating a provider script.
 */
private class ParserItem(
    val ref: ParserRef?,
    val text: String,
    val separatorAbove: ListSeparator? = null,
    val action: ParserMenuAction? = null,
)

/** The rows that do something instead of choosing a parser. */
private enum class ParserMenuAction { CREATE_SCRIPT, EDIT_ASSOCIATIONS }

/** The popup's rows: detection, generic, the built-in formats, the scripts' providers, settings. */
private fun parserItems(choice: ParserChoice): List<ParserItem> = buildList {
    add(ParserItem(null, "Auto-detect"))
    add(ParserItem(BinaryParser.GENERIC.ref(), BinaryParser.GENERIC.displayName, ListSeparator()))
    BinaryParser.entries.filter { it != BinaryParser.GENERIC }.forEachIndexed { index, parser ->
        add(ParserItem(parser.ref(), parser.displayName, if (index == 0) ListSeparator("Formats") else null))
    }
    // A chosen provider whose script is not loaded stays listed, so the checkmark has a row
    // to sit on and the choice can be seen for what it is.
    val scripted = choice.scriptedProviders.map { ParserRef.Scripted(it.id) to it.name } +
        listOfNotNull(
            (choice.chosen as? ParserRef.Scripted)
                ?.takeIf { chosen -> choice.scriptedProviders.none { it.id == chosen.id } }
                ?.let { it to "${it.id} (not loaded)" },
        )
    scripted.forEachIndexed { index, (ref, name) ->
        add(ParserItem(ref, name, if (index == 0) ListSeparator("Scripts") else null))
    }
    // Ends the Scripts section, or is all of it while the project has no provider scripts.
    add(
        ParserItem(
            null, "Create New Provider Script\u2026",
            if (scripted.isEmpty()) ListSeparator("Scripts") else null,
            ParserMenuAction.CREATE_SCRIPT,
        ),
    )
    add(ParserItem(null, "Edit File Associations\u2026", ListSeparator(), ParserMenuAction.EDIT_ASSOCIATIONS))
}

/**
 * Shows the parser list at the pointer. A platform list popup rather than a Compose menu:
 * the description bar is a 40 dp strip, and a platform popup is a window of its own that
 * neither the strip nor the editor clips. It also brings speed search for free.
 */
private fun showParserPopup(choice: ParserChoice) {
    val items = parserItems(choice)
    val step = object : BaseListPopupStep<ParserItem>("Parse File As", items) {
        override fun getTextFor(value: ParserItem): String = value.text

        override fun getIconFor(value: ParserItem): Icon? = when (value.action) {
            ParserMenuAction.CREATE_SCRIPT -> AllIcons.General.Add
            ParserMenuAction.EDIT_ASSOCIATIONS -> AllIcons.General.Settings
            null -> if (value.ref == choice.chosen) AllIcons.Actions.Checked else null
        }

        override fun getSeparatorAbove(value: ParserItem): ListSeparator? = value.separatorAbove

        override fun getDefaultOptionIndex(): Int =
            items.indexOfFirst { it.action == null && it.ref == choice.chosen }

        override fun isSpeedSearchEnabled(): Boolean = true

        override fun onChosen(selectedValue: ParserItem, finalChoice: Boolean): PopupStep<*>? {
            // Both open modal dialogs; run from the final step, once the popup is gone.
            when (selectedValue.action) {
                ParserMenuAction.CREATE_SCRIPT -> return doFinalStep { choice.onCreateScript() }
                ParserMenuAction.EDIT_ASSOCIATIONS -> return doFinalStep { choice.onEdit() }
                null -> Unit
            }
            if (selectedValue.ref != choice.chosen) choice.onChoose(selectedValue.ref)
            return FINAL_CHOICE
        }
    }
    val popup = JBPopupFactory.getInstance().createListPopup(step)
    val pointer = MouseInfo.getPointerInfo()?.location
    if (pointer != null) popup.show(RelativePoint.fromScreen(pointer)) else popup.showInFocusCenter()
}
