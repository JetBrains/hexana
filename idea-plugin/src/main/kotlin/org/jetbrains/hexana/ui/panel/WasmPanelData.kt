package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.onClick
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.borderBottom
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WasmPanelDataNew(wasmBinaryProvider: WasmBinaryProvider, state: HexViewStateHolder) {
    val dataRecords = remember(wasmBinaryProvider) { wasmBinaryProvider.data }

    if (dataRecords.isEmpty()) return

    Table(listOf(
        TableColumn("ID") { index ->
            index.toString()
        },
        TableColumn("Data") { index ->
            "(data $#data${index})"
        },
        TableColumn("Size") { index ->
            dataRecords[index].recordLength.toString()
        }
    ), dataRecords.size)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WasmPanelData(wasmBinaryProvider: WasmBinaryProvider, state: HexViewStateHolder) {
    val dataRecords = remember(wasmBinaryProvider) { wasmBinaryProvider.data }

    if (dataRecords.isEmpty()) return

    var selectedOriginalIndex: Int? by remember { mutableStateOf(null) }

    var sortBySizeAsc: Boolean? by remember { mutableStateOf(null) }
    val viewRecords = remember(dataRecords, sortBySizeAsc) {
        val base = dataRecords.withIndex().map { it.index to it.value }
        when (sortBySizeAsc) {
            true -> base.sortedBy { (_, rec) -> rec.recordLength }
            false -> base.sortedByDescending { (_, rec) -> rec.recordLength }
            null -> base
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            Box(modifier = Modifier.padding(end = 8.dp).weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Data",
                        style = TextStyle(
                            color = HexanaTheme.textColor,
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier.onClick {
                            sortBySizeAsc = when (sortBySizeAsc) {
                                null -> true
                                true -> false
                                false -> null
                            }
                        }
                    )


                    val sortTitle = when (sortBySizeAsc) {
                        true -> "smallest first"
                        false -> "biggest first"
                        null -> "by order"
                    }

                    Link(
                        sortTitle,
                        onClick = {
                            sortBySizeAsc = when (sortBySizeAsc) {
                                null -> true
                                true -> false
                                false -> null
                            }
                        }
                    )
                }
            }

            Box(modifier = Modifier.padding(end = 8.dp).weight(1f)) {
                Text(
                    "Size",
                    style = TextStyle(
                        color = HexanaTheme.textColor,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(viewRecords.size) { rowIndex ->
                val (originalIndex, dataRecord) = viewRecords[rowIndex]
                val name = "(data $#data${originalIndex})"
                val sizeLabel = "${dataRecord.recordLength}"

                val selectedColor = if (rowIndex % 2 == 0) HexanaTheme.backgroundColor else lerp(
                    HexanaTheme.backgroundColor,
                    Color.White,
                    0.05f
                )
                val isSelected = selectedOriginalIndex == originalIndex
                val rowBg = if (isSelected) lerp(
                    selectedColor,
                    HexanaTheme.hexCellHoverActiveColor,
                    0.6f
                ) else selectedColor

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .onClick {
                            selectedOriginalIndex = originalIndex
                            state.scrollHexViewTo(dataRecord.offset.toLong())

                            val startLine = (dataRecord.offset / state.bytesPerRow.value)
                            val startLineOffset = (dataRecord.offset % state.bytesPerRow.value)

                            val endLine =
                                ((dataRecord.offset + dataRecord.recordLength) / state.bytesPerRow.value)
                            val endLineOffset =
                                ((dataRecord.offset + dataRecord.recordLength) % state.bytesPerRow.value) - 1

                            state.setSelection(
                                startLine, startLineOffset,
                                endLine, endLineOffset
                            )
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        name,
                        style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
                        lineHeight = TextUnit(1f, TextUnitType.Em),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        sizeLabel,
                        style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
                        lineHeight = TextUnit(1f, TextUnitType.Em),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}