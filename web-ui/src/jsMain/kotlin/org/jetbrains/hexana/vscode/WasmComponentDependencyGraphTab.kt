package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.components.*
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.CanvasTextAlign
import org.w3c.dom.CanvasTextBaseline
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// --- Graph model ---

private enum class NodeKind { IMPORT, MODULE, NESTED_COMPONENT, CORE_INSTANCE, INSTANCE, EXPORT }

/** Serializable presentation boundary for dependency-graph data produced outside the Web UI. */
data class ComponentDependencyGraphNodeData(
    val id: String,
    val kind: String,
    val label: String,
    val sublabel: String? = null,
)

/** Serializable presentation boundary for dependency-graph edges produced outside the Web UI. */
data class ComponentDependencyGraphEdgeData(
    val fromId: String,
    val toId: String,
    val label: String? = null,
)

private data class GraphNode(
    val id: String,
    val kind: NodeKind,
    val label: String,
    val sublabel: String? = null,
    val groupMembers: List<String>? = null,
)

private data class GraphEdge(
    val fromId: String,
    val toId: String,
    val label: String? = null,
)

private data class DependencyGraph(
    val nodes: Map<String, GraphNode>,
    val edges: List<GraphEdge>,
)

// --- Graph building ---

private fun buildDependencyGraph(provider: WasmComponentBinaryProvider): DependencyGraph {
    val nodes = mutableMapOf<String, GraphNode>()
    val edges = mutableListOf<GraphEdge>()

    provider.imports.forEachIndexed { i, imp ->
        val id = "import:$i"
        val kindLabel = when (imp.externDescription) {
            is ComponentExternCoreType -> "core type"
            is ComponentExternType -> "type"
            is ComponentExternValueBound -> "value"
            is ComponentExternTypeBound -> "type bound"
            is ComponentExternComponent -> "component"
            is ComponentExternInstance -> "instance"
        }
        val version = if (imp.version != null) "@${imp.version}" else ""
        nodes[id] = GraphNode(id, NodeKind.IMPORT, imp.importName + version, kindLabel)
    }

    provider.modules.forEachIndexed { i, mod ->
        val id = "module:$i"
        nodes[id] = GraphNode(id, NodeKind.MODULE, "core module $i", "${mod.contentSize} bytes")
    }

    provider.nestedComponents.forEachIndexed { i, _ ->
        val id = "component:$i"
        val exportCount = provider.nestedComponentExports(i).size
        nodes[id] = GraphNode(id, NodeKind.NESTED_COMPONENT, "component $i", "$exportCount exports")
    }

    provider.coreInstances.forEachIndexed { i, inst ->
        val id = "core-instance:$i"
        when (inst) {
            is ModuleInstanceCoreExpression -> {
                nodes[id] = GraphNode(id, NodeKind.CORE_INSTANCE, "core instance $i", "instantiates module ${inst.moduleIndex}")
                edges.add(GraphEdge("module:${inst.moduleIndex}", id))
                for (arg in inst.instantiateArgs) {
                    edges.add(GraphEdge("core-instance:${arg.instanceIndex}", id, arg.name))
                }
            }
            is InlineInstanceCoreExpression -> {
                nodes[id] = GraphNode(id, NodeKind.CORE_INSTANCE, "core instance $i", "inline exports")
            }
        }
    }

    provider.instances.forEachIndexed { i, inst ->
        val id = "instance:$i"
        when (inst) {
            is ComponentInstanceExpression -> {
                nodes[id] = GraphNode(id, NodeKind.INSTANCE, "instance $i", "instantiates component ${inst.index}")
                edges.add(GraphEdge("component:${inst.index}", id))
                for (arg in inst.args) {
                    val sort = arg.sortIndex.sort
                    val sourceId = when (sort) {
                        is ComponentSortWithCoreSort -> when (sort.cs) {
                            ComponentCoreSort.Module -> "module:${arg.sortIndex.index}"
                            ComponentCoreSort.Instance -> "core-instance:${arg.sortIndex.index}"
                            else -> null
                        }
                        ComponentSort.Instance -> "instance:${arg.sortIndex.index}"
                        ComponentSort.Component -> "component:${arg.sortIndex.index}"
                        else -> null
                    }
                    if (sourceId != null) {
                        edges.add(GraphEdge(sourceId, id, arg.name))
                    }
                }
            }
            is InlineInstanceExpression -> {
                nodes[id] = GraphNode(id, NodeKind.INSTANCE, "instance $i", "inline exports")
            }
        }
    }

    provider.exports.forEachIndexed { i, exp ->
        val id = "export:$i"
        val sort = exp.sortIndex.sort
        val sortLabel = when (sort) {
            is ComponentSortWithCoreSort -> "core ${coreSortLabel(sort.cs)}"
            ComponentSort.Func -> "func"
            ComponentSort.Value -> "value"
            ComponentSort.Type -> "type"
            ComponentSort.Component -> "component"
            ComponentSort.Instance -> "instance"
        }
        val version = if (exp.version != null) "@${exp.version}" else ""
        nodes[id] = GraphNode(id, NodeKind.EXPORT, exp.exportName + version, sortLabel)

        val sourceId = when (sort) {
            is ComponentSortWithCoreSort -> when (sort.cs) {
                ComponentCoreSort.Module -> "module:${exp.sortIndex.index}"
                ComponentCoreSort.Instance -> "core-instance:${exp.sortIndex.index}"
                else -> null
            }
            ComponentSort.Instance -> "instance:${exp.sortIndex.index}"
            ComponentSort.Component -> "component:${exp.sortIndex.index}"
            ComponentSort.Func -> "instance:${exp.sortIndex.index}"
            else -> null
        }
        if (sourceId != null && nodes.containsKey(sourceId)) {
            edges.add(GraphEdge(sourceId, id))
        }
    }

    return DependencyGraph(nodes, edges)
}

private fun coreSortLabel(sort: ComponentCoreSort): String = when (sort) {
    ComponentCoreSort.Func -> "func"
    ComponentCoreSort.Table -> "table"
    ComponentCoreSort.Memory -> "memory"
    ComponentCoreSort.Global -> "global"
    ComponentCoreSort.Type -> "type"
    ComponentCoreSort.Module -> "module"
    ComponentCoreSort.Instance -> "instance"
}

// --- Grouping ---

private const val GROUP_THRESHOLD = 3

