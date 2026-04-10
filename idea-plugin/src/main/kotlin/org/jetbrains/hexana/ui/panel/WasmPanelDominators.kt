package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphNode
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphResult
import org.jetbrains.hexana.wasm.analysis.cfg.CfgBuilder
import org.jetbrains.hexana.wasm.analysis.cfg.CfgResult
import org.jetbrains.hexana.wasm.analysis.graph.DominatorResult
import org.jetbrains.jewel.ui.component.Text

@Composable
fun WasmPanelDominators(provider: WasmBinaryProvider, state: HexViewStateHolder) {
    val callGraphResult = remember(provider) { provider.callGraphResult } ?: return
    val dominators = remember(provider) { provider.callGraphDominators } ?: return

    DominatorsPanelContent(callGraphResult, dominators, provider, state)
}

@Composable
private fun DominatorsPanelContent(
    callGraphResult: CallGraphResult,
    dominators: DominatorResult,
    provider: WasmBinaryProvider,
    state: HexViewStateHolder,
) {
    var selectedNodeId by remember { mutableStateOf(CallGraphNode.VIRTUAL_ROOT_ID) }
    var showUnreachable by remember { mutableStateOf(false) }
    val cfgCache = remember { mutableMapOf<Int, CfgResult>() }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left pane: call graph dominator tree (~40%)
        Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
            DominatorTreeView(
                callGraphResult,
                dominators,
                selectedNodeId,
                showUnreachable,
                onSelect = { selectedNodeId = it },
                onToggleUnreachable = { showUnreachable = !showUnreachable },
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.Gray.copy(alpha = 0.4f))
        )

        // Right pane: CFG dominator detail (~60%)
        Box(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
            val selectedNode = callGraphResult.nodeMap[selectedNodeId]
            when (selectedNode) {
                is CallGraphNode.DefinedFunction -> {
                    val importCount = callGraphResult.nodeMap.values
                        .count { it is CallGraphNode.ImportedFunction }
                    val codeIndex = selectedNode.funcIndex - importCount
                    val record = provider.codeRecords.getOrNull(codeIndex)
                    if (record != null) {
                        val cfgResult = cfgCache.getOrPut(selectedNode.funcIndex) {
                            CfgBuilder.build(record, selectedNode.funcIndex)
                        }
                        CfgDominatorTable(cfgResult)
                    } else {
                        InfoMessage("No code record for func ${selectedNode.funcIndex}")
                    }
                }
                is CallGraphNode.ImportedFunction -> {
                    InfoMessage(
                        "Imported function: ${selectedNode.name} " +
                            "(module: ${selectedNode.module})"
                    )
                }
                is CallGraphNode.VirtualRoot -> {
                    InfoMessage("Virtual root -- select a defined function to view its CFG")
                }
                else -> {
                    InfoMessage("Select a node to view details")
                }
            }
        }
    }
}

