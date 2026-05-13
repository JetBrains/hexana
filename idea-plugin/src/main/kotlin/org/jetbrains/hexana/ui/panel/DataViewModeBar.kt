package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.providers.format.DataViewMode
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * Inline pill bar shown above a Data-section chunk's body rows when the
 * user expands it. Six modes (`u8`, `u16`, `u32`, `u64`, `ascii`, `utf8`);
 * the current mode is highlighted with a filled background and bold
 * weight. Clicking a mode invokes [onSelect].
 *
 * Lives inline in the [LazyColumn] item slot rather than a separate
 * floating control so the bar scrolls with its chunk — moving away from
 * the chunk shouldn't leave a phantom mode picker pointing at nothing.
 */
@Composable
internal fun DataViewModeBar(
    current: DataViewMode,
    onSelect: (DataViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            // Same indent as the expander row so the bar lines up under it.
            .padding(start = 90.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (mode in DataViewMode.entries) {
            val selected = mode == current
            val bg = if (selected) HexanaTheme.textColor.copy(alpha = 0.15f) else Color.Transparent
            Text(
                text = labelFor(mode),
                style = ROW_TEXT_STYLE.copy(
                    color = HexanaTheme.textColor,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                ),
                modifier = Modifier
                    .background(bg, shape = RoundedCornerShape(3.dp))
                    .clickable { if (!selected) onSelect(mode) }
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

private fun labelFor(mode: DataViewMode): String = when (mode) {
    DataViewMode.U8_HEX -> "u8"
    DataViewMode.U16_HEX -> "u16"
    DataViewMode.U32_HEX -> "u32"
    DataViewMode.U64_HEX -> "u64"
    DataViewMode.ASCII -> "ascii"
    DataViewMode.UTF8 -> "utf8"
}