private fun groupGraph(graph: DependencyGraph, expandedGroups: Set<String>): DependencyGraph {
    val nodes = graph.nodes.toMutableMap()
    val edges = graph.edges.toMutableList()

    fun collapseGroup(groupId: String, kind: NodeKind, memberIds: List<String>, label: String, sublabel: String) {
        if (expandedGroups.contains(groupId)) return
        val groupNode = GraphNode(groupId, kind, label, sublabel, groupMembers = memberIds)
        nodes[groupId] = groupNode
        val memberSet = memberIds.toSet()
        val newEdges = edges.map { edge ->
            val from = if (edge.fromId in memberSet) groupId else edge.fromId
            val to = if (edge.toId in memberSet) groupId else edge.toId
            edge.copy(fromId = from, toId = to)
        }
        val seen = mutableSetOf<Pair<String, String>>()
        edges.clear()
        for (e in newEdges) {
            if (e.fromId == e.toId) continue
            val key = e.fromId to e.toId
            if (seen.add(key)) edges.add(e)
        }
        for (mid in memberIds) nodes.remove(mid)
    }

    groupByPrefix(nodes, NodeKind.IMPORT) { groupId, prefix, memberIds ->
        collapseGroup(groupId, NodeKind.IMPORT, memberIds, prefix, "${memberIds.size} imports")
    }
    groupByCoarsePrefix(nodes, NodeKind.IMPORT) { groupId, prefix, memberIds ->
        collapseGroup(groupId, NodeKind.IMPORT, memberIds, prefix, "${memberIds.size} imports")
    }
    groupByPrefix(nodes, NodeKind.EXPORT) { groupId, prefix, memberIds ->
        collapseGroup(groupId, NodeKind.EXPORT, memberIds, prefix, "${memberIds.size} exports")
    }
    groupByCoarsePrefix(nodes, NodeKind.EXPORT) { groupId, prefix, memberIds ->
        collapseGroup(groupId, NodeKind.EXPORT, memberIds, prefix, "${memberIds.size} exports")
    }

    val coreInstances = nodes.values.filter { it.kind == NodeKind.CORE_INSTANCE && it.groupMembers == null }
    val byModule = coreInstances.filter { it.sublabel?.startsWith("instantiates") == true }
        .groupBy { it.sublabel!! }
    for ((sub, members) in byModule) {
        if (members.size < GROUP_THRESHOLD) continue
        val memberIds = members.map { it.id }
        val groupId = "group:core-instance:${sub.substringAfterLast(' ')}"
        collapseGroup(groupId, NodeKind.CORE_INSTANCE, memberIds, "${members.size} core instances", sub)
    }

    groupSharedDependencies(nodes, edges, expandedGroups)

    return DependencyGraph(nodes, edges)
}

private fun groupByPrefix(
    nodes: MutableMap<String, GraphNode>,
    kind: NodeKind,
    onGroup: (groupId: String, prefix: String, memberIds: List<String>) -> Unit,
) {
    val candidates = nodes.values.filter { it.kind == kind && it.groupMembers == null }
    val byPrefix = candidates
        .filter { ':' in it.label }
        .groupBy { it.label.substringBefore('/').let { p -> if (p == it.label) it.label.substringBefore(':') + ":*" else "$p/…" } }
    for ((prefix, members) in byPrefix) {
        if (members.size < GROUP_THRESHOLD) continue
        val memberIds = members.map { it.id }
        val groupId = "group:${kind.name.lowercase()}:$prefix"
        onGroup(groupId, prefix, memberIds)
    }
}

private fun groupByCoarsePrefix(
    nodes: MutableMap<String, GraphNode>,
    kind: NodeKind,
    onGroup: (groupId: String, prefix: String, memberIds: List<String>) -> Unit,
) {
    val candidates = nodes.values.filter { it.kind == kind }
    val byNs = candidates
        .filter { ':' in it.label }
        .groupBy { it.label.substringBefore(':') }
    for ((ns, members) in byNs) {
        if (members.size < GROUP_THRESHOLD) continue
        val memberIds = members.flatMap { n -> n.groupMembers ?: listOf(n.id) }
        val groupId = "group:${kind.name.lowercase()}:$ns:*"
        onGroup(groupId, "$ns:…", memberIds)
    }
}

private fun groupSharedDependencies(
    nodes: MutableMap<String, GraphNode>,
    edges: MutableList<GraphEdge>,
    expandedGroups: Set<String>,
) {
    val incomingByTarget = mutableMapOf<String, MutableList<GraphEdge>>()
    for (e in edges) {
        if (e.toId != e.fromId) {
            incomingByTarget.getOrPut(e.toId) { mutableListOf() }.add(e)
        }
    }
    for ((targetId, incoming) in incomingByTarget) {
        val coreSourceEdges = incoming.filter { e ->
            val src = nodes[e.fromId]
            src != null && src.kind == NodeKind.CORE_INSTANCE && src.groupMembers == null
        }
        if (coreSourceEdges.size < GROUP_THRESHOLD) continue
        val memberIds = coreSourceEdges.map { it.fromId }.distinct()
        if (memberIds.size < GROUP_THRESHOLD) continue
        val groupId = "group:deps-of:$targetId"
        if (expandedGroups.contains(groupId)) continue
        val memberSet = memberIds.toSet()
        val groupNode = GraphNode(groupId, NodeKind.CORE_INSTANCE, "${memberIds.size} core instances",
            "dependencies of ${nodes[targetId]?.label ?: targetId}", groupMembers = memberIds)
        nodes[groupId] = groupNode
        val newEdges = edges.map { edge ->
            val from = if (edge.fromId in memberSet) groupId else edge.fromId
            val to = if (edge.toId in memberSet) groupId else edge.toId
            edge.copy(fromId = from, toId = to)
        }
        val seen = mutableSetOf<Pair<String, String>>()
        edges.clear()
        for (e in newEdges) {
            if (e.fromId == e.toId) continue
            val key = e.fromId to e.toId
            if (seen.add(key)) edges.add(e)
        }
        for (mid in memberIds) nodes.remove(mid)
    }
}

// --- Layout ---

private data class NodeRect(val x: Double, val y: Double, val width: Double, val height: Double)
private data class LayoutEdge(val fromId: String, val toId: String, val label: String?)
private enum class EdgeKind { NORMAL, SAME_LAYER, BACK_EDGE }

private enum class HighlightKind { NONE, OUTGOING, INCOMING, BIDIRECTIONAL }

private fun highlightKindFor(hoveredNodeId: String?, fromId: String, toId: String): HighlightKind = when (hoveredNodeId) {
    fromId -> HighlightKind.OUTGOING
    toId -> HighlightKind.INCOMING
    else -> HighlightKind.NONE
}
private data class GraphLayout(
    val nodePositions: Map<String, NodeRect>,
    val edges: List<LayoutEdge>,
    val totalWidth: Double,
    val totalHeight: Double,
)

private const val NODE_PADDING_H = 20.0
private const val NODE_PADDING_V = 12.0
private const val HORIZONTAL_GAP = 40.0
private const val VERTICAL_GAP = 80.0
private const val COMPONENT_GAP = 80.0
private const val MAX_ROW_WIDTH = 1800.0
private const val WRAP_ROW_GAP = 30.0

private fun measureTextWidth(ctx: CanvasRenderingContext2D, text: String, font: String): Double {
    ctx.font = font
    return ctx.measureText(text).width
}

private const val LABEL_FONT = "bold 13px monospace"
private const val SUB_FONT = "11px monospace"
private const val LABEL_HEIGHT = 16.0
private const val SUB_HEIGHT = 14.0

