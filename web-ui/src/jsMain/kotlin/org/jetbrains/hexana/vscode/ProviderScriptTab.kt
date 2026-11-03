package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.RowSelectionData
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.scripting.SliceColumnType

/** Rows a provider tab reads before stopping; the same order of magnitude as a script table. */
private const val MAX_PROVIDER_TAB_ROWS = 10_000

/** Rows asked for per `rows()` call. */
private const val PROVIDER_TAB_PAGE = 500

/** The rows a tab produced so far, and whether reading them is over. */
private class ProviderTabRows(
    val rows: List<Pair<Long?, List<String>>> = emptyList(),
    val done: Boolean = false,
    val error: String? = null,
    val truncated: Boolean = false,
)

/**
 * The editor tab for a `hexana.defineTab` tab: its rows, read page by page from the script,
 * in the product's virtualized table. Clicking a row selects its anchor — or the value of its
 * first `offset` column — in the hex view, as a row of a built-in table does.
 */
fun providerScriptPanelTab(bound: BoundScriptTab): PanelTab =
    // Identified by the full `user:` name a region's `tab` links to; shown without the prefix.
    PanelTab(bound.tab.info.name.removePrefix("user:"), padding = false, id = bound.tab.info.name) { selection ->
        ProviderScriptTabContent(bound, selection)
    }

@Composable
private fun ProviderScriptTabContent(bound: BoundScriptTab, selection: HexSelectionState) {
    val tab = bound.tab
    // Read in an effect rather than during composition, a page at a time with a frame
    // in between, so the tab opens at once and fills in as the script answers. Reset
    // whenever the tab is rebuilt (the scripts or the binary changed).
    var loaded by remember(bound) { mutableStateOf(ProviderTabRows()) }
    LaunchedEffect(bound) {
        val rows = mutableListOf<Pair<Long?, List<String>>>()
        var offset: Int? = 0
        while (offset != null && rows.size < MAX_PROVIDER_TAB_ROWS) {
            val page = try {
                fetchBrowserTabPage(bound.tab, bound.binary, offset, PROVIDER_TAB_PAGE)
            } catch (e: Throwable) {
                loaded = ProviderTabRows(rows.toList(), done = true, error = e.message ?: "script error")
                return@LaunchedEffect
            }
            rows += page.rows
            // A page that adds nothing cannot be followed: a script answering the same
            // nextOffset forever would otherwise spin here.
            val current = offset
            offset = page.nextOffset?.takeIf { it > current && page.rows.isNotEmpty() }
            loaded = ProviderTabRows(rows.take(MAX_PROVIDER_TAB_ROWS), done = offset == null)
            if (offset != null) delay(0)
        }
        loaded = ProviderTabRows(rows.take(MAX_PROVIDER_TAB_ROWS), done = true, truncated = offset != null)
    }

    val offsetColumn = remember(tab) { tab.info.columns.indexOfFirst { it.type == SliceColumnType.OFFSET } }
    val rows = loaded.rows
    val descriptor = remember(tab, rows) {
        TableTabDescriptor(
            name = tab.info.name,
            columns = tab.info.columns.mapIndexed { column, info ->
                ColumnDescriptor(info.name) { row ->
                    val text = rows[row].second.getOrElse(column) { "" }
                    // Offsets sort as numbers, not as text.
                    val number = if (info.type == SliceColumnType.OFFSET) text.toLongOrNull() else null
                    if (number != null) CellValue(number, text) else CellValue(text)
                }
            },
            rowCount = rows.size,
            rowSelectionProvider = { row ->
                val (anchor, cells) = rows[row]
                (anchor ?: cells.getOrNull(offsetColumn)?.toLongOrNull())?.let { RowSelectionData(it.toInt(), 1) }
            },
            alwaysShowSearch = true,
            showIdColumn = false,
        )
    }

    if (loaded.error != null) {
        Div({ classes("hexana-provider-tab-error") }) { Text("Script error: ${loaded.error}") }
    }
    if (loaded.truncated) {
        Div({ classes("hexana-dialog-hint") }) { Text("Showing the first $MAX_PROVIDER_TAB_ROWS rows.") }
    }
    if (!loaded.done && rows.isEmpty()) {
        LoadingIndicator("Running the script…")
    } else {
        TableTabContent(descriptor, selection)
    }
}
