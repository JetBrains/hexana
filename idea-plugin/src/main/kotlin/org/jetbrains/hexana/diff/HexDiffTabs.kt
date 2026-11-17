package org.jetbrains.hexana.diff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.shared.diff.TableTabDiff
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.ScrollableTabStrip
import org.jetbrains.hexana.ui.panel.TableTabContent
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.Text

/**
 * The tab strip under the hex diff: one tab per diffed table, a changed tab marked with its
 * number of changed rows, and the merged table below it.
 */
@Composable
internal fun HexDiffTabsPanel(tabs: List<TableTabDiff>, tableState: HexViewStateHolder) {
    var selected by remember(tabs) { mutableIntStateOf(tabs.indexOfFirst { it.changed > 0 }.coerceAtLeast(0)) }
    Column(modifier = Modifier.fillMaxSize()) {
        // Scrolls when the tabs do not fit, like the single-file editor's tab row.
        ScrollableTabStrip(
            tabs = tabs.mapIndexed { index, tab ->
                TabData.Default(
                    selected = index == selected,
                    content = { TabTitle(tab) },
                    closable = false,
                    onClick = { selected = index },
                )
            },
            selectedIndex = selected,
            resetKey = tabs,
            modifier = Modifier.fillMaxWidth(),
        )
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        val tab = tabs.getOrNull(selected) ?: return@Column
        Box(modifier = Modifier.weight(1f).padding(8.dp)) {
            // Every tab renders from this one call site; without a key the table's remembered
            // sort column, selection and search carry over to the next tab — and a sort column
            // past the next table's width throws.
            key(selected) {
                TableTabContent(tab.descriptor, tableState)
            }
        }
    }
}

/** Holds the tabs' place while both sides' tables are built and diffed. */
@Composable
internal fun HexDiffTabsLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Text("Loading extra comparison data…", style = TextStyle(color = HexanaTheme.hexTextColor))
        }
    }
}

@Composable
private fun TabTitle(tab: TableTabDiff) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (tab.changed > 0) {
            Text("● ", style = TextStyle(color = HexanaTheme.diffModifiedColor.copy(alpha = 1f)))
            Text(tab.name, style = TextStyle(fontWeight = FontWeight.Bold))
            Text(
                "  ${"%,d".format(tab.changed)}",
                style = TextStyle(fontFamily = FontFamily.Monospace, color = HexanaTheme.hexTextColor),
            )
        } else {
            Text(tab.name, style = TextStyle(color = HexanaTheme.hexTextColor))
        }
    }
}