private fun findConnectedComponents(graph: DependencyGraph): List<Set<String>> {
    val parent = graph.nodes.keys.associateWith { it }.toMutableMap()
    fun find(x: String): String {
        var r = x
        while (parent[r] != r) r = parent[r]!!
        var c = x
        while (c != r) { val n = parent[c]!!; parent[c] = r; c = n }
        return r
    }
    fun union(a: String, b: String) { parent[find(a)] = find(b) }
    for (e in graph.edges) { if (e.fromId in parent && e.toId in parent) union(e.fromId, e.toId) }
    return graph.nodes.keys.groupBy { find(it) }.values.map { it.toSet() }
}

private fun layoutGraph(graph: DependencyGraph, ctx: CanvasRenderingContext2D): GraphLayout {
    val nodeSizes = mutableMapOf<String, Pair<Double, Double>>()
    for ((id, node) in graph.nodes) {
        val labelWidth = measureTextWidth(ctx, node.label, LABEL_FONT)
        val subWidth = if (node.sublabel != null) measureTextWidth(ctx, node.sublabel, SUB_FONT) else 0.0
        val textWidth = max(labelWidth, subWidth)
        val width = textWidth + NODE_PADDING_H * 2
        val height = if (node.sublabel != null) LABEL_HEIGHT + SUB_HEIGHT + NODE_PADDING_V * 2 + 4.0 else 36.0
        nodeSizes[id] = width to height
    }

    val nodesWithEdges = buildSet {
        for (e in graph.edges) { add(e.fromId); add(e.toId) }
    }
    val isolatedNodes = graph.nodes.values.filter { it.id !in nodesWithEdges }
    val connectedNodeIds = graph.nodes.keys.filter { it in nodesWithEdges }.toSet()

    val positions = mutableMapOf<String, NodeRect>()
    var isolatedRowHeight = 0.0

    if (isolatedNodes.isNotEmpty()) {
        val rows = wrapLayerRows(isolatedNodes, nodeSizes)
        var currentY = 0.0
        for (row in rows) {
            val rowWidth = row.sumOf { nodeSizes[it.id]?.first ?: 0.0 } + (row.size - 1) * HORIZONTAL_GAP
            val rowHeight = row.maxOf { nodeSizes[it.id]?.second ?: 36.0 }
            var x = 0.0
            for (node in row) {
                val (w, h) = nodeSizes[node.id] ?: (100.0 to 36.0)
                val yOffset = (rowHeight - h) / 2.0
                positions[node.id] = NodeRect(x, currentY + yOffset, w, h)
                x += w + HORIZONTAL_GAP
            }
            currentY += rowHeight + WRAP_ROW_GAP
        }
        // Center isolated rows
        val isoEntries = positions.entries.toList()
        val isoRowGroups = isoEntries.groupBy { (_, r) -> r.y }
        for ((_, entries) in isoRowGroups) {
            val minX = entries.minOf { it.value.x }
            val maxX = entries.maxOf { it.value.x + it.value.width }
            val rowWidth = maxX - minX
            val shift = -rowWidth / 2.0 - minX
            for ((id, rect) in entries) {
                positions[id] = NodeRect(rect.x + shift, rect.y, rect.width, rect.height)
            }
        }
        isolatedRowHeight = currentY + VERTICAL_GAP - WRAP_ROW_GAP
    }

    val connectedGraph = DependencyGraph(graph.nodes.filterKeys { it in connectedNodeIds }, graph.edges)
    val components = findConnectedComponents(connectedGraph)

    var componentOffsetX = 0.0
    var totalHeight = isolatedRowHeight

    for (componentNodeIds in components) {
        val compResult = layoutComponent(graph, componentNodeIds, nodeSizes)
        for ((id, rect) in compResult.first) {
            positions[id] = NodeRect(rect.x + componentOffsetX, rect.y + isolatedRowHeight, rect.width, rect.height)
        }
        componentOffsetX += compResult.second + COMPONENT_GAP
        totalHeight = max(totalHeight, compResult.third + isolatedRowHeight)
    }

    val totalWidth = (componentOffsetX - COMPONENT_GAP).coerceAtLeast(0.0)
    val shiftX = -totalWidth / 2.0
    val centeredPositions = positions.mapValues { (_, r) ->
        if (r.y < isolatedRowHeight) r
        else NodeRect(r.x + shiftX, r.y, r.width, r.height)
    }

    val layoutEdges = graph.edges.map { LayoutEdge(it.fromId, it.toId, it.label) }
    return GraphLayout(centeredPositions, layoutEdges, totalWidth, totalHeight)
}

private fun layoutComponent(
    graph: DependencyGraph,
    nodeIds: Set<String>,
    nodeSizes: Map<String, Pair<Double, Double>>,
): Triple<Map<String, NodeRect>, Double, Double> {
    val nodesWithOutgoing = graph.edges
        .filter { it.fromId in nodeIds && it.toId in nodeIds && it.fromId != it.toId }
        .map { it.fromId }.toSet()
    val movableLeafKinds = setOf(NodeKind.CORE_INSTANCE, NodeKind.INSTANCE)
    val leafIds = nodeIds.filter { id ->
        id !in nodesWithOutgoing && graph.nodes[id]?.kind in movableLeafKinds
    }.toSet()

    val layerKinds = listOf(
        setOf(NodeKind.IMPORT),
        setOf(NodeKind.MODULE, NodeKind.NESTED_COMPONENT),
        setOf(NodeKind.CORE_INSTANCE),
        setOf(NodeKind.INSTANCE),
        setOf(NodeKind.EXPORT),
    )
    val mainLayers = layerKinds.map { kinds ->
        graph.nodes.values.filter { it.id in nodeIds && it.id !in leafIds && it.kind in kinds }
    }.filter { it.isNotEmpty() }
    val leafLayer = graph.nodes.values.filter { it.id in leafIds }
    val layers = if (leafLayer.isNotEmpty()) mainLayers + listOf(leafLayer) else mainLayers
    val reorderedLayers = reorderLayersToReduceCrossings(layers, graph, nodeIds)

    val positions = mutableMapOf<String, NodeRect>()
    var currentY = 0.0
    var maxWidth = 0.0

    for (layer in reorderedLayers) {
        val rows = wrapLayerRows(layer, nodeSizes)
        for (row in rows) {
            val rowWidth = row.sumOf { nodeSizes[it.id]?.first ?: 0.0 } + (row.size - 1) * HORIZONTAL_GAP
            maxWidth = max(maxWidth, rowWidth)
            val rowHeight = row.maxOf { nodeSizes[it.id]?.second ?: 36.0 }
            var x = 0.0
            for (node in row) {
                val (w, h) = nodeSizes[node.id] ?: (100.0 to 36.0)
                val yOffset = (rowHeight - h) / 2.0
                positions[node.id] = NodeRect(x, currentY + yOffset, w, h)
                x += w + HORIZONTAL_GAP
            }
            currentY += rowHeight + WRAP_ROW_GAP
        }
        currentY += VERTICAL_GAP - WRAP_ROW_GAP
    }

    val rowGroups = positions.entries.groupBy { entry -> entry.value.y }.toList().sortedBy { it.first }.toMap()
    for ((_, entries) in rowGroups) {
        val minX = entries.minOf { it.value.x }
        val maxX = entries.maxOf { it.value.x + it.value.width }
        val rowWidth = maxX - minX
        val shift = (maxWidth - rowWidth) / 2.0 - minX
        for (entry in entries) {
            val r = entry.value
            positions[entry.key] = NodeRect(r.x + shift, r.y, r.width, r.height)
        }
    }

    return Triple(positions, maxWidth, currentY)
}

