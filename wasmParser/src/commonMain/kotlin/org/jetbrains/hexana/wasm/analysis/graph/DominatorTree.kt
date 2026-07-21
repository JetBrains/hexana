package org.jetbrains.hexana.wasm.analysis.graph

import org.jetbrains.hexana.io.allocateCommonByteBuffer

object DominatorTree {

    fun compute(graph: DirectedGraph): DominatorResult {
        val nodeIds = graph.nodes.map { it.id }
        if (nodeIds.size <= 1) {
            return pack(graph, emptyMap(), IntArray(0))
        }

        val nodeCount = nodeIds.size

        // Step 1: DFS numbering
        val dfsOrder = ArrayList<Int>(nodeCount)
        val dfNum = HashMap<Int, Int>(nodeCount * 2)
        val dfsParent = HashMap<Int, Int>(nodeCount * 2)

        val visited = HashSet<Int>(nodeCount * 2)
        val stack = ArrayDeque<Pair<Int, Iterator<Int>>>(nodeCount)
        visited.add(graph.root)
        dfNum[graph.root] = 0
        dfsOrder.add(graph.root)
        stack.addLast(graph.root to (graph.successors[graph.root] ?: emptyList()).iterator())

        while (stack.isNotEmpty()) {
            val (node, iter) = stack.last()
            if (iter.hasNext()) {
                val succ = iter.next()
                if (succ !in visited) {
                    visited.add(succ)
                    dfsParent[succ] = node
                    dfNum[succ] = dfsOrder.size
                    dfsOrder.add(succ)
                    stack.addLast(succ to (graph.successors[succ] ?: emptyList()).iterator())
                }
            } else {
                stack.removeLast()
            }
        }

        val n = dfsOrder.size
        val unreachable = nodeIds.filter { it !in visited }.toIntArray()
        unreachable.sort()

        // Lengauer-Tarjan data structures
        val semi = IntArray(n) { it }
        val idom = IntArray(n) { -1 }
        val ancestor = IntArray(n) { -1 }
        val best = IntArray(n) { it }
        val bucket = Array(n) { mutableListOf<Int>() }

        fun eval(v: Int): Int {
            if (ancestor[v] == -1) return v
            // Iterative path compression (avoids stack overflow on deep graphs)
            val path = mutableListOf<Int>()
            var cur = v
            while (ancestor[cur] != -1 && ancestor[ancestor[cur]] != -1) {
                path.add(cur)
                cur = ancestor[cur]
            }
            for (node in path.asReversed()) {
                val a = ancestor[node]
                if (a == -1) continue
                if (semi[best[a]] < semi[best[node]]) {
                    best[node] = best[a]
                }
                ancestor[node] = ancestor[a]
            }
            return best[v]
        }

        fun link(parent: Int, child: Int) {
            ancestor[child] = parent
        }

        // Process vertices in reverse DFS order
        for (i in n - 1 downTo 1) {
            val w = dfsOrder[i]
            val wParent = dfsParent[w]!!
            val wParentDfn = dfNum[wParent]!!

            for (v in graph.predecessors[w] ?: emptyList()) {
                if (v !in dfNum) continue
                val u = eval(dfNum[v]!!)
                if (semi[u] < semi[i]) {
                    semi[i] = semi[u]
                }
            }

            bucket[semi[i]].add(i)
            link(wParentDfn, i)

            for (v in bucket[wParentDfn]) {
                val u = eval(v)
                idom[v] = if (semi[u] < semi[v]) u else wParentDfn
            }
            bucket[wParentDfn].clear()
        }

        // Adjust idom
        for (i in 1 until n) {
            if (idom[i] != semi[i]) {
                idom[i] = idom[idom[i]]
            }
        }

        // Convert to nodeId-indexed
        val idomByNode = HashMap<Int, Int>(n * 2)
        for (i in 1 until n) {
            idomByNode[dfsOrder[i]] = dfsOrder[idom[i]]
        }

        return pack(graph, idomByNode, unreachable)
    }

    /**
     * Dominance frontier of each reachable node. Near-quadratic on join-heavy
     * graphs (e.g. whole-module call graphs) — compute on demand for small CFGs
     * only, never eagerly for module-level analysis.
     */
    fun dominanceFrontier(graph: DirectedGraph, result: DominatorResult): Map<Int, Set<Int>> {
        val df = HashMap<Int, MutableSet<Int>>()
        for (node in graph.nodes) {
            val nodeId = node.id
            if (nodeId !in result.idom) continue
            val preds = graph.predecessors[nodeId] ?: continue
            if (preds.size < 2) continue
            for (p in preds) {
                if (p !in result.idom) continue
                var runner = p
                while (runner != result.idom[nodeId]) {
                    df.getOrPut(runner) { mutableSetOf() }.add(nodeId)
                    runner = result.idom[runner] ?: break
                }
            }
        }
        return df
    }

    /** Pack idom + children (CSR) + unreachable into one buffer; see [DominatorResult] layout. */
    private fun pack(
        graph: DirectedGraph,
        idomByNode: Map<Int, Int>,
        unreachableSorted: IntArray
    ): DominatorResult {
        val nodes = graph.nodes
        val n = nodes.size

        var minId = 0
        var idRange = 0
        if (n > 0) {
            minId = Int.MAX_VALUE
            var maxId = Int.MIN_VALUE
            for (node in nodes) {
                if (node.id < minId) minId = node.id
                if (node.id > maxId) maxId = node.id
            }
            idRange = maxId - minId + 1
        }

        val idToIndex = IntArray(idRange) { -1 }
        for (i in 0 until n) idToIndex[nodes[i].id - minId] = i

        // idom slot per dense node index: parent node id, -1 = root or unreachable
        val idomSlots = IntArray(n) { -1 }
        for ((node, parent) in idomByNode) {
            idomSlots[idToIndex[node - minId]] = parent
        }

        // Children CSR (inverse of idom), deterministic in node-list order
        val childCounts = IntArray(n)
        for (i in 0 until n) {
            val parent = idomSlots[i]
            if (parent != -1) childCounts[idToIndex[parent - minId]]++
        }
        val childOffset = IntArray(n + 1)
        for (i in 0 until n) childOffset[i + 1] = childOffset[i] + childCounts[i]
        val childData = IntArray(childOffset[n])
        val fill = IntArray(n)
        for (i in 0 until n) {
            val parent = idomSlots[i]
            if (parent != -1) {
                val p = idToIndex[parent - minId]
                childData[childOffset[p] + fill[p]] = nodes[i].id
                fill[p]++
            }
        }

        val totalInts = idRange + n + (n + 1) + childData.size + unreachableSorted.size
        val buf = allocateCommonByteBuffer(totalInts * 4)
        for (v in idToIndex) buf.putIntLE(v)
        val idomPos = buf.position()
        for (v in idomSlots) buf.putIntLE(v)
        val childOffsetPos = buf.position()
        for (v in childOffset) buf.putIntLE(v)
        val childDataPos = buf.position()
        for (v in childData) buf.putIntLE(v)
        val unreachablePos = buf.position()
        for (v in unreachableSorted) buf.putIntLE(v)

        return DominatorResult(
            buf, minId, idRange,
            idomPos, childOffsetPos, childDataPos,
            unreachablePos, unreachableSorted.size,
            graph.root
        )
    }
}
