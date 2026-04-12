package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphNode
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphResult
import org.jetbrains.hexana.wasm.analysis.cfg.BasicBlock
import org.jetbrains.hexana.wasm.analysis.cfg.CfgBuilder
import org.jetbrains.hexana.wasm.analysis.cfg.CfgResult
import org.jetbrains.hexana.wasm.analysis.graph.DominatorResult
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text

@Composable
fun WasmPanelDominators(provider: WasmBinaryProvider, state: HexViewStateHolder) {
    var callGraphResult by remember { mutableStateOf<CallGraphResult?>(null) }
    var dominators by remember { mutableStateOf<DominatorResult?>(null) }

    LaunchedEffect(provider) {
        withContext(Dispatchers.IO) {
            val cgr = provider.callGraphResult
            val dom = provider.callGraphDominators
            callGraphResult = cgr
            dominators = dom
        }
    }

    val cgr = callGraphResult
    val dom = dominators
    if (cgr == null || dom == null) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text("Loading...", style = TextStyle(fontFamily = FontFamily.Monospace))
        }
        return
    }

    var selectedFuncIndex by remember { mutableStateOf<Int?>(null) }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left pane: call graph dominator tree (~40%)
        Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
            CallGraphDominatorTree(cgr, dom) { nodeId ->
                val node = cgr.nodeMap[nodeId]
                if (node is CallGraphNode.DefinedFunction) {
                    selectedFuncIndex = node.funcIndex
                }
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.Gray.copy(alpha = 0.4f))
        )

        // Right pane: CFG dominator tree for selected function (~60%)
        Box(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
            val funcIdx = selectedFuncIndex
            if (funcIdx != null) {
                CfgDominatorPane(provider, cgr, funcIdx)
            } else {
                InfoMessage("Select a defined function to view its CFG dominators")
            }
        }
    }
}

@Composable
private fun CfgDominatorPane(
    provider: WasmBinaryProvider,
    callGraphResult: CallGraphResult,
    funcIndex: Int,
) {
    var cfgResult by remember(funcIndex) { mutableStateOf<CfgResult?>(null) }

    LaunchedEffect(funcIndex) {
        cfgResult = withContext(Dispatchers.IO) {
            val importCount = callGraphResult.nodeMap.values
                .count { it is CallGraphNode.ImportedFunction }
            val codeIndex = funcIndex - importCount
            val record = provider.parseCodeRecord(codeIndex) ?: return@withContext null
            CfgBuilder.build(record, funcIndex)
        }
    }

    val result = cfgResult
    if (result == null) {
        InfoMessage("Loading CFG...")
        return
    }
    CfgDominatorTree(result)
}