private fun reorderLayersToReduceCrossings(
    layers: List<List<GraphNode>>,
    graph: DependencyGraph,
    nodeIds: Set<String>,
): List<List<GraphNode>> {
    if (layers.size <= 1) return layers
    val relevantEdges = graph.edges.filter { it.fromId in nodeIds && it.toId in nodeIds && it.fromId != it.toId }
    val nodeToLayer = mutableMapOf<String, Int>()
    for ((li, layer) in layers.withIndex()) {
        for (node in layer) nodeToLayer[node.id] = li
    }
    val result = layers.map { it.toMutableList() }
    fun positionOf(nodeId: String, layerNodes: List<GraphNode>): Int {
        return layerNodes.indexOfFirst { it.id == nodeId }
    }
    for (li in 1 until result.size) {
        val prevPositions = result[li - 1].mapIndexed { idx, n -> n.id to idx }.toMap()
        val barycenters = result[li].associateWith { node ->
            val neighbors = relevantEdges
                .filter { (it.fromId == node.id && it.toId in prevPositions) || (it.toId == node.id && it.fromId in prevPositions) }
                .map { if (it.fromId == node.id) prevPositions[it.toId]!! else prevPositions[it.fromId]!! }
            if (neighbors.isEmpty()) positionOf(node.id, result[li]).toDouble()
            else neighbors.average()
        }
        result[li].sortBy { barycenters[it] }
    }
    for (li in (result.size - 2) downTo 0) {
        val nextPositions = result[li + 1].mapIndexed { idx, n -> n.id to idx }.toMap()
        val barycenters = result[li].associateWith { node ->
            val neighbors = relevantEdges
                .filter { (it.fromId == node.id && it.toId in nextPositions) || (it.toId == node.id && it.fromId in nextPositions) }
                .map { if (it.fromId == node.id) nextPositions[it.toId]!! else nextPositions[it.fromId]!! }
            if (neighbors.isEmpty()) positionOf(node.id, result[li]).toDouble()
            else neighbors.average()
        }
        result[li].sortBy { barycenters[it] }
    }
    return result
}

private fun wrapLayerRows(
    layer: List<GraphNode>,
    nodeSizes: Map<String, Pair<Double, Double>>,
): List<List<GraphNode>> {
    val rows = mutableListOf<MutableList<GraphNode>>()
    var currentRow = mutableListOf<GraphNode>()
    var currentWidth = 0.0

    for (node in layer) {
        val w = nodeSizes[node.id]?.first ?: 100.0
        val needed = if (currentRow.isEmpty()) w else w + HORIZONTAL_GAP
        if (currentRow.isNotEmpty() && currentWidth + needed > MAX_ROW_WIDTH) {
            rows.add(currentRow)
            currentRow = mutableListOf()
            currentWidth = 0.0
        }
        currentRow.add(node)
        currentWidth += needed
    }
    if (currentRow.isNotEmpty()) rows.add(currentRow)
    return rows
}

// --- Colors ---

private object NodeColors {
    fun color(kind: NodeKind, isDark: Boolean): String = when (kind) {
        NodeKind.IMPORT -> if (isDark) "#5BA3CF" else "#2470B3"
        NodeKind.MODULE -> if (isDark) "#8CB369" else "#4A7A2E"
        NodeKind.NESTED_COMPONENT -> if (isDark) "#A882DD" else "#7B4FBF"
        NodeKind.CORE_INSTANCE -> if (isDark) "#D4A054" else "#B07D2A"
        NodeKind.INSTANCE -> if (isDark) "#E07A96" else "#C24466"
        NodeKind.EXPORT -> if (isDark) "#3CC0B8" else "#1A8F87"
    }

    fun legendItems(isDark: Boolean): List<Pair<String, String>> = listOf(
        color(NodeKind.IMPORT, isDark) to "Import",
        color(NodeKind.MODULE, isDark) to "Core Module",
        color(NodeKind.NESTED_COMPONENT, isDark) to "Nested Component",
        color(NodeKind.CORE_INSTANCE, isDark) to "Core Instance",
        color(NodeKind.INSTANCE, isDark) to "Component Instance",
        color(NodeKind.EXPORT, isDark) to "Export",
    )
}

// --- Drawing ---

private fun computeCollapseButtons(
    rawGraph: DependencyGraph,
    expandedGroups: Set<String>,
    layout: GraphLayout,
): Map<String, NodeRect> {
    if (expandedGroups.isEmpty()) return emptyMap()
    val fullyCollapsed = groupGraph(rawGraph, emptySet())
    val buttons = mutableMapOf<String, NodeRect>()
    for (groupId in expandedGroups) {
        val groupNode = fullyCollapsed.nodes[groupId] ?: continue
        val memberIds = groupNode.groupMembers ?: continue
        val memberRects = memberIds.mapNotNull { layout.nodePositions[it] }
        if (memberRects.isEmpty()) continue
        val minX = memberRects.minOf { it.x }
        val minY = memberRects.minOf { it.y }
        val btnSize = 24.0
        buttons[groupId] = NodeRect(minX - btnSize - 8.0, minY, btnSize, btnSize)
    }
    return buttons
}

private fun drawCollapseButton(
    ctx: CanvasRenderingContext2D,
    x: Double,
    y: Double,
    size: Double,
    isDark: Boolean,
) {
    val borderColor = if (isDark) "#aaaaaa" else "#888888"
    val bgColor = if (isDark) "rgba(30,30,30,0.8)" else "rgba(240,240,240,0.8)"
    val iconColor = if (isDark) "#cccccc" else "#333333"

    // Background
    ctx.fillStyle = bgColor
    roundRect(ctx, x, y, size, size, 4.0)
    ctx.fill()
    // Border
    ctx.strokeStyle = borderColor
    ctx.lineWidth = 1.5
    roundRect(ctx, x, y, size, size, 4.0)
    ctx.stroke()
    // Minus icon
    val pad = 6.0
    val centerY = y + size / 2.0
    ctx.beginPath()
    ctx.moveTo(x + pad, centerY)
    ctx.lineTo(x + size - pad, centerY)
    ctx.strokeStyle = iconColor
    ctx.lineWidth = 2.0
    ctx.stroke()
}

