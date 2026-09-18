package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSession
import org.jetbrains.hexana.mcp.scripting.SlicePageResult
import org.jetbrains.hexana.shared.scripting.ConsoleSeverity
import org.jetbrains.hexana.shared.scripting.ScriptConsoleEntry
import org.jetbrains.hexana.shared.scripting.SliceRegistrationInfo
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle

private const val LOG_TAB_NAME = "Log"

/**
 * Compound view showing one inner tab per registered slice plus a Log tab.
 *
 * Drives page fetching lazily: the first page loads when a tab is first shown,
 * and further pages load on demand when the user clicks "Load more".
 * Closes [session] in its DisposableEffect onDispose.
 *
 * Staleness is decided by the text hash alone: the version counter triggers a
 * hash recompute on every save, and an undo back to the computed-from text
 * clears the badge even though the version moved on.
 */
@Composable
internal fun ScriptCompoundView(
    session: HexanaScriptSession,
    scriptTextHash: String,
    stampHash: String,
    onRecompute: () -> Unit,
    hexState: HexViewStateHolder?,
    modifier: Modifier = Modifier,
) {
    val slices = remember(session) { session.initialResult.slices }
    val tabNames = remember(slices) { slices.map { it.displayName() } + LOG_TAB_NAME }
    var selectedIndex by remember(session) { mutableIntStateOf(0) }

    val sliceModels = remember(session) { slices.map { SliceTabModel(it) } }
    // Seeded with the entries the eval itself produced; page fetches refresh it.
    val consoleEntries = remember(session) {
        mutableStateListOf<ScriptConsoleEntry>().apply { addAll(session.consoleSnapshot()) }
    }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    val isStale = isScriptStale(currentHash = scriptTextHash, stampHash = stampHash)

    Column(modifier = modifier.fillMaxSize()) {
        if (isStale) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Script changed",
                    fontSize = 11.sp,
                    color = JewelTheme.globalColors.outlines.warning,
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onRecompute, modifier = Modifier.size(20.dp)) {
                    Icon(
                        AllIconsKeys.Actions.Refresh,
                        contentDescription = "Recompute",
                        iconClass = AllIcons::class.java,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Recompute", fontSize = 11.sp)
            }
        }

        TabStrip(
            tabs = tabNames.mapIndexed { index, name ->
                TabData.Default(
                    selected = index == selectedIndex,
                    content = { Text(name) },
                    onClick = { selectedIndex = index },
                    closable = false,
                )
            },
            style = JewelTheme.defaultTabStyle,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val idx = selectedIndex
            if (idx < slices.size) {
                SliceTabContent(
                    session = session,
                    model = sliceModels[idx],
                    sliceInfo = slices[idx],
                    hexState = hexState,
                    onConsoleFetched = { entries ->
                        consoleEntries.clear()
                        consoleEntries.addAll(entries)
                    },
                )
            } else {
                LogTabContent(entries = consoleEntries)
            }
        }
    }
}

