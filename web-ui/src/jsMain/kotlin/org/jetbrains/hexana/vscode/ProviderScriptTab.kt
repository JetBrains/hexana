package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.shared.scripting.SliceColumnType

/** Rows a provider tab reads before stopping; the same order of magnitude as a script table. */
private const val MAX_PROVIDER_TAB_ROWS = 10_000

/** Rows asked for per `rows()` call. */
private const val PROVIDER_TAB_PAGE = 500

/** The rows a tab produced, or why it stopped. */
private class ProviderTabRows(val rows: List<Pair<Long?, List<String>>>, val error: String?, val truncated: Boolean)

/**
 * The editor tab for a `hexana.defineTab` tab: its rows, read page by page from the script,
 * in the product's searchable table. Clicking a row selects its anchor — or the value of its
 * first `offset` column — in the hex view, as a row of a built-in table does.
 */
fun providerScriptPanelTab(bound: BoundScriptTab): PanelTab =
    PanelTab(bound.tab.info.name.removePrefix("user:"), padding = false) { selection ->
        ProviderScriptTabContent(bound, selection)
    }

@Composable
private fun ProviderScriptTabContent(bound: BoundScriptTab, selection: HexSelectionState) {
    val tab = bound.tab
    // Read once per tab instance: the tab is rebuilt whenever the scripts or the binary change.
    val loaded = remember(bound) { readAllRows(bound) }
    val offsetColumn = remember(tab) { tab.info.columns.indexOfFirst { it.type == SliceColumnType.OFFSET } }

    if (loaded.error != null) {
        Div({ classes("hexana-provider-tab-error") }) { Text("Script error: ${loaded.error}") }
    }
    if (loaded.truncated) {
        Div({ classes("hexana-dialog-hint") }) { Text("Showing the first $MAX_PROVIDER_TAB_ROWS rows.") }
    }
    SearchableTable(headers = tab.info.columns.map { it.name }, alwaysShowSearch = true) {
        for ((anchor, cells) in loaded.rows) {
            val target = anchor ?: cells.getOrNull(offsetColumn)?.toLongOrNull()
            PanelTableRow(onClick = { target?.let { selection.selectRange(it.toInt(), 1) } }) {
                for (index in tab.info.columns.indices) {
                    Td({ classes("panel-td") }) { Text(cells.getOrElse(index) { "" }) }
                }
            }
        }
    }
}

private fun readAllRows(bound: BoundScriptTab): ProviderTabRows {
    val rows = mutableListOf<Pair<Long?, List<String>>>()
    var offset: Int? = 0
    try {
        while (offset != null && rows.size < MAX_PROVIDER_TAB_ROWS) {
            val page = fetchBrowserTabPage(bound.tab, bound.binary, offset, PROVIDER_TAB_PAGE)
            rows += page.rows
            // A page that adds nothing cannot be followed: a script answering the same
            // nextOffset forever would otherwise spin here.
            offset = page.nextOffset?.takeIf { it > offset!! && page.rows.isNotEmpty() }
        }
    } catch (e: Throwable) {
        return ProviderTabRows(rows, e.message ?: "script error", truncated = false)
    }
    return ProviderTabRows(rows.take(MAX_PROVIDER_TAB_ROWS), null, truncated = offset != null)
}