private fun drawGraph(
    ctx: CanvasRenderingContext2D,
    canvasWidth: Double,
    canvasHeight: Double,
    graph: DependencyGraph,
    layout: GraphLayout,
    panX: Double,
    panY: Double,
    scale: Double,
    hoveredNodeId: String?,
    isDark: Boolean,
    collapseButtons: Map<String, NodeRect> = emptyMap(),
) {
    ctx.clearRect(0.0, 0.0, canvasWidth, canvasHeight)

    val bg = if (isDark) "#1e1e1e" else "#ffffff"
    ctx.fillStyle = bg
    ctx.fillRect(0.0, 0.0, canvasWidth, canvasHeight)

    ctx.save()
    val originX = canvasWidth / 2.0 + panX
    val originY = 40.0 + panY
    ctx.translate(originX, originY)
    ctx.scale(scale, scale)

    val nodesWithOutgoing = layout.edges.map { it.fromId }.toSet()

    // Detect bidirectional edge pairs (A→B and B→A both exist)
    val edgePairs = layout.edges.map { it.fromId to it.toId }.toSet()
    val bidirectionalPairs = mutableSetOf<Pair<String, String>>()
    for (pair in edgePairs) {
        val reverse = pair.second to pair.first
        if (reverse in edgePairs) {
            val canonical = if (pair.first < pair.second) pair else reverse
            bidirectionalPairs.add(canonical)
        }
    }
    val skippedReverseEdges = mutableSetOf<Pair<String, String>>()

    // Build a lookup for reverse edge labels (for bidirectional edges)
    val edgeLabelLookup = mutableMapOf<Pair<String, String>, String?>()
    for (edge in layout.edges) {
        if (edge.label != null) {
            edgeLabelLookup[edge.fromId to edge.toId] = edge.label
        }
    }

    // Pre-compute the best highlight kind for each deduplicated label,
    // so the label reflects all edges it represents (not just the first).
    val labelHighlightOverrides = mutableMapOf<Pair<String, String>, HighlightKind>()
    for (edge in layout.edges) {
        if (edge.label != null) {
            val from = layout.nodePositions[edge.fromId] ?: continue
            val to = layout.nodePositions[edge.toId] ?: continue
            val canonical = if (edge.fromId < edge.toId) edge.fromId to edge.toId else edge.toId to edge.fromId
            val isBidi = canonical in bidirectionalPairs
            val hl = if (isBidi) {
                if (hoveredNodeId == edge.fromId || hoveredNodeId == edge.toId) HighlightKind.BIDIRECTIONAL else HighlightKind.NONE
            } else highlightKindFor(hoveredNodeId, edge.fromId, edge.toId)
            val key = edge.fromId to edge.label
            val current = labelHighlightOverrides[key]
            if (current == null || (current == HighlightKind.NONE && hl != HighlightKind.NONE)) {
                labelHighlightOverrides[key] = hl
            }
        }
    }

    // Draw edges (deduplicate labels: show each label only once per source node)
    val drawnLabels = mutableSetOf<Pair<String, String>>() // (fromId, label)
    for (edge in layout.edges) {
        val from = layout.nodePositions[edge.fromId] ?: continue
        val to = layout.nodePositions[edge.toId] ?: continue
        val canonical = if (edge.fromId < edge.toId) edge.fromId to edge.toId else edge.toId to edge.fromId
        val isBidi = canonical in bidirectionalPairs
        if (isBidi) {
            val edgeKey = edge.fromId to edge.toId
            val reverseKey = edge.toId to edge.fromId
            if (reverseKey in skippedReverseEdges) continue
            skippedReverseEdges.add(edgeKey)
        }
        val highlightKind = if (isBidi) {
            if (hoveredNodeId == edge.fromId || hoveredNodeId == edge.toId) HighlightKind.BIDIRECTIONAL else HighlightKind.NONE
        } else highlightKindFor(hoveredNodeId, edge.fromId, edge.toId)
        val effectiveLabel = if (edge.label != null) {
            val key = edge.fromId to edge.label
            if (drawnLabels.add(key)) edge.label else null
        } else null
        val labelHl = if (effectiveLabel != null) {
            labelHighlightOverrides[edge.fromId to edge.label] ?: highlightKind
        } else highlightKind
        val reverseLabel = if (isBidi) edgeLabelLookup[edge.toId to edge.fromId] else null
        drawEdge(ctx, from, to, effectiveLabel, highlightKind, isDark, from.width, isBidi, reverseLabel, labelHl)
    }

    // Draw nodes
    for ((id, rect) in layout.nodePositions) {
        val node = graph.nodes[id] ?: continue
        val isHovered = hoveredNodeId == id
        val isLeaf = id !in nodesWithOutgoing
        drawNode(ctx, rect, node, isHovered, isLeaf, isDark)
    }

    // Draw collapse buttons for expanded groups
    for ((_, btnRect) in collapseButtons) {
        drawCollapseButton(ctx, btnRect.x, btnRect.y, btnRect.width, isDark)
    }

    ctx.restore()
}

private fun drawNode(
    ctx: CanvasRenderingContext2D,
    rect: NodeRect,
    node: GraphNode,
    isHovered: Boolean,
    isLeaf: Boolean,
    isDark: Boolean,
) {
    val color = NodeColors.color(node.kind, isDark)
    val isGroup = node.groupMembers != null
    val bgAlpha = if (isHovered) 0.35 else if (isGroup) 0.22 else 0.15

    // Stacked-card effect for group nodes
    if (isGroup) {
        val off = 4.0
        ctx.globalAlpha = 0.10
        ctx.fillStyle = color
        roundRect(ctx, rect.x + off, rect.y + off, rect.width, rect.height, 8.0)
        ctx.fill()
        ctx.globalAlpha = 0.5
        ctx.strokeStyle = color
        ctx.lineWidth = 1.0
        roundRect(ctx, rect.x + off, rect.y + off, rect.width, rect.height, 8.0)
        ctx.stroke()
        ctx.globalAlpha = 1.0
    }

    // Background
    ctx.globalAlpha = bgAlpha
    ctx.fillStyle = color
    roundRect(ctx, rect.x, rect.y, rect.width, rect.height, 8.0)
    ctx.fill()
    ctx.globalAlpha = 1.0

    // Border
    ctx.strokeStyle = color
    ctx.lineWidth = if (isHovered) 3.0 else 2.0
    roundRect(ctx, rect.x, rect.y, rect.width, rect.height, 8.0)
    ctx.stroke()

    // Double border for leaf nodes
    if (isLeaf) {
        val inset = 4.0
        ctx.globalAlpha = 0.7
        ctx.strokeStyle = color
        ctx.lineWidth = 1.5
        roundRect(ctx, rect.x + inset, rect.y + inset, rect.width - inset * 2, rect.height - inset * 2, 5.0)
        ctx.stroke()
        ctx.globalAlpha = 1.0
    }

    // Label
    ctx.fillStyle = color
    ctx.font = LABEL_FONT
    ctx.textAlign = "center".asDynamic().unsafeCast<CanvasTextAlign>()
    ctx.textBaseline = "top".asDynamic().unsafeCast<CanvasTextBaseline>()
    val labelY = if (node.sublabel != null) rect.y + NODE_PADDING_V else rect.y + (rect.height - LABEL_HEIGHT) / 2.0
    ctx.fillText(node.label, rect.x + rect.width / 2.0, labelY)

    // Sublabel
    if (node.sublabel != null) {
        ctx.globalAlpha = 0.7
        ctx.font = SUB_FONT
        ctx.fillText(node.sublabel, rect.x + rect.width / 2.0, rect.y + rect.height - SUB_HEIGHT - NODE_PADDING_V)
        ctx.globalAlpha = 1.0
    }
}