@Composable
private fun InfoMessage(text: String) {
    Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(
            text,
            style = TextStyle(
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

@Composable
private fun CallGraphDominatorTree(
    callGraphResult: CallGraphResult,
    dominators: DominatorResult,
    onSelect: (Int) -> Unit,
) {
    var tree by remember { mutableStateOf<Tree<Any>?>(null) }

    LaunchedEffect(dominators) {
        tree = withContext(Dispatchers.IO) {
            val subtreeSizes = mutableMapOf<Int, Int>()
            fun computeSize(nodeId: Int): Int {
                val children = dominators.children[nodeId] ?: emptyList()
                val size = 1 + children.sumOf { computeSize(it) }
                subtreeSizes[nodeId] = size
                return size
            }
            computeSize(CallGraphNode.VIRTUAL_ROOT_ID)

            buildTree {
                fun TreeGeneratorScope<Any>.walkDom(nodeId: Int) {
                    val children = (dominators.children[nodeId] ?: emptyList()).sorted()
                    val entry = DomEntry(
                        nodeId, callGraphResult.nodeMap[nodeId],
                        subtreeSizes[nodeId] ?: 1
                    )
                    if (children.isNotEmpty()) {
                        addNode(entry) {
                            for (childId in children) { walkDom(childId) }
                        }
                    } else {
                        addLeaf(entry)
                    }
                }
                walkDom(CallGraphNode.VIRTUAL_ROOT_ID)

                if (dominators.unreachable.isNotEmpty()) {
                    addNode(UnreachableHeader(dominators.unreachable.size)) {
                        for (nodeId in dominators.unreachable.sorted()) {
                            addLeaf(
                                DomEntry(nodeId, callGraphResult.nodeMap[nodeId], 1, unreachable = true)
                            )
                        }
                    }
                }
            }
        }
    }

    val currentTree = tree
    if (currentTree == null) {
        InfoMessage("Loading...")
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
            if (data is DomEntry) {
                onSelect(data.nodeId)
            }
        },
    ) { element ->
        val label = when (val data = element.data) {
            is DomEntry -> domEntryLabel(data)
            is UnreachableHeader -> "Unreachable (${data.count})"
            else -> data.toString()
        }
        Text(
            label,
            modifier = Modifier.padding(2.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun CfgDominatorTree(cfgResult: CfgResult) {
    val blocks = cfgResult.blocks
    val doms = cfgResult.dominators
    if (blocks.isEmpty()) {
        InfoMessage("No basic blocks")
        return
    }

    val blockById = remember(blocks) { blocks.associateBy { it.id } }

    val tree = remember(cfgResult) {
        buildTree {
            fun TreeGeneratorScope<Any>.walkCfgDom(blockId: Int) {
                val block = blockById[blockId] ?: return
                val children = (doms.children[blockId] ?: emptyList()).sorted()
                val entry = CfgBlockEntry(block, doms.idom[blockId], children.size)
                if (children.isNotEmpty()) {
                    addNode(entry) {
                        for (childId in children) { walkCfgDom(childId) }
                    }
                } else {
                    addLeaf(entry)
                }
            }
            val entryBlockId = blocks.first().id
            walkCfgDom(entryBlockId)
        }
    }

    val treeState = remember(tree) {
        TreeState(SelectableLazyListState(LazyListState()))
    }

    LazyTree(
        tree = tree,
        treeState = treeState,
        modifier = Modifier.fillMaxSize(),
    ) { element ->
        val label = when (val data = element.data) {
            is CfgBlockEntry -> cfgBlockLabel(data)
            else -> data.toString()
        }
        Text(
            label,
            modifier = Modifier.padding(2.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

// Data classes for tree nodes

private data class DomEntry(
    val nodeId: Int,
    val node: CallGraphNode?,
    val subtreeSize: Int,
    val unreachable: Boolean = false,
)

private data class UnreachableHeader(val count: Int)

private data class CfgBlockEntry(
    val block: BasicBlock,
    val idom: Int?,
    val dominatesCount: Int,
)

// Label formatting

private fun domEntryLabel(entry: DomEntry): String {
    val name = when (val node = entry.node) {
        is CallGraphNode.VirtualRoot -> "<root>"
        is CallGraphNode.ImportedFunction -> "[${entry.nodeId}] ${node.module}.${node.name}"
        is CallGraphNode.DefinedFunction -> "[${entry.nodeId}] ${node.name}"
        is CallGraphNode.ComponentFunction -> "[${entry.nodeId}] ${node.name}"
        null -> "[${entry.nodeId}] <unknown>"
    }
    val suffix = if (entry.unreachable) " (unreachable)" else ""
    val domCount = if (entry.subtreeSize > 1) "  dominates ${entry.subtreeSize - 1}" else ""
    return "$name$domCount$suffix"
}

private fun cfgBlockLabel(entry: CfgBlockEntry): String {
    val block = entry.block
    val idomStr = entry.idom?.let { "idom=#$it" } ?: "root"
    val domStr = if (entry.dominatesCount > 0) "  dominates ${entry.dominatesCount}" else ""
    return "${block.kind.name} #${block.id}  [${block.startIndex}..${block.endIndex}]  $idomStr$domStr"
}
