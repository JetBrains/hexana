package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A [Panel] subclass representing a user-added script tab.
 * Executes the script via GraalJS, passing the [BinaryProvider] as `binary`
 * and a `print` function that appends lines to the rendered output.
 */
class ScriptPanel(
    val scriptPath: String,
    binaryProvider: BinaryProvider,
) : Panel(
    description = scriptPath.substringAfterLast('/'),
    render = {
        ScriptTabContent(scriptPath, binaryProvider)
    },
)

/**
 * Detects whether the first two lines look like CSV or TSV.
 * Returns the delimiter character if detected, or `null` for plain text.
 */
private fun detectDelimiter(lines: List<String>): Char? {
    if (lines.size < 2) return null
    for (delim in charArrayOf('\t', ',')) {
        val cols0 = lines[0].split(delim)
        val cols1 = lines[1].split(delim)
        if (cols0.size >= 2 && cols0.size == cols1.size) return delim
    }
    return null
}

@Composable
internal fun ScriptTabContent(scriptPath: String, binaryProvider: BinaryProvider) {
    val outputLines = remember(scriptPath) { mutableStateListOf<String>() }
    var isRunning by remember(scriptPath) { mutableStateOf(true) }
    var refreshKey by remember(scriptPath) { mutableIntStateOf(0) }

    LaunchedEffect(scriptPath, refreshKey) {
        outputLines.clear()
        isRunning = true
        withContext(Dispatchers.IO) {
            ScriptExecutor.execute(scriptPath, binaryProvider) { line ->
                outputLines.add(line)
            }
        }
        isRunning = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { refreshKey++ },
                modifier = Modifier.size(24.dp),
                enabled = !isRunning,
            ) {
                Icon(
                    AllIconsKeys.Actions.Refresh,
                    contentDescription = "Refresh",
                    iconClass = AllIcons::class.java,
                )
            }
            Text(
                text = scriptPath,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
                fontSize = 13.sp,
            )
        }
        if (isRunning && outputLines.isEmpty()) {
            Text(
                text = "Running script…",
                modifier = Modifier.padding(4.dp),
                fontSize = 12.sp,
            )
        }

        val delimiter = remember(outputLines.size) {
            if (outputLines.size >= 2) detectDelimiter(outputLines) else null
        }

        if (delimiter != null) {
            ScriptTableView(outputLines, delimiter, isRunning)
        } else if (outputLines.isNotEmpty()) {
            ScriptTextView(outputLines, isRunning)
        }
    }
}

@Composable
private fun ScriptTableView(lines: List<String>, delimiter: Char, isRunning: Boolean) {
    val headers = remember(lines.firstOrNull()) {
        lines.firstOrNull()?.split(delimiter) ?: emptyList()
    }
    // Data rows (skip header); snapshot size so recomposition picks up new lines.
    val dataSize = lines.size - 1
    val dataRows = lines

    val columns = remember(headers, dataSize) {
        headers.mapIndexed { colIndex, header ->
            TableColumn(
                title = header.trim(),
                weight = 1f,
            ) { rowIndex ->
                val actualRow = rowIndex + 1 // skip header
                val cells = dataRows.getOrNull(actualRow)?.split(delimiter) ?: emptyList()
                val cell = cells.getOrElse(colIndex) { "" }.trim()
                TableCellValue(cell)
            }
        }
    }

    Table(
        columns = columns,
        dataCount = maxOf(dataSize, 0),
        alwaysShowSearch = true,
        showIdColumn = true,
    )
    if (isRunning) {
        Text(
            text = "Running…",
            modifier = Modifier.padding(4.dp),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ScriptTextView(lines: List<String>, isRunning: Boolean) {
    val columns = remember(lines.size) {
        listOf(
            TableColumn(
                title = "Output",
                weight = 1f,
            ) { index ->
                TableCellValue(lines.getOrElse(index) { "" })
            }
        )
    }

    Table(
        columns = columns,
        dataCount = lines.size,
        alwaysShowSearch = true,
        showIdColumn = true,
    )
    if (isRunning) {
        Text(
            text = "Running…",
            modifier = Modifier.padding(4.dp),
            fontSize = 12.sp,
        )
    }
}

internal fun createScriptPanel(scriptPath: String, binaryProvider: BinaryProvider): ScriptPanel {
    return ScriptPanel(scriptPath, binaryProvider)
}