private fun drawEdge(
    ctx: CanvasRenderingContext2D,
    from: NodeRect,
    to: NodeRect,
    label: String?,
    highlightKind: HighlightKind,
    isDark: Boolean,
    sourceNodeWidth: Double,
    isBidirectional: Boolean = false,
    reverseLabel: String? = null,
    labelHighlightKind: HighlightKind = highlightKind,
) {
    val edgeKind = when {
        abs(from.y - to.y) < 1.0 -> EdgeKind.SAME_LAYER
        from.y + from.height > to.y + to.height -> EdgeKind.BACK_EDGE
        else -> EdgeKind.NORMAL
    }

    val startX = from.x + from.width / 2.0
    val startY = if (edgeKind == EdgeKind.BACK_EDGE) from.y else from.y + from.height
    val endX = to.x + to.width / 2.0
    val endY = if (edgeKind == EdgeKind.NORMAL) to.y else to.y + to.height

    val isHighlighted = highlightKind != HighlightKind.NONE
    val edgeColor = when (highlightKind) {
        HighlightKind.OUTGOING -> if (isDark) "#6CB4EE" else "#2B7ACC"
        HighlightKind.INCOMING -> if (isDark) "#7BC67E" else "#2E8B32"
        HighlightKind.BIDIRECTIONAL -> if (isDark) "#8FB8C9" else "#5A8FA3"
        HighlightKind.NONE -> if (isDark) "#666666" else "#999999"
    }
    val strokeWidth = if (isHighlighted) 2.5 else 1.5
    val arrowSize = 10.0
    val arrowLength = arrowSize * 1.5

    when (edgeKind) {
        EdgeKind.SAME_LAYER -> {
            val dist = abs(endX - startX)
            val arcDrop = (dist * 0.4).coerceIn(30.0, 120.0)
            val arcY = startY + arcDrop
            ctx.beginPath()
            ctx.moveTo(startX, if (isBidirectional) startY + arrowLength else startY)
            ctx.bezierCurveTo(startX, arcY, endX, arcY, endX, endY + arrowLength)
            ctx.strokeStyle = edgeColor
            ctx.lineWidth = strokeWidth
            ctx.stroke()

            // Arrowhead pointing up at target
            ctx.beginPath()
            ctx.moveTo(endX, endY)
            ctx.lineTo(endX - arrowSize, endY + arrowLength)
            ctx.lineTo(endX + arrowSize, endY + arrowLength)
            ctx.closePath()
            ctx.fillStyle = edgeColor
            ctx.fill()
            // Arrowhead at source for bidirectional edges
            if (isBidirectional) {
                ctx.beginPath()
                ctx.moveTo(startX, startY)
                ctx.lineTo(startX - arrowSize, startY + arrowLength)
                ctx.lineTo(startX + arrowSize, startY + arrowLength)
                ctx.closePath()
                ctx.fillStyle = edgeColor
                ctx.fill()
            }
        }
        EdgeKind.BACK_EDGE -> {
            val dist = abs(endX - startX).coerceAtLeast(60.0)
            val loopOffset = dist * 0.3 + 40.0
            val sideDir = if (startX <= endX) -1.0 else 1.0
            val sideX = min(startX, endX) + sideDir * loopOffset
            ctx.beginPath()
            ctx.moveTo(startX, if (isBidirectional) startY - arrowLength else startY)
            ctx.bezierCurveTo(sideX, startY, sideX, endY, endX, endY + arrowLength)
            ctx.strokeStyle = edgeColor
            ctx.lineWidth = strokeWidth
            ctx.stroke()

            // Arrowhead pointing up at target
            ctx.beginPath()
            ctx.moveTo(endX, endY)
            ctx.lineTo(endX - arrowSize, endY + arrowLength)
            ctx.lineTo(endX + arrowSize, endY + arrowLength)
            ctx.closePath()
            ctx.fillStyle = edgeColor
            ctx.fill()
            // Arrowhead at source for bidirectional edges
            if (isBidirectional) {
                ctx.beginPath()
                ctx.moveTo(startX, startY)
                ctx.lineTo(startX - arrowSize, startY - arrowLength)
                ctx.lineTo(startX + arrowSize, startY - arrowLength)
                ctx.closePath()
                ctx.fillStyle = edgeColor
                ctx.fill()
            }
        }
        EdgeKind.NORMAL -> {
            ctx.beginPath()
            ctx.moveTo(startX, if (isBidirectional) startY + arrowLength else startY)
            val midY = (startY + endY) / 2.0
            ctx.bezierCurveTo(startX, midY, endX, midY, endX, endY - arrowLength)
            ctx.strokeStyle = edgeColor
            ctx.lineWidth = strokeWidth
            ctx.stroke()

            // Arrowhead pointing down at target
            ctx.beginPath()
            ctx.moveTo(endX, endY)
            ctx.lineTo(endX - arrowSize, endY - arrowLength)
            ctx.lineTo(endX + arrowSize, endY - arrowLength)
            ctx.closePath()
            ctx.fillStyle = edgeColor
            ctx.fill()
            // Arrowhead at source for bidirectional edges
            if (isBidirectional) {
                ctx.beginPath()
                ctx.moveTo(startX, startY)
                ctx.lineTo(startX - arrowSize, startY + arrowLength)
                ctx.lineTo(startX + arrowSize, startY + arrowLength)
                ctx.closePath()
                ctx.fillStyle = edgeColor
                ctx.fill()
            }
        }
    }

    // Edge label
    val labelFont = "10px monospace"
    val labelColor = when (labelHighlightKind) {
        HighlightKind.OUTGOING -> if (isDark) "#8ECAF0" else "#1A5FA0"
        HighlightKind.INCOMING -> if (isDark) "#9AD89D" else "#1E7522"
        HighlightKind.BIDIRECTIONAL -> if (isDark) "#A8D0E0" else "#4A7A8C"
        HighlightKind.NONE -> if (isDark) "#999999" else "#555555"
    }
    val bgColor = if (isDark) "rgba(26,26,26,0.8)" else "rgba(240,240,240,0.8)"
    if (label != null) {
        ctx.font = labelFont
        val labelY = startY + 14.0
        val textWidth = ctx.measureText(label).width

        ctx.fillStyle = bgColor
        roundRect(ctx, startX - textWidth / 2.0 - 4.0, labelY - 2.0, textWidth + 8.0, 14.0, 3.0)
        ctx.fill()

        ctx.fillStyle = labelColor
        ctx.textAlign = "center".asDynamic().unsafeCast<CanvasTextAlign>()
        ctx.textBaseline = "top".asDynamic().unsafeCast<CanvasTextBaseline>()
        ctx.fillText(label, startX, labelY)
    }
    // Reverse label for bidirectional edges (drawn near the target end)
    if (reverseLabel != null && isBidirectional) {
        ctx.font = labelFont
        val revLabelY = endY + 14.0
        val revTextWidth = ctx.measureText(reverseLabel).width

        ctx.fillStyle = bgColor
        roundRect(ctx, endX - revTextWidth / 2.0 - 4.0, revLabelY - 2.0, revTextWidth + 8.0, 14.0, 3.0)
        ctx.fill()

        ctx.fillStyle = labelColor
        ctx.textAlign = "center".asDynamic().unsafeCast<CanvasTextAlign>()
        ctx.textBaseline = "top".asDynamic().unsafeCast<CanvasTextBaseline>()
        ctx.fillText(reverseLabel, endX, revLabelY)
    }
}

