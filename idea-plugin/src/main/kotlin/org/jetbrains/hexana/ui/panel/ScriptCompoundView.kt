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
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSession
import org.jetbrains.hexana.mcp.scripting.SlicePageResult
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.ConsoleSeverity
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import org.jetbrains.hexana.shared.scripting.ScriptConsoleEntry
import org.jetbrains.hexana.shared.scripting.SliceRegistrationInfo
import org.jetbrains.hexana.mcp.scripting.ScriptTabBinding
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle

private const val LOG_TAB_NAME = "Log"
private const val CHECKS_TAB_NAME = "Checks"

/**
 * Compound view showing one inner tab per registered slice, an optional Checks
 * tab when checks are registered, and a Log tab.
 *
 * Drives page fetching lazily: the first page loads when a tab is first shown,
 * and further pages load on demand when the user clicks "Load more".
 * Closes [session] in its DisposableEffect onDispose.
 *
 * Staleness is decided by the text hash alone: the version counter triggers a
 * hash recompute on every save, and an undo back to the computed-from text
 * clears the badge even though the version moved on.
 *
 * @param binaryFilePath path to the primary binary (used to push findings to the
 *   ScriptFindingsStore). Null in tests or when no file is known.
 */
@Composable
internal fun ScriptCompoundView(
    session: HexanaScriptSession,
    scriptTextHash: String,
    stampHash: String,
    onRecompute: () -> Unit,
    hexState: HexViewStateHolder?,
    modifier: Modifier = Modifier,
    binaryFilePath: String? = null,
    scriptName: String = "",
) {
    val slices = remember(session) { session.initialResult.slices }
    val checks = remember(session) { session.initialResult.checks }
    val hasChecks = checks.isNotEmpty()

    // Tab names: slices first, then Checks (if any), then Log.
    val tabNames = remember(slices, hasChecks) {
        slices.map { it.displayName() } + (if (hasChecks) listOf(CHECKS_TAB_NAME) else emptyList()) + LOG_TAB_NAME
    }
    val checksTabIndex = if (hasChecks) slices.size else -1
    val logTabIndex = if (hasChecks) slices.size + 1 else slices.size

    var selectedIndex by remember(session) { mutableIntStateOf(0) }

    val sliceModels = remember(session) { slices.map { SliceTabModel(it) } }
    val checksModel = remember(session) { if (hasChecks) CheckTabModel(checks) else null }

    // Compose-observable findings state: null while loading, list when done.
    var checkFindings by remember(session) { mutableStateOf<List<CheckFinding>?>(null) }

    // Seeded with the entries the eval itself produced; page fetches refresh it.
    val consoleEntries = remember(session) {
        mutableStateListOf<ScriptConsoleEntry>().apply { addAll(session.consoleSnapshot()) }
    }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    // Run checks on Dispatchers.IO right after the session opens.
    LaunchedEffect(session) {
        if (!hasChecks) return@LaunchedEffect
        val findings = withContext(Dispatchers.IO) { runInterruptible { session.runChecks() } }
        checkFindings = findings
        // Keep the plain model in sync for test-only use.
        checksModel?.findings = findings
        // Push to the store so the Problems tab can also reflect them.
        val project = hexState?.project
        if (project != null && binaryFilePath != null && binaryFilePath.isNotEmpty()) {
            val vf = withContext(Dispatchers.IO) {
                LocalFileSystem.getInstance().findFileByPath(binaryFilePath)
            }
            if (vf != null) {
                ScriptFindingsStore.getInstance(project)
                    .replaceFindings(vf, scriptName, findings)
            }
        }
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
            when {
                idx < slices.size -> SliceTabContent(
                    session = session,
                    model = sliceModels[idx],
                    sliceInfo = slices[idx],
                    hexState = hexState,
                    onConsoleFetched = { entries ->
                        consoleEntries.clear()
                        consoleEntries.addAll(entries)
                    },
                )
                idx == checksTabIndex && checksModel != null -> ChecksTabContent(
                    findings = checkFindings,
                    hexState = hexState,
                    binaryFilePath = binaryFilePath,
                )
                else -> LogTabContent(entries = consoleEntries)
            }
        }
    }
}

/**
 * A `hexana.defineTab` tab shown as one of the editor's own tabs: the same paged table a
 * script tab renders for a slice, driven by the provider script's session. The session
 * belongs to the editor's script bindings, which close it — not this composable.
 */
@Composable
internal fun ProviderTabContent(binding: ScriptTabBinding, hexState: HexViewStateHolder?) {
    val model = remember(binding) { SliceTabModel(binding.tab) }
    SliceTabContent(
        session = binding.session,
        model = model,
        sliceInfo = binding.tab,
        hexState = hexState,
        // A provider tab has no Log tab beside it; console output is not shown.
        onConsoleFetched = {},
    )
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

/**
 * Renders the Checks inner tab.
 *
 * Columns: Severity, Check (id), Message, Anchor.
 * Row click: Function anchor -> WAT nav; ByteRange anchor -> hex scroll.
 * Empty state: shows a clean-run message with a hint about run configurations.
 *
 * @param findings null while checks are still running, empty list for a clean run,
 *   non-empty when there are findings to display.
 */
@Composable
private fun ChecksTabContent(
    findings: List<CheckFinding>?,
    hexState: HexViewStateHolder?,
    binaryFilePath: String?,
) {
    val rows = remember(findings) { findings?.map { it.toRow() } ?: emptyList() }

    when {
        findings == null -> {
            Text(
                text = "Running checks…",
                modifier = Modifier.padding(8.dp),
                fontSize = 12.sp,
            )
        }
        rows.isEmpty() -> {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "No findings. Checks ran clean.",
                    fontSize = 12.sp,
                )
                Text(
                    text = "Create a run configuration to run this script with inputs and flags.",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                    color = JewelTheme.globalColors.text.normal.copy(alpha = 0.7f),
                )
            }
        }
        else -> {
            val columns: List<TableColumn> = remember(rows) {
                listOf(
                    TableColumn("Severity", weight = 0.5f) { rowIndex ->
                        val row = rows.getOrNull(rowIndex)
                        TableCellValue(row?.severity ?: "")
                    },
                    TableColumn("Check", weight = 1f) { rowIndex ->
                        val row = rows.getOrNull(rowIndex)
                        TableCellValue(row?.checkId ?: "")
                    },
                    TableColumn("Message", weight = 2f) { rowIndex ->
                        val row = rows.getOrNull(rowIndex)
                        TableCellValue(row?.message ?: "")
                    },
                    TableColumn("Anchor", weight = 0.8f) { rowIndex ->
                        val row = rows.getOrNull(rowIndex)
                        TableCellValue(row?.anchorText ?: "")
                    },
                )
            }
            Table(
                columns = columns,
                dataCount = rows.size,
                showIdColumn = false,
                alwaysShowSearch = true,
                onRow = { rowIndex ->
                    val finding = findings.getOrNull(rowIndex) ?: return@Table
                    when (val a = finding.anchor) {
                        is ScriptAnchor.Function -> {
                            val project = hexState?.project ?: return@Table
                            val vf = binaryFilePath?.let {
                                LocalFileSystem.getInstance().findFileByPath(it)
                            } ?: return@Table
                            navigateToFunctionInWat(vf, project, a.funcidx.toInt())
                        }
                        is ScriptAnchor.ByteRange -> {
                            hexState?.scrollHexViewTo(a.offset, setCursor = true)
                        }
                        null -> {}
                    }
                },
            )
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
