package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.nativebin.NativeDebugCompanionScanner
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun NativeDebugContextPanel(provider: NativeBinaryProvider, state: HexViewStateHolder) {
    val context = remember(provider) { NativeDebugCompanionScanner.scan(provider) }
    val rows = context.companions

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            buildString {
                append("Debug context: ")
                append(if (context.embeddedDebugInfoPresent) "embedded debug info present" else "embedded debug info absent")
                context.elfBuildId?.let { append(", build-id $it") }
                context.gnuDebugLink?.let { append(", debuglink $it") }
                if (context.binaryPath == null) append(". Nearby companion scan unavailable for in-memory artifacts.")
            },
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (rows.isEmpty()) {
            Text("No debug companion information is available.")
            return@Column
        }

        Table(
            columns = listOf(
                TableColumn("Kind", modifier = Modifier.fillMaxWidth(0.22f)) { index ->
                    TableCellValue(rows[index].kind.label)
                },
                TableColumn("Status", modifier = Modifier.fillMaxWidth(0.16f)) { index ->
                    TableCellValue(rows[index].status.label)
                },
                TableColumn("Name", modifier = Modifier.fillMaxWidth(0.30f)) { index ->
                    TableCellValue(rows[index].name)
                },
                TableColumn("Location", modifier = Modifier.fillMaxWidth(0.45f)) { index ->
                    TableCellValue(rows[index].location ?: "")
                },
                TableColumn("Notes", modifier = Modifier.fillMaxWidth()) { index ->
                    TableCellValue(rows[index].description)
                },
            ),
            dataCount = rows.size,
            onRow = { rowIndex ->
                rows[rowIndex].range?.let { range ->
                    state.scrollHexViewTo(range.offset, range.size)
                }
            },
        )
    }
}

@Composable
internal fun NativeDebugInfoPanel(provider: NativeBinaryProvider, state: HexViewStateHolder) {
    val sourceLocationProvider = remember(provider) { provider.nativeSourceLocationProvider }
    if (sourceLocationProvider == null) {
        Text("Debug info: unavailable. No supported native line-table data was found.")
        return
    }
    val summary = remember(sourceLocationProvider) { sourceLocationProvider.summary }
    val rows = remember(summary) {
        buildList {
            add("Line rows" to summary.lineRowCount.toString())
            add("Source files" to summary.fileCount.toString())
            add("First VA" to (summary.firstAddress?.let { hex(it) } ?: ""))
            add("Last VA" to (summary.lastAddress?.let { hex(it) } ?: ""))
            summary.sampleFiles.forEachIndexed { index, file ->
                val resolved = resolveNativeSourcePath(file, state, provider.sourcePath)
                add("File ${index + 1}" to if (resolved != null && resolved.toFile().exists()) {
                    "found: $resolved"
                } else {
                    "missing: $file"
                })
            }
        }
    }
    if (summary.lineRowCount == 0) {
        Text("Debug info: present, but Hexana did not resolve any source line rows.")
        return
    }

    Table(
        columns = listOf(
            TableColumn("Field", modifier = Modifier.fillMaxWidth(0.24f)) { index ->
                TableCellValue(rows[index].first)
            },
            TableColumn("Value", modifier = Modifier.fillMaxWidth()) { index ->
                TableCellValue(rows[index].second)
            },
        ),
        dataCount = rows.size,
    )
}

private fun hex(value: ULong): String = "0x${value.toString(16).uppercase()}"
