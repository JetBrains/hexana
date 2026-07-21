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
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphNode
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphResult
import org.jetbrains.hexana.wasm.analysis.graph.CallerPathTree
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text

private const val MAX_DEPTH = 64

@Composable
fun WasmPanelCallerPaths(
    callGraphResult: CallGraphResult,
    targetNodeId: Int,
) {
    var tree by remember(targetNodeId) { mutableStateOf<Tree<Any>?>(null) }

    LaunchedEffect(targetNodeId) {
        tree = withContext(Dispatchers.IO) {
            val callerTree = CallerPathTree(callGraphResult.graph)
            buildTree {
                buildCallerNode(callerTree, callGraphResult, targetNodeId, emptySet(), 0)
            }
        }
    }

    val currentTree = tree
    if (currentTree == null) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text("Loading paths...", style = TextStyle(fontFamily = FontFamily.Monospace))
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
    ) { element ->
        val label = when (val data = element.data) {
            is CallerEntry -> callerEntryLabel(data)
            else -> data.toString()
        }
        Text(
            label,
            modifier = Modifier.padding(2.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

private fun TreeGeneratorScope<Any>.buildCallerNode(
    callerTree: CallerPathTree,
    callGraphResult: CallGraphResult,
    nodeId: Int,
    ancestors: Set<Int>,
    depth: Int,
) {
    val node = callGraphResult.nodeMap[nodeId]
    val callerCount = callerTree.callerCount(nodeId)
    val isCycle = nodeId in ancestors
    val isDepthLimit = depth >= MAX_DEPTH
    val isRoot = callerTree.isRoot(nodeId)

    val entry = CallerEntry(
        nodeId = nodeId,
        node = node,
        callerCount = callerCount,
        isRoot = isRoot,
        isCycle = isCycle,
        isDepthLimit = isDepthLimit,
    )

    if (isCycle || isDepthLimit || isRoot || callerCount == 0) {
        addLeaf(entry)
    } else {
        val callers = callerTree.callers(nodeId)
        addNode(entry) {
            val nextAncestors = ancestors + nodeId
            for (callerId in callers) {
                buildCallerNode(callerTree, callGraphResult, callerId, nextAncestors, depth + 1)
            }
        }
    }
}

internal data class CallerEntry(
    val nodeId: Int,
    val node: CallGraphNode?,
    val callerCount: Int,
    val isRoot: Boolean,
    val isCycle: Boolean,
    val isDepthLimit: Boolean,
)

internal fun callerEntryLabel(entry: CallerEntry): String {
    val name = when (val node = entry.node) {
        is CallGraphNode.VirtualRoot -> "<root>"
        is CallGraphNode.IndirectCallHub -> "<indirect calls>"
        is CallGraphNode.ImportedFunction -> "[${entry.nodeId}] ${node.module}.${node.name}"
        is CallGraphNode.DefinedFunction -> "[${entry.nodeId}] ${node.name}"
        is CallGraphNode.ComponentFunction -> "[${entry.nodeId}] ${node.name}"
        null -> "[${entry.nodeId}] <unknown>"
    }
    return buildString {
        append(name)
        if (entry.isRoot) append(" [root]")
        if (entry.isCycle) append(" (cycle)")
        if (entry.isDepthLimit) append(" (depth limit)")
        if (!entry.isRoot && !entry.isCycle && !entry.isDepthLimit && entry.callerCount > 0) {
            append(" (${entry.callerCount} callers)")
        }
    }
}