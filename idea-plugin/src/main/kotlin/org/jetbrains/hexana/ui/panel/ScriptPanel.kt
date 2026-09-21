package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.scripting.ScriptOutputBlock
import org.jetbrains.hexana.shared.scripting.filterScriptOutputBlocks
import org.jetbrains.hexana.shared.scripting.parseScriptOutputBlocks
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.TABLE_BODY_CHROME_HEIGHT
import org.jetbrains.hexana.ui.components.rememberTableRowHeight
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A [Panel] subclass representing a user-added script tab.
 * Executes the script via the shared sandboxed scripting runtime and renders the
 * output as interleaved text and tables.
 */
class ScriptPanel(
    val scriptPath: String,
    binaryProvider: BinaryProvider,
    binaryFileName: String? = null,
    binaryFilePath: String? = null,
    project: Project? = null,
    hexViewState: HexViewStateHolder? = null,
) : Panel(
    description = scriptPath.substringAfterLast('/'),
    render = {
        ScriptTabContent(scriptPath, binaryProvider, binaryFileName, binaryFilePath, project, hexViewState)
    },
)

@Composable
internal fun ScriptTabContent(
    scriptPath: String,
    binaryProvider: BinaryProvider,
    binaryFileName: String? = null,
    binaryFilePath: String? = null,
    project: Project? = null,
    hexViewState: HexViewStateHolder? = null,
) {
    val outputLines = remember(scriptPath) { mutableStateListOf<String>() }
    var isRunning by remember(scriptPath) { mutableStateOf(true) }
    var refreshKey by remember(scriptPath) { mutableIntStateOf(0) }
    var filter by remember(scriptPath) { mutableStateOf(TextFieldValue("")) }
    // Segmentation is O(output), so it is re-run on its own slower cadence than
    // the 50ms line drain: a script streaming 200k lines would otherwise re-parse
    // and re-flatten everything twenty times a second.
    var outputRevision by remember(scriptPath) { mutableIntStateOf(0) }

    // Script-file version counter: incremented on every VFS change to the script file.
    var scriptVersion by remember(scriptPath) { mutableIntStateOf(0) }

    // Register a VFS listener on the script file for staleness tracking.
    // Removed in DisposableEffect onDispose.
    DisposableEffect(scriptPath) {
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val scriptUrl = LocalFileSystem.getInstance()
                        .findFileByPath(scriptPath)?.url
                    if (events.any { it.file?.url == scriptUrl }) {
                        scriptVersion++
                    }
                }
            },
        )
        onDispose { connection.disconnect() }
    }

    // Current script text hash, used for staleness detection in the compound view.
    var scriptTextHash by remember(scriptPath) { mutableStateOf("") }

    // Result from the session-based executor: switches between legacy and compound mode.
    var sessionResult by remember(scriptPath, refreshKey) {
        mutableStateOf<ScriptSessionRunResult?>(null)
    }
    // Stamp: version+hash at the time of the last successful run.
    var stamp by remember(scriptPath) { mutableStateOf<ScriptRunStamp?>(null) }

    LaunchedEffect(scriptPath, refreshKey) {
        outputLines.clear()
        // Re-segment immediately: the parsed blocks are remembered against this
        // revision, so without it a rerun keeps showing the previous run's output
        // until the first drain bumps it.
        outputRevision++
        isRunning = true
        sessionResult = null

        // Compute the current script text hash before running.
        val currentHash = kotlinx.coroutines.withContext(Dispatchers.IO) { hashScriptFile(scriptPath) }
        scriptTextHash = currentHash
        // Stamp the run with the current version and hash.
        stamp = ScriptRunStamp(version = scriptVersion, textHash = currentHash)

        // The runner streams from its own worker thread. Lines are buffered and
        // drained in batches rather than written to the snapshot list one at a
        // time: a script is allowed up to 200k lines, and per-line snapshot
        // writes from a background thread would dominate the run.
        val pending = mutableListOf<String>()
        fun drain() {
            val batch = synchronized(pending) {
                if (pending.isEmpty()) emptyList() else pending.toList().also { pending.clear() }
            }
            if (batch.isNotEmpty()) outputLines.addAll(batch)
        }

        val execution = launch(Dispatchers.IO) {
            val result = openScriptSession(
                scriptPath = scriptPath,
                binaryProvider = binaryProvider,
                binaryFileName = binaryFileName,
                binaryFilePath = binaryFilePath,
                onLine = { line -> synchronized(pending) { pending.add(line) } },
            )
            // If the panel was disposed while eval ran, nothing will ever
            // compose the session, so close it here instead of leaking it.
            if (!isActive) {
                result.session?.close()
            } else {
                sessionResult = result
            }
        }
        var drains = 0
        while (execution.isActive) {
            delay(OUTPUT_POLL_MILLIS)
            drain()
            if (++drains % DRAINS_PER_RESEGMENT == 0) outputRevision++
        }
        execution.join()
        drain()
        outputRevision++
        isRunning = false
    }

    // Current script text hash for staleness comparison (updated while running and on version bump).
    LaunchedEffect(scriptVersion) {
        scriptTextHash = kotlinx.coroutines.withContext(Dispatchers.IO) { hashScriptFile(scriptPath) }
    }

    val currentResult = sessionResult

    // Compound path: script registered at least one slice.
    if (currentResult != null && currentResult.mode == ScriptRenderMode.Compound) {
        val session = currentResult.session
        if (session != null) {
            val stampSnapshot = stamp
            Column(modifier = Modifier.fillMaxSize()) {
                ScriptHeader(scriptPath, project, isRunning, onRefresh = { refreshKey++ })
                ScriptCompoundView(
                    session = session,
                    scriptTextHash = scriptTextHash,
                    stampHash = stampSnapshot?.textHash ?: "",
                    onRecompute = { refreshKey++ },
                    hexState = hexViewState,
                    modifier = Modifier.weight(1f),
                    binaryFilePath = binaryFilePath,
                    scriptName = scriptPath.substringAfterLast('/'),
                )
            }
            return
        }
    }

    // Legacy path: no slices, or still running.
    val blocks = remember(outputRevision) { parseScriptOutputBlocks(outputLines.toList()) }
    val visibleBlocks = remember(blocks, filter.text) { filterScriptOutputBlocks(blocks, filter.text) }
    val singleTable = blocks.singleOrNull() as? ScriptOutputBlock.Table

    Column(modifier = Modifier.fillMaxSize()) {
        ScriptHeader(scriptPath, project, isRunning, onRefresh = { refreshKey++ })

        if (isRunning && outputLines.isEmpty()) {
            Text(
                text = "Running script…",
                modifier = Modifier.padding(4.dp),
                fontSize = 12.sp,
            )
        }

        if (singleTable != null) {
            ScriptSingleTableView(singleTable, isRunning, modifier = Modifier.weight(1f))
        } else if (blocks.isNotEmpty()) {
            // One filter for the whole pane: the output is a document of several
            // blocks, so a search box per table would be both noisy and unable to
            // reach the text between them.
            TextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                placeholder = { Text("Filter output...") },
                leadingIcon = {
                    Icon(
                        AllIconsKeys.Actions.Search,
                        contentDescription = null,
                        iconClass = AllIcons::class.java,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            ScriptBlocksView(
                blocks = visibleBlocks,
                filtered = filter.text.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
            // Outside the scrolling document, so it stays visible while output is
            // still arriving.
            RunningNote(isRunning)
        }
    }
}

/** Common header row: refresh button + script path link. */
@Composable
private fun ScriptHeader(
    scriptPath: String,
    project: Project?,
    isRunning: Boolean,
    onRefresh: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.size(24.dp),
            enabled = !isRunning,
        ) {
            Icon(
                AllIconsKeys.Actions.Refresh,
                contentDescription = "Refresh",
                iconClass = AllIcons::class.java,
            )
        }
        if (project != null) {
            // Opening the script from here attaches the type definitions
            // first, so the editor resolves the scripting globals.
            Link(
                text = scriptPath,
                onClick = { ScriptTemplateWriter.openForEditing(project, scriptPath) },
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
        } else {
            Text(
                text = scriptPath,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
                fontSize = 13.sp,
            )
        }
    }
}

/** Renders output that is a single table, filling the pane. */
@Composable
private fun ScriptSingleTableView(
    block: ScriptOutputBlock.Table,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // The table brings its own scrollbar, but only once something bounds its
        // height — otherwise it sizes to the whole tab and its last rows fall
        // below the visible area.
        Box(modifier = Modifier.weight(1f)) {
            Table(
                columns = scriptTableColumns(block),
                dataCount = block.rows.size,
                alwaysShowSearch = true,
                showIdColumn = true,
            )
        }
        RunningNote(isRunning)
    }
}