private fun roundRect(ctx: CanvasRenderingContext2D, x: Double, y: Double, w: Double, h: Double, r: Double) {
    ctx.beginPath()
    ctx.moveTo(x + r, y)
    ctx.lineTo(x + w - r, y)
    ctx.quadraticCurveTo(x + w, y, x + w, y + r)
    ctx.lineTo(x + w, y + h - r)
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h)
    ctx.lineTo(x + r, y + h)
    ctx.quadraticCurveTo(x, y + h, x, y + h - r)
    ctx.lineTo(x, y + r)
    ctx.quadraticCurveTo(x, y, x + r, y)
    ctx.closePath()
}

// --- Composable ---

@Composable
fun DependencyGraphTabContent(provider: WasmComponentBinaryProvider) {
    DependencyGraphTabContent(remember(provider) { buildDependencyGraph(provider) })
}

@Composable
fun DependencyGraphTabContent(
    nodes: List<ComponentDependencyGraphNodeData>,
    edges: List<ComponentDependencyGraphEdgeData>,
) {
    val rawGraph = remember(nodes, edges) {
        DependencyGraph(
            nodes.associate { node ->
                node.id to GraphNode(node.id, checkNotNull(node.kind.toGraphNodeKind()), node.label, node.sublabel)
            },
            edges.map { GraphEdge(it.fromId, it.toId, it.label) },
        )
    }
    DependencyGraphTabContent(rawGraph)
}

private fun String.toGraphNodeKind(): NodeKind? = when (this) {
    "import" -> NodeKind.IMPORT
    "module" -> NodeKind.MODULE
    "nested_component" -> NodeKind.NESTED_COMPONENT
    "core_instance" -> NodeKind.CORE_INSTANCE
    "instance" -> NodeKind.INSTANCE
    "export" -> NodeKind.EXPORT
    else -> null
}

@Composable
private fun DependencyGraphTabContent(rawGraph: DependencyGraph) {

    if (rawGraph.nodes.isEmpty()) {
        Div({ style { property("padding", "16px") } }) {
            Text("No dependency graph available for this component.")
        }
        return
    }

    // Detect dark theme from VS Code CSS variables
    val isDark = remember {
        val body = document.body
        val bg = window.getComputedStyle(body!!).getPropertyValue("--vscode-editor-background")
        // Heuristic: if background is dark, we're in dark mode
        bg.isBlank() || bg.contains("1e1e1e", ignoreCase = true) || bg.contains("252526", ignoreCase = true) ||
            run {
                // Parse rgb and check luminance
                val match = Regex("""rgb\((\d+),\s*(\d+),\s*(\d+)\)""").find(bg)
                if (match != null) {
                    val (r, g, b) = match.destructured
                    (r.toInt() * 299 + g.toInt() * 587 + b.toInt() * 114) / 1000 < 128
                } else true
            }
    }

    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    val graph = remember(rawGraph, expandedGroups) { groupGraph(rawGraph, expandedGroups) }

    // Legend state
    var legendExpanded by remember { mutableStateOf(false) }

    Div({
        style {
            width(100.percent)
            height(100.percent)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("position", "relative")
        }
    }) {
        // Canvas container — keyed on graph identity so the canvas is recreated
        // when expanded groups change (which produces a new graph object).
        key(graph) {
            Div({
                style {
                    property("flex", "1")
                    property("position", "relative")
                    property("overflow", "hidden")
                }
                ref { element ->
                    setupCanvas(element, graph, rawGraph, isDark, expandedGroups,
                        onExpandedGroupsChange = { expandedGroups = it })
                    onDispose { }
                }
            })
        }

        // Legend overlay
        Div({
            style {
                property("position", "absolute")
                property("top", "8px")
                property("right", "8px")
                property("background", if (isDark) "rgba(30,30,30,0.8)" else "rgba(245,245,245,0.8)")
                property("padding", "8px")
                property("cursor", "pointer")
                property("font-size", "12px")
                property("z-index", "10")
                property("border-radius", "4px")
                property("user-select", "none")
            }
            onClick { legendExpanded = !legendExpanded }
        }) {
            val mutedColor = if (isDark) "#AAAAAA" else "#6E7177"
            Span({ style { property("color", mutedColor) } }) {
                Text(if (legendExpanded) "▼ " else "▶ ")
            }
            Text("Legend")
            if (legendExpanded) {
                Div({ style { property("margin-top", "4px") } }) {
                    for ((color, label) in NodeColors.legendItems(isDark)) {
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                property("padding", "2px 0")
                            }
                        }) {
                            Div({
                                style {
                                    width(12.px)
                                    height(12.px)
                                    property("background", color)
                                    property("margin-right", "6px")
                                    property("flex-shrink", "0")
                                }
                            })
                            Text(label)
                        }
                    }
                    Div({
                        style {
                            property("margin-top", "8px")
                            property("color", mutedColor)
                        }
                    }) {
                        Text("Click group to expand/collapse")
                    }
                    Div({ style { property("color", mutedColor) } }) {
                        Text("Scroll to zoom, drag to pan")
                    }
                }
            }
        }

        // Zoom controls overlay
        Div({
            style {
                property("position", "absolute")
                property("bottom", "12px")
                property("right", "12px")
                property("background", if (isDark) "rgba(30,30,30,0.8)" else "rgba(245,245,245,0.8)")
                property("padding", "4px")
                property("z-index", "10")
                property("border-radius", "4px")
                property("display", "flex")
                property("flex-direction", "column")
                property("align-items", "center")
                property("user-select", "none")
            }
        }) {
            val controlText = if (isDark) "#CCCCCC" else "#333333"
            Div({
                style {
                    width(28.px); height(28.px)
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.Center)
                    property("cursor", "pointer")
                    property("color", controlText)
                    property("font-size", "16px")
                }
                attr("data-zoom", "in")
            }) { Text("+") }
            Div({
                style {
                    property("font-size", "9px")
                    property("color", if (isDark) "#888888" else "#999999")
                }
                attr("data-zoom-label", "true")
            }) { Text("100%") }
            Div({
                style {
                    width(28.px); height(28.px)
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.Center)
                    property("cursor", "pointer")
                    property("color", controlText)
                    property("font-size", "16px")
                }
                attr("data-zoom", "out")
            }) { Text("−") }
            Div({
                style {
                    width(28.px); height(28.px)
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.Center)
                    property("cursor", "pointer")
                    property("color", controlText)
                    property("font-size", "14px")
                }
                attr("data-zoom", "reset")
            }) { Text("⌂") }
        }
    }
}

