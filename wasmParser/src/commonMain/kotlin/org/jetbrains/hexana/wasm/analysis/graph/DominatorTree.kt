package org.jetbrains.hexana.wasm.analysis.graph

object DominatorTree {

    fun compute(graph: DirectedGraph): DominatorResult {
        val nodeIds = graph.nodes.map { it.id }
        if (nodeIds.size <= 1) {
            val idom = mutableMapOf<Int, Int?>()
            if (nodeIds.isNotEmpty()) idom[nodeIds[0]] = null
            return DominatorResult(idom, emptyMap(), emptyMap(), emptySet())
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
        val unreachable = nodeIds.filter { it !in visited }.toSet()

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
        val idomMap = HashMap<Int, Int?>(n * 2)
        idomMap[graph.root] = null
        for (i in 1 until n) {
            idomMap[dfsOrder[i]] = dfsOrder[idom[i]]
        }

        val childrenMap = HashMap<Int, MutableList<Int>>(n * 2)
        for ((node, parent) in idomMap) {
            if (parent != null) {
                childrenMap.getOrPut(parent) { ArrayList(4) }.add(node)
            }
        }

        // Dominance frontier
        val df = HashMap<Int, MutableSet<Int>>(n)
        for (nodeId in dfsOrder) {
            val preds = graph.predecessors[nodeId] ?: continue
            if (preds.size < 2) continue
            for (p in preds) {
                if (p !in dfNum) continue
                var runner = p
                while (runner != idomMap[nodeId]) {
                    df.getOrPut(runner) { mutableSetOf() }.add(nodeId)
                    runner = idomMap[runner] ?: break
                }
            }
        }

        return DominatorResult(idomMap, childrenMap, df, unreachable)
    }
}