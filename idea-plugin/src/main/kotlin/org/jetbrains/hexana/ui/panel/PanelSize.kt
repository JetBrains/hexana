package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.borderBottom
import org.jetbrains.hexana.wasm.analysis.SizeAnalysisBuilder
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.theme.treeStyle
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.sections.WasmDataRecord
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar

private enum class SizeSortColumn { TYPE, SIZE, OFFSET }

/** Off-EDT-computed inputs for the size panel. */
private class SizePanelData(
    val root: BinarySizeNode?,
    val builder: SizeAnalysisBuilder?,
    val wasmProvider: WasmBinaryProvider?,
    val dataRecords: List<WasmDataRecord>,
)

@Composable
fun PanelSize(provider: BinaryProvider, state: HexViewStateHolder) {
    // Building the top-level size tree (`provider.sizeAnalysis`) and parsing the
    // data segments (`provider.data`) is expensive on large binaries and MUST NOT
    // run on the EDT. Computing them inside `remember { }` during composition
    // froze the UI. Compute off-thread and show a spinner until it lands; mirrors
    // WasmPanelWatVirtualized's load pattern. Provider lazies are synchronized and
    // `provider.bytes` hands out an independent-cursor duplicate, so this is safe.
    var loaded by remember(provider) { mutableStateOf<SizePanelData?>(null) }
    LaunchedEffect(provider) {
        loaded = withContext(Dispatchers.IO) {
            val root = provider.sizeAnalysis
            val wasmProvider = provider as? WasmBinaryProvider
            SizePanelData(
                root = root,
                builder = wasmProvider?.sizeAnalysisBuilder,
                wasmProvider = wasmProvider,
                dataRecords = if (root != null) wasmProvider?.data ?: emptyList() else emptyList(),
            )
        }
    }

    val data = loaded
    when {
        data == null -> {
            Box(modifier = Modifier.padding(8.dp)) {
                Text(
                    "Loading...",
                    style = TextStyle(fontFamily = FontFamily.Monospace),
                )
            }
        }
        data.root == null -> {
            Text("No size information available")
        }
        else -> {
            SizeTreeContent(data.root, data.builder, data.wasmProvider, data.dataRecords, state)
        }
    }
}