private fun setupCanvas(
    container: HTMLElement,
    graph: DependencyGraph,
    rawGraph: DependencyGraph,
    isDark: Boolean,
    expandedGroups: Set<String>,
    onExpandedGroupsChange: (Set<String>) -> Unit,
) {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.style.width = "100%"
    canvas.style.height = "100%"
    canvas.style.display = "block"
    container.appendChild(canvas)

    var panX = 0.0
    var panY = 0.0
    var scale = 0.7
    var hoveredNodeId: String? = null
    var isDragging = false
    var lastMouseX = 0.0
    var lastMouseY = 0.0
    var dragStartX = 0.0
    var dragStartY = 0.0

    fun resize() {
        val dpr = window.devicePixelRatio
        val w = container.clientWidth.toDouble()
        val h = container.clientHeight.toDouble()
        canvas.width = (w * dpr).toInt()
        canvas.height = (h * dpr).toInt()
        canvas.style.width = "${w}px"
        canvas.style.height = "${h}px"
    }

    resize()

    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    val dpr = window.devicePixelRatio
    ctx.scale(dpr, dpr)

    val layout = layoutGraph(graph, ctx)
    val collapseButtons = computeCollapseButtons(rawGraph, expandedGroups, layout)

    fun findCollapseButtonAt(gx: Double, gy: Double): String? {
        return collapseButtons.entries.firstOrNull { (_, rect) ->
            gx >= rect.x && gx <= rect.x + rect.width &&
                gy >= rect.y && gy <= rect.y + rect.height
        }?.key
    }

    fun updateZoomLabel() {
        val label = container.parentElement?.querySelector("[data-zoom-label]") as? HTMLElement
        label?.textContent = "${(scale * 100).toInt()}%"
    }

    fun redraw() {
        val w = container.clientWidth.toDouble()
        val h = container.clientHeight.toDouble()
        ctx.save()
        ctx.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
        drawGraph(ctx, w, h, graph, layout, panX, panY, scale, hoveredNodeId, isDark, collapseButtons)
        ctx.restore()
    }

    fun toGraphCoords(clientX: Double, clientY: Double): Pair<Double, Double> {
        val rect = canvas.getBoundingClientRect()
        val canvasX = clientX - rect.left
        val canvasY = clientY - rect.top
        val w = container.clientWidth.toDouble()
        val originX = w / 2.0 + panX
        val originY = 40.0 + panY
        val graphX = (canvasX - originX) / scale
        val graphY = (canvasY - originY) / scale
        return graphX to graphY
    }

    fun findNodeAt(gx: Double, gy: Double): String? {
        return layout.nodePositions.entries.firstOrNull { (_, rect) ->
            gx >= rect.x && gx <= rect.x + rect.width &&
                gy >= rect.y && gy <= rect.y + rect.height
        }?.key
    }

    canvas.addEventListener("mousedown", { e ->
        val me = e as MouseEvent
        isDragging = true
        lastMouseX = me.clientX.toDouble()
        lastMouseY = me.clientY.toDouble()
        dragStartX = lastMouseX
        dragStartY = lastMouseY
    })

    canvas.addEventListener("mousemove", { e ->
        val me = e as MouseEvent
        if (isDragging) {
            panX += me.clientX.toDouble() - lastMouseX
            panY += me.clientY.toDouble() - lastMouseY
            lastMouseX = me.clientX.toDouble()
            lastMouseY = me.clientY.toDouble()
            redraw()
        } else {
            val (gx, gy) = toGraphCoords(me.clientX.toDouble(), me.clientY.toDouble())
            val newHovered = findNodeAt(gx, gy)
            val overButton = findCollapseButtonAt(gx, gy) != null
            if (newHovered != hoveredNodeId || overButton) {
                hoveredNodeId = newHovered
                canvas.style.cursor = if (newHovered != null || overButton) "pointer" else "default"
                redraw()
            }
        }
    })

    canvas.addEventListener("mouseup", { e ->
        val me = e as MouseEvent
        if (isDragging) {
            isDragging = false
            // Check for click (minimal drag)
            val dx = abs(me.clientX.toDouble() - dragStartX)
            val dy = abs(me.clientY.toDouble() - dragStartY)
            if (dx < 3 && dy < 3) {
                val (gx, gy) = toGraphCoords(me.clientX.toDouble(), me.clientY.toDouble())
                // Check collapse buttons first
                val clickedButton = findCollapseButtonAt(gx, gy)
                if (clickedButton != null) {
                    onExpandedGroupsChange(expandedGroups - clickedButton)
                    return@addEventListener
                }
                val clickedId = findNodeAt(gx, gy)
                if (clickedId != null) {
                    val node = graph.nodes[clickedId]
                    if (node?.groupMembers != null) {
                        val newExpanded = if (clickedId in expandedGroups) {
                            expandedGroups - clickedId
                        } else {
                            expandedGroups + clickedId
                        }
                        onExpandedGroupsChange(newExpanded)
                    }
                }
            }
        }
    })

    canvas.addEventListener("mouseleave", {
        isDragging = false
        if (hoveredNodeId != null) {
            hoveredNodeId = null
            redraw()
        }
    })

    canvas.addEventListener("wheel", { e ->
        val we = e as WheelEvent
        we.preventDefault()
        val zoomFactor = if (we.deltaY > 0) 0.9 else 1.1
        scale = (scale * zoomFactor).coerceIn(0.2, 3.0)
        updateZoomLabel()
        redraw()
    }, js("({passive: false})"))

    // Zoom button handlers
    container.parentElement?.querySelector("[data-zoom='in']")?.addEventListener("click", {
        scale = (scale * 1.2).coerceIn(0.2, 3.0)
        updateZoomLabel()
        redraw()
    })
    container.parentElement?.querySelector("[data-zoom='out']")?.addEventListener("click", {
        scale = (scale / 1.2).coerceIn(0.2, 3.0)
        updateZoomLabel()
        redraw()
    })
    container.parentElement?.querySelector("[data-zoom='reset']")?.addEventListener("click", {
        scale = 0.7
        panX = 0.0
        panY = 0.0
        updateZoomLabel()
        redraw()
    })

    // Handle resize — observe the container, not the canvas, because the canvas
    // has percentage-based CSS sizing and won't fire resize entries on its own.
    val resizeObserver = js("new ResizeObserver(function(entries) { entries[0] && entries[0].target.__resizeCallback && entries[0].target.__resizeCallback(); })")
    container.asDynamic().__resizeCallback = {
        resize()
        val newDpr = window.devicePixelRatio
        ctx.setTransform(newDpr, 0.0, 0.0, newDpr, 0.0, 0.0)
        redraw()
    }
    resizeObserver.observe(container)

    redraw()
}