@Composable
private fun InfoMessage(text: String) {
    Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(
            text,
            style = TextStyle(
                color = HexanaTheme.textColor.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

@Composable
private fun DominatorTreeView(
    callGraphResult: CallGraphResult,
    dominators: DominatorResult,
    selectedNodeId: Int,
    showUnreachable: Boolean,
    onSelect: (Int) -> Unit,
    onToggleUnreachable: () -> Unit,
) {
    val flatNodes = remember(dominators, showUnreachable) {
        buildFlatDominatorTree(callGraphResult, dominators, showUnreachable)
    }
    val lazyListState = rememberLazyListState()
    val unreachableCount = dominators.unreachable.size

    Column(modifier = Modifier.fillMaxSize()) {
        // Toggle for unreachable functions
        if (unreachableCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleUnreachable() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = if (showUnreachable) {
                    "Hide unreachable ($unreachableCount)"
                } else {
                    "Show unreachable ($unreachableCount)"
                }
                Text(
                    label,
                    style = TextStyle(
                        color = HexanaTheme.textColor.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
            ) {
                items(flatNodes.size) { index ->
                    val entry = flatNodes[index]
                    val isSelected = entry.nodeId == selectedNodeId
                    val rowBg = if (isSelected) {
                        HexanaTheme.hexCellHoverActiveColor
                    } else {
                        Color.Unspecified
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .clickable { onSelect(entry.nodeId) }
                            .padding(
                                start = (entry.depth * 16).dp,
                                end = 4.dp,
                                top = 2.dp,
                                bottom = 2.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val hasChildren = entry.hasChildren
                        val prefix = if (hasChildren) "\u25B8 " else "  "
                        val weight = if (isSelected) FontWeight.Bold else FontWeight.Normal

                        Text(
                            "$prefix${entry.label}",
                            style = TextStyle(
                                color = if (entry.unreachable) {
                                    HexanaTheme.textColor.copy(alpha = 0.4f)
                                } else {
                                    HexanaTheme.textColor
                                },
                                fontFamily = FontFamily.Monospace,
                                fontWeight = weight,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            entry.subtreeSize.toString(),
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
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private data class FlatDomEntry(
    val nodeId: Int,
    val label: String,
    val depth: Int,
    val subtreeSize: Int,
    val hasChildren: Boolean,
    val unreachable: Boolean,
)

private fun buildFlatDominatorTree(
    callGraphResult: CallGraphResult,
    dominators: DominatorResult,
    showUnreachable: Boolean,
): List<FlatDomEntry> {
    val result = mutableListOf<FlatDomEntry>()

    fun walk(nodeId: Int, depth: Int) {
        val node = callGraphResult.nodeMap[nodeId]
        val label = buildNodeLabel(node, nodeId)
        val children = dominators.children[nodeId] ?: emptyList()
        val subtreeSize = dominators.subtree(nodeId).size
        result.add(
            FlatDomEntry(
                nodeId = nodeId,
                label = label,
                depth = depth,
                subtreeSize = subtreeSize,
                hasChildren = children.isNotEmpty(),
                unreachable = false,
            )
        )
        for (child in children.sorted()) {
            walk(child, depth + 1)
        }
    }

    walk(CallGraphNode.VIRTUAL_ROOT_ID, 0)

    if (showUnreachable) {
        for (nodeId in dominators.unreachable.sorted()) {
            val node = callGraphResult.nodeMap[nodeId]
            val label = buildNodeLabel(node, nodeId)
            result.add(
                FlatDomEntry(
                    nodeId = nodeId,
                    label = "$label (unreachable)",
                    depth = 0,
                    subtreeSize = 1,
                    hasChildren = false,
                    unreachable = true,
                )
            )
        }
    }

    return result
}

private fun buildNodeLabel(node: CallGraphNode?, nodeId: Int): String {
    return when (node) {
        is CallGraphNode.VirtualRoot -> "<root>"
        is CallGraphNode.ImportedFunction -> "[$nodeId] ${node.name}"
        is CallGraphNode.DefinedFunction -> "[$nodeId] ${node.name}"
        is CallGraphNode.ComponentFunction -> "[$nodeId] ${node.name}"
        null -> "[$nodeId] <unknown>"
    }
}

@Composable
private fun CfgDominatorTable(cfgResult: CfgResult) {
    val blocks = cfgResult.blocks
    val doms = cfgResult.dominators

    if (blocks.isEmpty()) {
        InfoMessage("No basic blocks")
        return
    }

    val columns = listOf(
        TableColumn("Block", modifier = Modifier.widthIn(min = 100.dp)) { index ->
            val block = blocks[index]
            val kindLabel = block.kind.name
            TableCellValue("$kindLabel #${block.id}", tooltip = "$kindLabel #${block.id}")
        },
        TableColumn("Indices", modifier = Modifier.widthIn(min = 80.dp)) { index ->
            val block = blocks[index]
            val range = "${block.startIndex}..${block.endIndex}"
            TableCellValue(range)
        },
        TableColumn("Idom", modifier = Modifier.widthIn(min = 60.dp)) { index ->
            val block = blocks[index]
            val idom = doms.idom[block.id]
            val idomStr = idom?.toString() ?: "-"
            TableCellValue(idomStr)
        },
        TableColumn("Dominates", modifier = Modifier.fillMaxWidth()) { index ->
            val block = blocks[index]
            val children = doms.children[block.id] ?: emptyList()
            val childrenStr = if (children.isEmpty()) "-" else children.sorted().joinToString(", ")
            TableCellValue(childrenStr)
        },
    )

    Table(columns, blocks.size)
}