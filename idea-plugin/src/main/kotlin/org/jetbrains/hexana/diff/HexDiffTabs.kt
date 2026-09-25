package org.jetbrains.hexana.diff

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.TabPriority
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.jetbrains.hexana.shared.diff.TableTabDiff
import org.jetbrains.hexana.shared.diff.TableTabDiffer
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.ScrollableTabStrip
import org.jetbrains.hexana.ui.panel.TableTabContent
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.Text

/**
 * The table tabs of both binaries, diffed pairwise: every flat tab either side's
 * [org.jetbrains.hexana.shared.TableTabsProvider]s produce, paired by name within its provider.
 * Tree tabs are left out for now.
 *
 * Blocking — each provider parses its side — so hosts call it off the UI thread. A provider
 * that fails on one side contributes nothing for that side rather than failing the diff.
 */
internal fun diffTableTabs(
    left: BinaryProvider?,
    right: BinaryProvider?,
    leftName: String?,
    rightName: String?,
    onError: (Throwable) -> Unit,
    checkCanceled: () -> Unit,
    onRowClick: (leftOffset: Int?, rightOffset: Int?) -> Unit,
): List<TableTabDiff> {
    fun tabsOf(index: Int, provider: BinaryProvider?, name: String?): List<TableTabDescriptor> {
        provider ?: return emptyList()
        return try {
            TableTabsProviderRegistry.providers[index].tableTabs(provider, TableTabsContext(fileName = name))
        } catch (e: Exception) {
            onError(e)
            emptyList()
        }
    }
    return TableTabsProviderRegistry.providers.indices.flatMap { index ->
        checkCanceled()
        val leftTabs = tabsOf(index, left, leftName)
        val rightTabs = tabsOf(index, right, rightName)
        val priority = (leftTabs + rightTabs).associate { TableTabDiffer.baseName(it.name) to it.priority }
        TableTabDiffer.diffTabs(leftTabs, rightTabs) { l, r -> onRowClick(l?.offset, r?.offset) }
            .map { Triple(priority[it.name] ?: TabPriority.Medium, index, it) }
    }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
}

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
            TableTabContent(tab.descriptor, tableState)
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