@Composable
private fun SizeTreeContent(
    module: BinarySizeNode,
    builder: SizeAnalysisBuilder?,
    provider: WasmBinaryProvider?,
    dataRecords: List<WasmDataRecord>,
    state: HexViewStateHolder,
) {
    var sortColumn by remember { mutableStateOf(SizeSortColumn.SIZE) }
    var sortAsc by remember { mutableStateOf(false) }

    var tree by remember { mutableStateOf<Tree<Any>?>(null) }

    LaunchedEffect(module, sortColumn, sortAsc) {
        tree = withContext(Dispatchers.IO) {
            val detailCache = mutableMapOf<Any, List<BinarySizeNode>>()
            buildTree {
                addSizeChildren(module, builder, provider, dataRecords, detailCache, sortColumn, sortAsc)
            }
        }
    }

    val currentTree = tree
    if (currentTree == null) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text(
                "Loading...",
                style = TextStyle(fontFamily = FontFamily.Monospace),
            )
        }
        return
    }

    val treeState = remember(currentTree) {
        TreeState(SelectableLazyListState(LazyListState()))
    }

    val treeMetrics = JewelTheme.treeStyle.metrics
    val chevronSpacerWidth = 16.dp + treeMetrics.chevronContentGap
    val indentSize = treeMetrics.indentSize
    val maxDepth = remember(currentTree) { currentTree.maxDepth() }
    val maxIndent = indentSize * maxDepth
    val nameColumnWidth = 200.dp
    val sizeColumnWidth = 144.dp + maxIndent
    val percentColumnWidth = 112.dp + maxIndent
    val expandedNestingDepth by remember(currentTree) {
        derivedStateOf { currentTree.maxExpandedDepth(treeState.openNodes) }
    }
    val nestingRightPadding = when (expandedNestingDepth) {
        0 -> 0.dp
        1 -> 20.dp
        else -> 40.dp
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SizeColumnHeaders(sortColumn, sortAsc, chevronSpacerWidth, nameColumnWidth, sizeColumnWidth, percentColumnWidth) { column ->
            if (sortColumn == column) {
                sortAsc = !sortAsc
            } else {
                sortColumn = column
                sortAsc = when (column) {
                    SizeSortColumn.TYPE -> true
                    SizeSortColumn.SIZE -> false
                    SizeSortColumn.OFFSET -> true
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyTree(
                tree = currentTree,
                treeState = treeState,
                modifier = Modifier.fillMaxSize(),
                onElementClick = { element ->
                    val data = element.data
                    if (data is BinarySizeNode && data.size > 0) {
                        state.scrollHexViewTo(data.offset, data.size)
                    }
                },
            ) { element ->
                if (element is Tree.Element.Leaf) {
                    Spacer(Modifier.width(chevronSpacerWidth))
                }
                val data = element.data
                if (data is BinarySizeNode) {
                    SizeNodeRow(data, module.size, nameColumnWidth, sizeColumnWidth, percentColumnWidth, nestingRightPadding)
                } else {
                    Text(
                        data.toString(),
                        modifier = Modifier.padding(2.dp),
                        style = TextStyle(fontFamily = FontFamily.Monospace),
                    )
                }
            }

            VerticalScrollbar(
                treeState.lazyListState.lazyListState,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private fun TreeGeneratorScope<Any>.addSizeChildren(
    parent: BinarySizeNode,
    builder: SizeAnalysisBuilder?,
    provider: WasmBinaryProvider?,
    dataRecords: List<WasmDataRecord>,
    detailCache: MutableMap<Any, List<BinarySizeNode>>,
    sortColumn: SizeSortColumn,
    sortAsc: Boolean,
) {
    val children = resolveChildren(parent, builder, provider, dataRecords, detailCache)
        .sortedWith(sizeNodeComparator(sortColumn, sortAsc))
    for (child in children) {
        val grandchildren = resolveChildren(child, builder, provider, dataRecords, detailCache)
        if (grandchildren.isNotEmpty()) {
            addNode(child) {
                addSizeChildren(child, builder, provider, dataRecords, detailCache, sortColumn, sortAsc)
            }
        } else {
            addLeaf(child)
        }
    }
}

private fun sizeNodeComparator(column: SizeSortColumn, asc: Boolean): Comparator<BinarySizeNode> {
    val base: Comparator<BinarySizeNode> = when (column) {
        SizeSortColumn.TYPE -> compareBy { it.name }
        SizeSortColumn.SIZE -> compareBy { it.size }
        SizeSortColumn.OFFSET -> compareBy { it.offset }
    }
    return if (asc) base else base.reversed()
}

private fun resolveChildren(
    node: BinarySizeNode,
    builder: SizeAnalysisBuilder?,
    provider: WasmBinaryProvider?,
    dataRecords: List<WasmDataRecord>,
    cache: MutableMap<Any, List<BinarySizeNode>>,
): List<BinarySizeNode> {
    if (node.children.isNotEmpty()) return node.children
    if (builder == null) return emptyList()

    return when (node) {
        is SizeNode.Function -> {
            cache.getOrPut("func:${node.funcIndex}") {
                val record = provider?.parseCodeRecord(node.funcIndex) ?: return emptyList()
                builder.buildFunctionDetail(record)
            }
        }
        is SizeNode.DataSegment -> {
            cache.getOrPut("data:${node.segmentIndex}") {
                builder.buildDataSegmentDetail(dataRecords, node.segmentIndex)
            }
        }
        else -> emptyList()
    }
}

@Composable
private fun SizeColumnHeaders(
    sortColumn: SizeSortColumn,
    sortAsc: Boolean,
    chevronSpacerWidth: Dp,
    nameColumnWidth: Dp,
    sizeColumnWidth: Dp,
    percentColumnWidth: Dp,
    onSort: (SizeSortColumn) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(start = 4.dp + 12.dp + chevronSpacerWidth + 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SortableHeaderCell("Type", SizeSortColumn.TYPE, sortColumn, sortAsc, Modifier.width(nameColumnWidth), onSort)
        SortableHeaderCell("Size", SizeSortColumn.SIZE, sortColumn, sortAsc, Modifier.width(sizeColumnWidth), onSort)
        SortableHeaderCell("%", SizeSortColumn.SIZE, sortColumn, sortAsc, Modifier.width(percentColumnWidth), onSort)
        SortableHeaderCell("Offset", SizeSortColumn.OFFSET, sortColumn, sortAsc, Modifier.width(percentColumnWidth), onSort)
    }
}

@Composable
private fun SortableHeaderCell(
    title: String,
    column: SizeSortColumn,
    activeColumn: SizeSortColumn,
    sortAsc: Boolean,
    modifier: Modifier,
    onSort: ((SizeSortColumn) -> Unit)?,
) {
    val isActive = column == activeColumn
    val indicatorColor = if (isActive) HexanaTheme.textColor else Color(238, 237, 237, 80)
    val indicator = if (isActive && sortAsc) "\u25B4" else "\u25BE"

    Box(
        modifier = modifier
            .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
            .then(if (onSort != null) Modifier.clickable { onSort(column) } else Modifier),
    ) {
        Row {
            Text(
                indicator,
                modifier = Modifier.padding(end = 4.dp),
                style = TextStyle(color = indicatorColor, fontFamily = FontFamily.Monospace),
            )
            Text(
                title,
                style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun SizeNodeRow(
    node: BinarySizeNode,
    moduleSize: Int,
    nameColumnWidth: Dp,
    sizeColumnWidth: Dp,
    percentColumnWidth: Dp,
    rightPadding: Dp,
) {
    val pct = node.percentage(moduleSize)
    val pctStr = "%.1f%%".format(pct)
    val hex = "0x${node.offset.toString(16).uppercase()}"
    val dimColor = HexanaTheme.textColor.copy(alpha = 0.6f)

    Row(
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            node.name,
            modifier = Modifier.width(nameColumnWidth),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
        Text(
            formatSize(node.size),
            modifier = Modifier.width(sizeColumnWidth).absolutePadding(right = rightPadding),
            style = TextStyle(fontFamily = FontFamily.Monospace, textAlign = TextAlign.End),
            maxLines = 1,
        )
        Text(
            pctStr,
            modifier = Modifier.width(percentColumnWidth).absolutePadding(right = rightPadding),
            style = TextStyle(fontFamily = FontFamily.Monospace, textAlign = TextAlign.End),
            maxLines = 1,
        )
        Text(
            hex,
            modifier = Modifier.width(percentColumnWidth).absolutePadding(right = rightPadding, left = 4.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace, color = dimColor),
            maxLines = 1,
        )
    }
}

private fun Tree<*>.maxDepth(): Int =
    walkDepthFirst().maxOfOrNull { it.depth } ?: 0

private fun Tree<*>.maxExpandedDepth(openNodes: Set<Any>): Int =
    walkDepthFirst().filter { it.id in openNodes }.maxOfOrNull { it.depth + 1 } ?: 0

internal fun formatSize(bytes: Int): String {
    return formatSize(bytes.toLong())
}

internal fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}