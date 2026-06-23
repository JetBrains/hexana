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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.SpeedSearchableTree
import org.jetbrains.hexana.ui.borderBottom
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.analysis.SizeAnalysisBuilder
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.theme.treeStyle
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.sections.WasmDataRecord
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.ui.component.SpeedSearchArea
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

/**
 * The built size tree plus depth metrics computed once, off the EDT. Walking
 * the tree (`walkDepthFirst()`) is O(nodes) and was previously done in the
 * composable body on every render — for a large module (one node per function /
 * segment) that froze the EDT on startup (it was the top frame in the freeze
 * stack: `PanelSize.maxDepth`). [depthById] keeps `maxExpandedDepth` O(openNodes).
 */
private data class SizeTreeData(
    val tree: Tree<Any>,
    val maxDepth: Int,
    val depthById: Map<Any, Int>,
)

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

    var treeData by remember { mutableStateOf<SizeTreeData?>(null) }

    LaunchedEffect(module, sortColumn, sortAsc) {
        treeData = withContext(Dispatchers.IO) {
            // Cooperative cancellation: switching away from this tab removes the
            // panel from composition and Compose cancels this LaunchedEffect. The
            // build/walk below are CPU-bound, so they poll the job and stop
            // promptly instead of running to completion on the IO pool.
            val job = coroutineContext.job
            val isCancelled: () -> Boolean = { !job.isActive }
            val detailCache = mutableMapOf<Any, List<BinarySizeNode>>()
            val builtTree = buildTree {
                addSizeChildren(module, builder, provider, dataRecords, detailCache, sortColumn, sortAsc, isCancelled)
            }
            // Depth metrics computed here, off the EDT — see [SizeTreeData].
            val depthById = HashMap<Any, Int>()
            var maxDepth = 0
            var visited = 0
            for (element in builtTree.walkDepthFirst()) {
                if (visited++ and 0xFFF == 0) ensureActive()
                if (element.depth > maxDepth) maxDepth = element.depth
                element.id?.let { depthById[it] = element.depth }
            }
            SizeTreeData(builtTree, maxDepth, depthById)
        }
    }

    val currentData = treeData
    if (currentData == null) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text(
                "Loading...",
                style = TextStyle(fontFamily = FontFamily.Monospace),
            )
        }
        return
    }
    val currentTree = currentData.tree

    val treeState = remember(currentTree) {
        TreeState(SelectableLazyListState(LazyListState()))
    }

    val treeMetrics = JewelTheme.treeStyle.metrics
    val chevronSpacerWidth = 16.dp + treeMetrics.chevronContentGap
    val indentSize = treeMetrics.indentSize
    val maxDepth = currentData.maxDepth
    val maxIndent = indentSize * maxDepth
    val nameColumnWidth = 200.dp
    val sizeColumnWidth = 144.dp + maxIndent
    val percentColumnWidth = 112.dp + maxIndent
    val expandedNestingDepth by remember(currentData) {
        derivedStateOf {
            treeState.openNodes.mapNotNull { currentData.depthById[it] }.maxOfOrNull { it + 1 } ?: 0
        }
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
            // Type-to-search find-and-jump over the size tree: highlights matches,
            // arrow keys move between them, auto-scrolls to the match. Mirrors the
            // StructureView speed-search usage. No row filtering — matches the
            // chosen find-and-jump behavior and preserves the tree's lazy expansion.
            SpeedSearchArea(modifier = Modifier.fillMaxSize()) {
                SpeedSearchableTree(
                    tree = currentTree,
                    nodeText = { (it.data as? BinarySizeNode)?.name },
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
    isCancelled: () -> Boolean = { false },
) {
    if (isCancelled()) return
    val children = resolveChildren(parent, builder, provider, dataRecords, detailCache)
        .sortedWith(sizeNodeComparator(sortColumn, sortAsc))
    for (child in children) {
        if (isCancelled()) return
        val grandchildren = resolveChildren(child, builder, provider, dataRecords, detailCache)
        // Pass `child` as the node id. Jewel's addNode/addLeaf default the id to null, and
        // TreeBuilder.build() then falls back to String.valueOf(childIndex) — unique only among
        // siblings, so a node and its own first child collide ("0"). Jewel keys expansion state
        // (openNodes / depthById) on the id, which breaks expansion for DEEP trees (the component
        // case: a Component node and its first child share an id). The SizeNode instance is a
        // unique, stable id (identity-based; children are cached so re-evaluation reuses them).
        if (grandchildren.isNotEmpty()) {
            addNode(child, child) {
                // The childrenGenerator block is stored on Tree$Element$Node and invoked lazily by
                // Jewel's evaluateChildren() when the user expands the row — long after the build
                // coroutine (whose job.isActive drives isCancelled) has completed.  Passing
                // isCancelled here would make job.isActive == false → isCancelled() == true →
                // addSizeChildren returns immediately → children never appear.  Lazy expansion must
                // always run to completion; pass { false } so only the initial build loop can be
                // cancelled, not the on-demand child evaluation.
                addSizeChildren(child, builder, provider, dataRecords, detailCache, sortColumn, sortAsc) { false }
            }
        } else {
            addLeaf(child, child)
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

    // Embedded modules / nested components expand lazily from their own byte slice. This is
    // independent of `builder` (null for a component provider) and is the path that lets a
    // composed component be drilled into without eagerly building the whole nested tree.
    when (node) {
        is SizeNode.EmbeddedComponent -> return lazyEmbeddedChildren(node.slice, node, cache)
        is SizeNode.EmbeddedModule -> return lazyEmbeddedChildren(node.slice, node, cache)
        else -> {}
    }

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

/**
 * Parses an embedded module / nested-component [slice] and returns its size-tree children on first
 * access (cached by node). Building one level at a time keeps composed components responsive: the
 * children it returns are themselves lazily-expandable (deeper embedded nodes carry their own slice;
 * core-module children are this module's sections/functions). Returns empty if the slice doesn't parse.
 */
private fun lazyEmbeddedChildren(
    slice: CommonByteBuffer?,
    node: BinarySizeNode,
    cache: MutableMap<Any, List<BinarySizeNode>>,
): List<BinarySizeNode> {
    if (slice == null) return emptyList()
    return cache.getOrPut(node) {
        val sub = (WasmParser().parse(slice) as? WasmParseSuccess)?.wasmModule
        if (sub == null) emptyList() else SizeAnalysisBuilder(slice, sub).buildTree().children
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
