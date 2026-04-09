package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.wasm.analysis.SizeAnalysisBuilder
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
import org.jetbrains.hexana.wasm.sections.WasmDataRecord
import org.jetbrains.jewel.ui.component.Text

@Composable
fun WasmPanelSize(provider: WasmBinaryProvider, state: HexViewStateHolder) {
    val module = remember(provider) { provider.sizeAnalysis } ?: return
    val builder = remember(provider) { provider.sizeAnalysisBuilder }
    val codeRecords = remember(provider) { provider.codeRecords }
    val dataRecords = remember(provider) { provider.data }
    SizePanelContent(module, builder, codeRecords, dataRecords, state)
}

@Composable
fun WasmPanelSize(provider: WasmComponentBinaryProvider, state: HexViewStateHolder) {
    val module = remember(provider) { provider.sizeAnalysis } ?: return
    SizePanelContent(module, builder = null, codeRecords = emptyList(), dataRecords = emptyList(), state)
}

@Composable
private fun SizePanelContent(
    module: SizeNode.Module,
    builder: SizeAnalysisBuilder?,
    codeRecords: List<WasmCodeRecord>,
    dataRecords: List<WasmDataRecord>,
    state: HexViewStateHolder,
) {
    var selectedNode by remember { mutableStateOf<SizeNode>(module) }
    val detailCache = remember { mutableMapOf<Any, List<SizeNode>>() }

    val children = remember(selectedNode, detailCache.size) {
        resolveChildren(selectedNode, builder, codeRecords, dataRecords, detailCache)
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left pane: tree (~35%)
        Box(modifier = Modifier.weight(0.35f).fillMaxHeight()) {
            SizeTree(module, selectedNode, onSelect = { selectedNode = it }, depth = 0)
        }

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.Gray.copy(alpha = 0.4f))
        )

        // Right pane: table (~65%)
        Box(modifier = Modifier.weight(0.65f).fillMaxHeight()) {
            if (children.isEmpty()) {
                Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                    Text(
                        "No children",
                        style = TextStyle(
                            color = HexanaTheme.textColor.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            } else {
                val parentSize = selectedNode.size
                val columns = listOf(
                    TableColumn("Name", modifier = Modifier.widthIn(min = 120.dp)) { index ->
                        TableCellValue(children[index].name, tooltip = children[index].name)
                    },
                    TableColumn("Size", modifier = Modifier.widthIn(min = 80.dp)) { index ->
                        val size = children[index].size
                        TableCellValue(size, formatSize(size))
                    },
                    TableColumn("%", modifier = Modifier.widthIn(min = 60.dp)) { index ->
                        val pct = children[index].percentage(parentSize)
                        val pctStr = "%.1f%%".format(pct)
                        TableCellValue(pctStr)
                    },
                    TableColumn("Offset", modifier = Modifier.fillMaxWidth()) { index ->
                        val offset = children[index].offset
                        val hex = "0x${offset.toString(16).uppercase()}"
                        TableCellValue(offset, hex)
                    },
                )
                Table(columns, children.size) { rowIndex ->
                    val node = children[rowIndex]
                    if (node.size > 0) {
                        state.scrollHexViewTo(node.offset, node.size)
                    }
                }
            }
        }
    }
}

private fun resolveChildren(
    node: SizeNode,
    builder: SizeAnalysisBuilder?,
    codeRecords: List<WasmCodeRecord>,
    dataRecords: List<WasmDataRecord>,
    cache: MutableMap<Any, List<SizeNode>>,
): List<SizeNode> {
    if (node.children.isNotEmpty()) return node.children

    if (builder == null) return emptyList()

    when (node) {
        is SizeNode.Function -> {
            val key = "func:${node.funcIndex}"
            return cache.getOrPut(key) {
                builder.buildFunctionDetail(codeRecords, node.funcIndex)
            }
        }
        is SizeNode.DataSegment -> {
            val key = "data:${node.segmentIndex}"
            return cache.getOrPut(key) {
                builder.buildDataSegmentDetail(dataRecords, node.segmentIndex)
            }
        }
        else -> return emptyList()
    }
}

@Composable
private fun SizeTree(
    node: SizeNode,
    selectedNode: SizeNode,
    onSelect: (SizeNode) -> Unit,
    depth: Int,
) {
    val lazyListState = rememberLazyListState()
    val flatNodes = remember(node) { flattenTree(node) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)
        ) {
            items(flatNodes.size) { index ->
                val (treeNode, treeDepth) = flatNodes[index]
                val isSelected = treeNode === selectedNode
                val rowBg = if (isSelected) {
                    HexanaTheme.hexCellHoverActiveColor
                } else {
                    Color.Unspecified
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .clickable { onSelect(treeNode) }
                        .padding(start = (treeDepth * 16).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hasChildren = treeNode.children.isNotEmpty() ||
                        treeNode is SizeNode.Function || treeNode is SizeNode.DataSegment
                    val prefix = if (hasChildren) "\u25B8 " else "  "
                    val weight = if (isSelected) FontWeight.Bold else FontWeight.Normal

                    Text(
                        "$prefix${treeNode.name}",
                        style = TextStyle(
                            color = HexanaTheme.textColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = weight,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatSize(treeNode.size),
                        style = TextStyle(
                            color = HexanaTheme.textColor.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                        ),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        VerticalScrollbar(
            rememberScrollbarAdapter(lazyListState),
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

private data class FlatTreeEntry(val node: SizeNode, val depth: Int)

private fun flattenTree(root: SizeNode): List<FlatTreeEntry> {
    val result = mutableListOf<FlatTreeEntry>()
    fun walk(node: SizeNode, depth: Int) {
        result.add(FlatTreeEntry(node, depth))
        for (child in node.children) {
            walk(child, depth + 1)
        }
    }
    walk(root, 0)
    return result
}

internal fun formatSize(bytes: Int): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}