/**
 * Renders text and tables in the order the script produced them.
 *
 * The whole document scrolls as one, with its own scrollbar: text and tables
 * together can be far taller than the tab, and each table only scrolls within its
 * own bounded block. The outer [LazyColumn] virtualizes the document — a script
 * may emit 200k lines — and each table block is one item holding a real [Table],
 * so tables keep the sorting, per-row copy, and context menus every other tab
 * has. A table needs a bounded height to size its own virtualized body; the bound
 * is a share of the visible pane, so no single table can push the text around it
 * off screen. Taller tables scroll within their own block.
 */
@Composable
private fun ScriptBlocksView(
    blocks: List<ScriptOutputBlock>,
    filtered: Boolean,
    modifier: Modifier = Modifier,
) {
    if (blocks.isEmpty()) {
        Text(
            text = if (filtered) "No output matches the filter." else "The script produced no output.",
            modifier = modifier.padding(8.dp),
            fontSize = 12.sp,
        )
        return
    }

    val items = remember(blocks) { flattenScriptBlocks(blocks) }
    val listState = rememberLazyListState()
    val rowHeight = rememberTableRowHeight()

    BoxWithConstraints(modifier = modifier) {
        // Cap each table against the visible pane rather than a fixed row count:
        // one table must not fill the viewport and hide the text around it, and a
        // fixed count is too tall on a short pane and needlessly short on a big
        // display. Tables shorter than the cap still shrink to their content.
        val maxTableRows = remember(maxHeight, rowHeight) {
            // The cap is on rows, but a block also spends height on the column
            // header and the body's padding; both come off the budget so the
            // finished block really does land near the intended share.
            val available = maxHeight * TABLE_BLOCK_PANE_FRACTION - TABLE_BODY_CHROME_HEIGHT - rowHeight
            (available / rowHeight).toInt().coerceIn(MIN_TABLE_BLOCK_ROWS, MAX_TABLE_BLOCK_ROWS)
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            // Keyed on the block's position in the unfiltered output: a table
            // hosts a stateful component, and with positional identity its sort
            // and selection would jump to a different table as filtering changes
            // the list around it.
            items(items, key = { it.key }) { item ->
                when (item) {
                    is ScriptOutputItem.Blank -> Spacer(modifier = Modifier.height(6.dp))
                    is ScriptOutputItem.TextLine -> ScriptTextLine(item.text)
                    is ScriptOutputItem.TableBlock -> ScriptTableBlockView(item.block, maxTableRows)
                }
            }
        }
        VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun ScriptTableBlockView(block: ScriptOutputBlock.Table, maxVisibleRows: Int) {
    Box(modifier = Modifier.padding(bottom = 8.dp, end = DOCUMENT_SCROLLBAR_GUTTER)) {
        Table(
            columns = scriptTableColumns(block),
            dataCount = block.rows.size,
            showIdColumn = true,
            maxVisibleRows = maxVisibleRows,
            // The pane has one filter for the whole document; a search bar per
            // table would be noisy and could not reach the text between them.
            // Ctrl+F still opens this table's own search.
            alwaysShowSearch = false,
            // Several tables share this pane, so none of them may steal focus.
            requestInitialFocus = false,
        )
    }
}

/**
 * Table columns for a script-produced table.
 *
 * Cell values are handed over as numbers when they parse as one, so the column
 * sorts numerically rather than lexicographically — a `Size` column of script
 * output is otherwise ordered 1024 before 64.
 */
@Composable
private fun scriptTableColumns(block: ScriptOutputBlock.Table): List<TableColumn> {
    val rows = block.rows
    return remember(block) {
        block.columns.mapIndexed { columnIndex, header ->
            TableColumn(title = header, weight = 1f) { rowIndex ->
                val text = rows.getOrNull(rowIndex)?.getOrNull(columnIndex)?.trim() ?: ""
                val number = text.toLongOrNull()
                if (number != null) TableCellValue(number, text) else TableCellValue(text)
            }
        }
    }
}

@Composable
private fun ScriptTextLine(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun RunningNote(isRunning: Boolean) {
    if (!isRunning) return
    Text(
        text = "Running\u2026",
        modifier = Modifier.padding(4.dp),
        fontSize = 12.sp,
    )
}

/** One item of the flattened output document. */
private sealed interface ScriptOutputItem {
    /** Identity that survives filtering, for the virtualized list. */
    val key: String

    class Blank(override val key: String) : ScriptOutputItem
    class TextLine(val text: String, override val key: String) : ScriptOutputItem
    class TableBlock(val block: ScriptOutputBlock.Table) : ScriptOutputItem {
        override val key: String get() = "table:${block.ordinal}"
    }
}

/**
 * Flattens blocks into list items: text one line at a time so long output stays
 * virtualized, and each table as a single item that the table component then
 * virtualizes internally.
 */
private fun flattenScriptBlocks(blocks: List<ScriptOutputBlock>): List<ScriptOutputItem> {
    val items = mutableListOf<ScriptOutputItem>()
    for (block in blocks) {
        when (block) {
            is ScriptOutputBlock.Text ->
                block.lines.forEachIndexed { lineIndex, line ->
                    val key = "text:${block.ordinal}:$lineIndex"
                    items += if (line.isBlank()) {
                        ScriptOutputItem.Blank(key)
                    } else {
                        ScriptOutputItem.TextLine(line, key)
                    }
                }
            is ScriptOutputBlock.Table -> items += ScriptOutputItem.TableBlock(block)
        }
    }
    return items
}

/**
 * Share of the visible pane one table block may occupy before it scrolls within
 * itself. Half leaves room for the text around it to stay on screen.
 */
private const val TABLE_BLOCK_PANE_FRACTION = 0.5f

/** Floor for the per-table cap, so a table stays usable on a very short pane. */
private const val MIN_TABLE_BLOCK_ROWS = 5

/** Ceiling for the per-table cap, so one table does not run away on a tall display. */
private const val MAX_TABLE_BLOCK_ROWS = 40

/**
 * Space left at the right of a table block so its own scrollbar does not sit
 * underneath the document scrollbar.
 */
private val DOCUMENT_SCROLLBAR_GUTTER = 12.dp

/** Interval at which streamed script output is published to the view. */
private const val OUTPUT_POLL_MILLIS = 50L

/** Drains between re-segmentations while a script is still running. */
private const val DRAINS_PER_RESEGMENT = 5

internal fun createScriptPanel(
    scriptPath: String,
    binaryProvider: BinaryProvider,
    binaryFileName: String? = null,
    binaryFilePath: String? = null,
    project: Project? = null,
    hexViewState: HexViewStateHolder? = null,
): ScriptPanel {
    return ScriptPanel(scriptPath, binaryProvider, binaryFileName, binaryFilePath, project, hexViewState)
}
