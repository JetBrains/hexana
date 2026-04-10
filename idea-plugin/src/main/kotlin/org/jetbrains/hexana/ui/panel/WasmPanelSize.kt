package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.analysis.SizeAnalysisBuilder
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
import org.jetbrains.hexana.wasm.sections.WasmDataRecord
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text

@Composable
fun WasmPanelSize(provider: WasmBinaryProvider, state: HexViewStateHolder) {
    val module = remember(provider) { provider.sizeAnalysis } ?: return
    val builder = remember(provider) { provider.sizeAnalysisBuilder }
    val codeRecords = remember(provider) { provider.codeRecords }
    val dataRecords = remember(provider) { provider.data }
    SizeTreeContent(module, builder, codeRecords, dataRecords, state)
}

@Composable
fun WasmPanelSize(provider: WasmComponentBinaryProvider, state: HexViewStateHolder) {
    val module = remember(provider) { provider.sizeAnalysis } ?: return
    SizeTreeContent(module, builder = null, codeRecords = emptyList(), dataRecords = emptyList(), state)
}

@Composable
private fun SizeTreeContent(
    module: SizeNode.Module,
    builder: SizeAnalysisBuilder?,
    codeRecords: List<WasmCodeRecord>,
    dataRecords: List<WasmDataRecord>,
    state: HexViewStateHolder,
) {
    var tree by remember { mutableStateOf<Tree<Any>?>(null) }

    LaunchedEffect(module) {
        tree = withContext(Dispatchers.IO) {
            val detailCache = mutableMapOf<Any, List<SizeNode>>()
            buildTree {
                addSizeChildren(module, builder, codeRecords, dataRecords, detailCache)
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

    LazyTree(
        tree = currentTree,
        treeState = treeState,
        modifier = Modifier.fillMaxSize(),
        onElementClick = { element ->
            val data = element.data
            if (data is SizeNode && data.size > 0) {
                state.scrollHexViewTo(data.offset, data.size)
            }
        },
    ) { element ->
        val data = element.data
        val label = if (data is SizeNode) sizeNodeLabel(data, module.size) else data.toString()
        Text(
            label,
            modifier = Modifier.padding(2.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

private fun TreeGeneratorScope<Any>.addSizeChildren(
    parent: SizeNode,
    builder: SizeAnalysisBuilder?,
    codeRecords: List<WasmCodeRecord>,
    dataRecords: List<WasmDataRecord>,
    detailCache: MutableMap<Any, List<SizeNode>>,
) {
    val children = resolveChildren(parent, builder, codeRecords, dataRecords, detailCache)
    for (child in children) {
        val grandchildren = resolveChildren(child, builder, codeRecords, dataRecords, detailCache)
        if (grandchildren.isNotEmpty()) {
            addNode(child) {
                addSizeChildren(child, builder, codeRecords, dataRecords, detailCache)
            }
        } else {
            addLeaf(child)
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

    return when (node) {
        is SizeNode.Function -> {
            cache.getOrPut("func:${node.funcIndex}") {
                builder.buildFunctionDetail(codeRecords, node.funcIndex)
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

private fun sizeNodeLabel(node: SizeNode, moduleSize: Int): String {
    val pct = node.percentage(moduleSize)
    val pctStr = "%.1f%%".format(pct)
    val hex = "0x${node.offset.toString(16).uppercase()}"
    return "${node.name}  ${formatSize(node.size)}  $pctStr  @$hex"
}

internal fun formatSize(bytes: Int): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}