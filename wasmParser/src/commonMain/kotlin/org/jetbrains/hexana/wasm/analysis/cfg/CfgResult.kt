package org.jetbrains.hexana.wasm.analysis.cfg

import org.jetbrains.hexana.wasm.analysis.graph.DirectedGraph
import org.jetbrains.hexana.wasm.analysis.graph.DominatorResult

class CfgResult(
    val funcIndex: Int,
    val blocks: List<BasicBlock>,
    val graph: DirectedGraph,
    val dominators: DominatorResult
)