@Composable
private fun SliceTabContent(
    session: HexanaScriptSession,
    model: SliceTabModel,
    sliceInfo: SliceRegistrationInfo,
    hexState: HexViewStateHolder?,
    onConsoleFetched: (List<ScriptConsoleEntry>) -> Unit,
) {
    var rows by remember(model) { mutableStateOf(model.rows.toList()) }
    var sortCapped by remember(model) { mutableStateOf(model.sortCapped) }
    var isLoading by remember(model) { mutableStateOf(!model.fullyLoaded && !model.hasError) }
    var hasError by remember(model) { mutableStateOf(model.hasError) }

    // Load the first page on first composition for this tab (if not yet fetched).
    LaunchedEffect(model) {
        if (model.nextOffset == null || model.hasError) {
            isLoading = false
            return@LaunchedEffect
        }
        fetchAndApply(session, model, sliceInfo) { entries ->
            rows = model.rows.toList()
            sortCapped = model.sortCapped
            hasError = model.hasError
            onConsoleFetched(entries)
        }
        isLoading = false
    }

    val offsetColIndex = remember(sliceInfo) { sliceInfo.firstOffsetColumnIndex() }
    val columns = remember(sliceInfo, rows) { sliceColumnsForInfo(sliceInfo, rows) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (sortCapped) {
            Text(
                text = "Sort disabled (more than $SORT_CAP_ROW_COUNT rows)",
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                color = JewelTheme.globalColors.outlines.warning,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (columns.isEmpty() && !isLoading) {
                Text(
                    text = "No rows.",
                    modifier = Modifier.padding(8.dp),
                    fontSize = 12.sp,
                )
            } else {
                Table(
                    columns = columns,
                    dataCount = rows.size,
                    showIdColumn = false,
                    alwaysShowSearch = true,
                    onSortChanged = if (sortCapped) { _, _ -> } else null,
                    onRow = { rowIndex ->
                        if (hexState != null && offsetColIndex != null) {
                            val cell = rows.getOrNull(rowIndex)?.cells?.getOrNull(offsetColIndex)
                            val offset = cell?.numeric
                            if (offset != null) hexState.scrollHexViewTo(offset, setCursor = true)
                        }
                    },
                    rowColor = { rowIndex ->
                        val firstCell = rows.getOrNull(rowIndex)?.cells?.firstOrNull()
                        if (rows.getOrNull(rowIndex)?.anchor == null &&
                            firstCell?.text?.startsWith("script error:") == true
                        ) {
                            Color(255, 60, 60, 40)
                        } else null
                    },
                )
            }
        }

        if (!model.fullyLoaded && !model.hasError && !isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { isLoading = true },
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        AllIconsKeys.Actions.More,
                        contentDescription = "Load more",
                        iconClass = AllIcons::class.java,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Load more", fontSize = 12.sp)
            }
        }

        if (isLoading) {
            Text(
                text = "Loading…",
                modifier = Modifier.padding(4.dp),
                fontSize = 12.sp,
            )
        }

        // Triggered when the user clicks "Load more" and isLoading flips to true.
        LaunchedEffect(isLoading) {
            if (!isLoading) return@LaunchedEffect
            if (model.fullyLoaded || model.hasError) {
                isLoading = false
                return@LaunchedEffect
            }
            fetchAndApply(session, model, sliceInfo) { entries ->
                rows = model.rows.toList()
                sortCapped = model.sortCapped
                hasError = model.hasError
                onConsoleFetched(entries)
            }
            isLoading = false
        }
    }
}

/** Fetches one page on Dispatchers.IO and delivers results on the main thread. */
private suspend fun fetchAndApply(
    session: HexanaScriptSession,
    model: SliceTabModel,
    sliceInfo: SliceRegistrationInfo,
    onDone: (List<ScriptConsoleEntry>) -> Unit,
) {
    val offset = model.nextOffset ?: return
    withContext(Dispatchers.IO) {
        val result = session.fetchPage(sliceInfo.name, offset, SLICE_FETCH_LIMIT)
        val entries = session.consoleSnapshot()
        withContext(Dispatchers.Main) {
            when (result) {
                is SlicePageResult.Ok -> model.applyPage(result.page)
                is SlicePageResult.Err -> model.applyError(result.error.diagnostic)
            }
            onDone(entries)
        }
    }
}

@Composable
private fun LogTabContent(entries: List<ScriptConsoleEntry>) {
    if (entries.isEmpty()) {
        Text(
            text = "No console output.",
            modifier = Modifier.padding(8.dp),
            fontSize = 12.sp,
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries) { entry ->
            val color = when (entry.severity) {
                ConsoleSeverity.ERROR -> Color(200, 60, 60)
                ConsoleSeverity.DEBUG -> JewelTheme.globalColors.text.normal.copy(alpha = 0.5f)
                ConsoleSeverity.LOG -> Color.Unspecified
            }
            Text(
                text = entry.text,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                color = color,
            )
        }
    }
}
