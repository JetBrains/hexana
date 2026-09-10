package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * Folds [panels] behind a single parent tab named [name], which switches between them with an
 * inner tab strip - the same shape as the grouped table tabs, for panels that are not tables.
 *
 * A lone panel is returned unchanged: a parent tab wrapping one child costs a click and saves
 * nothing.
 */
internal fun groupPanels(name: String, panels: List<Panel>): List<Panel> = when {
    panels.isEmpty() -> emptyList()
    panels.size == 1 -> panels
    else -> listOf(Panel(name) { GroupedPanelContent(panels) })
}

@Composable
private fun GroupedPanelContent(panels: List<Panel>) {
    var selectedIndex by remember(panels) { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(
            tabs = panels.mapIndexed { index, panel ->
                TabData.Default(
                    selected = index == selectedIndex,
                    content = { Text(panel.description) },
                    onClick = { selectedIndex = index },
                    closable = false,
                )
            },
            style = JewelTheme.defaultTabStyle,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            panels.getOrNull(selectedIndex)?.render?.invoke()
        }
    }
}
