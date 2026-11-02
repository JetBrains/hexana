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
 * @param failed whether [chosen] could not read the bytes, so detection was used instead
 * @param onChoose records a new choice; `null` returns the file to automatic detection
 * @param onEdit opens the settings page listing every file's choice
 */
internal class ParserChoice(
    val chosen: BinaryParser?,
    val failed: Boolean,
    val onChoose: (BinaryParser?) -> Unit,
    val onEdit: () -> Unit,
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

    val failed = choice.chosen != null && choice.failed
    val badgeBackground = if (failed) warningBadgeBackground() else background
    val badgeForeground = if (failed) warningBadgeForeground() else foreground
    val tooltip = when {
        choice.chosen == null -> "Detected automatically. Click to choose a parser for this file."
        failed -> "The ${choice.chosen.displayName} parser chosen in $FILE_ASSOCIATIONS_PATH cannot read " +
            "this file, so it was detected automatically. Click to choose another."
        else -> "Parser chosen for this file in $FILE_ASSOCIATIONS_PATH. Click to change it."
    }

    Tooltip(tooltip = { Text(tooltip) }) {
        Text(
            text = "$label ▾",
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
 * One row of the parser popup: a parser, `null` for automatic detection, or — with
 * [isEdit] — the link to the settings page that closes the list.
 */
private class ParserItem(val parser: BinaryParser?, val isEdit: Boolean = false)

/**
 * Shows the parser list at the pointer. A platform list popup rather than a Compose menu:
 * the description bar is a 40 dp strip, and a platform popup is a window of its own that
 * neither the strip nor the editor clips. It also brings speed search for free.
 */
private fun showParserPopup(choice: ParserChoice) {
    val items = listOf(ParserItem(null)) + BinaryParser.entries.map(::ParserItem) +
        ParserItem(null, isEdit = true)
    val step = object : BaseListPopupStep<ParserItem>("Parse File As", items) {
        override fun getTextFor(value: ParserItem): String = when {
            value.isEdit -> "Edit File Associations\u2026"
            else -> value.parser?.displayName ?: "Auto-detect"
        }

        override fun getIconFor(value: ParserItem): Icon? = when {
            value.isEdit -> AllIcons.General.Settings
            value.parser == choice.chosen -> AllIcons.Actions.Checked
            else -> null
        }

        override fun getSeparatorAbove(value: ParserItem): ListSeparator? = when {
            value.isEdit -> ListSeparator()
            value.parser == BinaryParser.GENERIC -> ListSeparator()
            value.parser == BinaryParser.entries.first { it != BinaryParser.GENERIC } -> ListSeparator("Formats")
            else -> null
        }

        override fun getDefaultOptionIndex(): Int =
            items.indexOfFirst { !it.isEdit && it.parser == choice.chosen }

        override fun isSpeedSearchEnabled(): Boolean = true

        override fun onChosen(selectedValue: ParserItem, finalChoice: Boolean): PopupStep<*>? {
            // The settings dialog is modal; opened from the final step, once the popup is gone.
            if (selectedValue.isEdit) return doFinalStep { choice.onEdit() }
            if (selectedValue.parser != choice.chosen) choice.onChoose(selectedValue.parser)
            return FINAL_CHOICE
        }
    }
    val popup = JBPopupFactory.getInstance().createListPopup(step)
    val pointer = MouseInfo.getPointerInfo()?.location
    if (pointer != null) popup.show(RelativePoint.fromScreen(pointer)) else popup.showInFocusCenter()
}
