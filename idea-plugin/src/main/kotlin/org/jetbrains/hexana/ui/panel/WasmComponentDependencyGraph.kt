package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * A visual dependency graph for WebAssembly component models.
 *
 * Shows how imports, core modules, nested components, core instances,
 * component instances, and exports are wired together via instantiation
 * arguments — similar to the graph in wasmbuilder.app.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WasmComponentDependencyGraph(provider: WasmComponentBinaryProvider) {
    val rawGraph = remember(provider) { buildDependencyGraph(provider) }

    if (rawGraph.nodes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("No dependency graph available for this component.")
        }
        return
    }

    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    val graph = remember(rawGraph, expandedGroups) { groupGraph(rawGraph, expandedGroups) }

    val textMeasurer = rememberTextMeasurer()
    val layout = remember(graph, textMeasurer) { layoutGraph(graph, textMeasurer) }

    // Compute collapse button positions for expanded groups
    val collapseButtons = remember(rawGraph, expandedGroups, layout) {
        if (expandedGroups.isEmpty()) emptyMap()
        else {
            // Build the fully-collapsed graph to discover group→member mappings
            val fullyCollapsed = groupGraph(rawGraph, emptySet())
            val buttons = mutableMapOf<String, NodeRect>() // groupId → button rect
            for (groupId in expandedGroups) {
                val groupNode = fullyCollapsed.nodes[groupId] ?: continue
                val memberIds = groupNode.groupMembers ?: continue
                // Find bounding box of member nodes in the current layout
                val memberRects = memberIds.mapNotNull { layout.nodePositions[it] }
                if (memberRects.isEmpty()) continue
                val minX = memberRects.minOf { it.x }
                val minY = memberRects.minOf { it.y }
                // Place button to the left of the top-left member
                val btnSize = 24f
                buttons[groupId] = NodeRect(minX - btnSize - 8f, minY, btnSize, btnSize)
            }
            buttons
        }
    }

    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var hoveredNodeId by remember { mutableStateOf<String?>(null) }
    var legendExpanded by remember { mutableStateOf(false) }
    val isDark = JewelTheme.isDark

    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        panOffset += dragAmount
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    val zoomFactor = if (scrollDelta > 0) 0.9f else 1.1f
                    scale = (scale * zoomFactor).coerceIn(0.2f, 3f)
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    val canvasCenter = Offset(size.width / 2f, 40f)
                    val origin = canvasCenter + panOffset
                    // Transform pointer position back to graph coordinates
                    val graphX = (pos.x - origin.x) / scale
                    val graphY = (pos.y - origin.y) / scale
                    hoveredNodeId = layout.nodePositions.entries.firstOrNull { (_, rect) ->
                        graphX >= rect.x && graphX <= rect.x + rect.width &&
                            graphY >= rect.y && graphY <= rect.y + rect.height
                    }?.key
                }
                .onPointerEvent(PointerEventType.Exit) { hoveredNodeId = null }
                .onPointerEvent(PointerEventType.Press) {
                    val pos = it.changes.firstOrNull()?.position ?: return@onPointerEvent
                    val canvasCenter = Offset(size.width / 2f, 40f)
                    val origin = canvasCenter + panOffset
                    val graphX = (pos.x - origin.x) / scale
                    val graphY = (pos.y - origin.y) / scale

                    // Check collapse buttons first
                    val clickedButton = collapseButtons.entries.firstOrNull { (_, rect) ->
                        graphX >= rect.x && graphX <= rect.x + rect.width &&
                            graphY >= rect.y && graphY <= rect.y + rect.height
                    }?.key
                    if (clickedButton != null) {
                        expandedGroups = expandedGroups - clickedButton
                        return@onPointerEvent
                    }

                    val clickedId = layout.nodePositions.entries.firstOrNull { (_, rect) ->
                        graphX >= rect.x && graphX <= rect.x + rect.width &&
                            graphY >= rect.y && graphY <= rect.y + rect.height
                    }?.key
                    if (clickedId != null) {
                        val node = graph.nodes[clickedId]
                        if (node?.groupMembers != null) {
                            // Toggle expand/collapse
                            expandedGroups = if (clickedId in expandedGroups) {
                                expandedGroups - clickedId
                            } else {
                                expandedGroups + clickedId
                            }
                        }
                    }
                }
        ) {
            val canvasCenter = Offset(size.width / 2f, 40f)
            val origin = canvasCenter + panOffset

            withTransform({
                translate(origin.x, origin.y)
                scale(scale, scale, Offset.Zero)
            }) {
                // Pre-compute edge label positions to avoid overlaps
                data class EdgeDrawInfo(
                    val startX: Float, val startY: Float,
                    val endX: Float, val endY: Float,
                    val label: String?, val isHighlighted: Boolean,
                    val sourceNodeWidth: Float,
                    var labelT: Float = 0.65f,
                    val edgeKind: EdgeKind = EdgeKind.NORMAL,
                )
                val edgeInfos = layout.edges.mapNotNull { edge ->
                    val from = layout.nodePositions[edge.fromId] ?: return@mapNotNull null
                    val to = layout.nodePositions[edge.toId] ?: return@mapNotNull null
                    val sameLayer = kotlin.math.abs(from.y - to.y) < 1f
                    val isBackEdge = !sameLayer && from.y + from.height > to.y + to.height
                    val kind = when {
                        sameLayer -> EdgeKind.SAME_LAYER
                        isBackEdge -> EdgeKind.BACK_EDGE
                        else -> EdgeKind.NORMAL
                    }
                    when (kind) {
                        EdgeKind.SAME_LAYER -> EdgeDrawInfo(
                            from.x + from.width / 2f, from.y + from.height,
                            to.x + to.width / 2f, to.y + to.height,
                            edge.label,
                            hoveredNodeId == edge.fromId || hoveredNodeId == edge.toId,
                            from.width,
                            edgeKind = EdgeKind.SAME_LAYER,
                        )
                        EdgeKind.BACK_EDGE -> EdgeDrawInfo(
                            from.x + from.width / 2f, from.y,
                            to.x + to.width / 2f, to.y + to.height,
                            edge.label,
                            hoveredNodeId == edge.fromId || hoveredNodeId == edge.toId,
                            from.width,
                            edgeKind = EdgeKind.BACK_EDGE,
                        )
                        EdgeKind.NORMAL -> EdgeDrawInfo(
                            from.x + from.width / 2f, from.y + from.height,
                            to.x + to.width / 2f, to.y,
                            edge.label,
                            hoveredNodeId == edge.fromId || hoveredNodeId == edge.toId,
                            from.width,
                        )
                    }
                }
                // Resolve label overlaps: group labeled edges by source endpoint,
                // then spread their t values so labels don't stack on top of each other.
                val labeledBySource = edgeInfos.filter { it.label != null }
                    .groupBy { Pair(it.startX, it.startY) }
                for ((_, group) in labeledBySource) {
                    if (group.size <= 1) continue
                    // Spread t values evenly between 0.35 and 0.85
                    val step = 0.5f / (group.size - 1).coerceAtLeast(1)
                    group.forEachIndexed { idx, info ->
                        info.labelT = 0.35f + step * idx
                    }
                }

                // Deduplicate edge labels: if multiple edges from the same source
                // share the same label text, show it only on the first occurrence.
                val drawnLabels = mutableSetOf<Pair<Pair<Float, Float>, String>>()

                // Draw edges
                for (info in edgeInfos) {
                    val effectiveLabel = if (info.label != null) {
                        val key = Pair(info.startX, info.startY) to info.label
                        if (drawnLabels.add(key)) info.label else null
                    } else null
                    drawEdge(info.startX, info.startY, info.endX, info.endY,
                        effectiveLabel, textMeasurer, info.isHighlighted, info.labelT, isDark, info.sourceNodeWidth, info.edgeKind)
                }

                // Compute leaf nodes (no outgoing edges)
                val nodesWithOutgoing = layout.edges.map { it.fromId }.toSet()

                // Draw nodes
                for ((id, rect) in layout.nodePositions) {
                    val node = graph.nodes[id] ?: continue
                    val isHovered = hoveredNodeId == id
                    val isLeaf = id !in nodesWithOutgoing
                    drawNode(rect.x, rect.y, rect.width, rect.height, node, textMeasurer, isHovered, isLeaf, isDark)
                }

                // Draw collapse buttons for expanded groups
                for ((_, btnRect) in collapseButtons) {
                    drawCollapseButton(btnRect.x, btnRect.y, btnRect.width, isDark)
                }
            }
        }

        // Zoom controls
        val controlBg = if (isDark) Color(0xCC1E1E1E.toInt()) else Color(0xCCF5F5F5.toInt())
        val controlBorder = if (isDark) Color(0xFF555555.toInt()) else Color(0xFFBBBBBB.toInt())
        val controlText = if (isDark) Color(0xFFCCCCCC.toInt()) else Color(0xFF333333.toInt())
        val controlMuted = if (isDark) Color(0xFF888888.toInt()) else Color(0xFF999999.toInt())
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .background(controlBg)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(28.dp).clickable {
                    scale = (scale * 1.2f).coerceIn(0.2f, 3f)
                },
                contentAlignment = Alignment.Center,
            ) { Text("+", color = controlText) }
            Spacer(modifier = Modifier.height(2.dp))
            Text("${(scale * 100).toInt()}%", color = controlMuted, fontSize = 9.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier.size(28.dp).clickable {
                    scale = (scale / 1.2f).coerceIn(0.2f, 3f)
                },
                contentAlignment = Alignment.Center,
            ) { Text("−", color = controlText) }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier.size(28.dp).clickable {
                    scale = 1f
                    panOffset = Offset.Zero
                },
                contentAlignment = Alignment.Center,
            ) { Text("⌂", color = controlText, fontSize = 14.sp) }
        }

        // Legend
        val legendBg = if (isDark) Color(0xCC1E1E1E.toInt()) else Color(0xCCF5F5F5.toInt())
        val legendMutedColor = if (isDark) Color(0xFFAAAAAA.toInt()) else Color(0xFF6E7177.toInt())
        val leafBorderColor = if (isDark) Color.White else Color.Black
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(legendBg)
                .clickable { legendExpanded = !legendExpanded }
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (legendExpanded) "▼" else "▶", color = legendMutedColor)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Legend")
            }
            if (legendExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                LegendItem(if (isDark) NodeColors.IMPORT else NodeColors.IMPORT_LIGHT, "Import")
                LegendItem(if (isDark) NodeColors.MODULE else NodeColors.MODULE_LIGHT, "Core Module")
                LegendItem(if (isDark) NodeColors.COMPONENT else NodeColors.COMPONENT_LIGHT, "Nested Component")
                LegendItem(if (isDark) NodeColors.CORE_INSTANCE else NodeColors.CORE_INSTANCE_LIGHT, "Core Instance")
                LegendItem(if (isDark) NodeColors.INSTANCE else NodeColors.INSTANCE_LIGHT, "Component Instance")
                LegendItem(if (isDark) NodeColors.EXPORT else NodeColors.EXPORT_LIGHT, "Export")
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(modifier = Modifier.size(12.dp).border(2.dp, leafBorderColor).padding(2.dp).border(1.dp, leafBorderColor))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Leaf node (terminal)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Click group to expand/collapse", color = legendMutedColor)
                Text("Scroll to zoom, drag to pan", color = legendMutedColor)
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label)
    }
}

// --- Graph model ---

private enum class NodeKind { IMPORT, MODULE, NESTED_COMPONENT, CORE_INSTANCE, INSTANCE, EXPORT }

private data class GraphNode(
    val id: String,
    val kind: NodeKind,
    val label: String,
    val sublabel: String? = null,
    /** Non-null for group nodes: the IDs of the member nodes this group replaces. */
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

/** Minimum number of nodes sharing a prefix/property before they are grouped. */
private const val GROUP_THRESHOLD = 3

/**
 * Collapses repetitive nodes into group nodes.
 *
 * Two grouping strategies:
 * 1. **Prefix grouping** (imports & exports): nodes whose labels share a
 *    common namespace prefix (e.g. `wasi:cli/…`) are merged.
 * 2. **Same-source grouping** (core instances): instances that all
 *    instantiate the same core module are merged.
 */
private fun groupGraph(
    graph: DependencyGraph,
    expandedGroups: Set<String>,
): DependencyGraph {
    val nodes = graph.nodes.toMutableMap()
    val edges = graph.edges.toMutableList()

    fun collapseGroup(
        groupId: String,
        kind: NodeKind,
        memberIds: List<String>,
        label: String,
        sublabel: String,
    ) {
        if (expandedGroups.contains(groupId)) return // user expanded this group
        val groupNode = GraphNode(groupId, kind, label, sublabel, groupMembers = memberIds)
        nodes[groupId] = groupNode
        val memberSet = memberIds.toSet()
        // Rewrite edges: replace any reference to a member with the group id
        val newEdges = edges.map { edge ->
            val from = if (edge.fromId in memberSet) groupId else edge.fromId
            val to = if (edge.toId in memberSet) groupId else edge.toId
            edge.copy(fromId = from, toId = to)
        }
        // Remove self-loops and deduplicate (keep first label per from→to pair)
        val seen = mutableSetOf<Pair<String, String>>()
        edges.clear()
        for (e in newEdges) {
            if (e.fromId == e.toId) continue
            val key = e.fromId to e.toId
            if (seen.add(key)) edges.add(e)
        }
        // Remove member nodes
        for (mid in memberIds) nodes.remove(mid)
    }

    // --- 1. Prefix grouping for imports (fine then coarse) ---
    groupByPrefix(nodes, NodeKind.IMPORT) { groupId, prefix, memberIds ->
        collapseGroup(groupId, NodeKind.IMPORT, memberIds, prefix, "${memberIds.size} imports")
    }
    // Second pass: coarser namespace grouping (e.g. all remaining wasi:… into one group)
    groupByCoarsePrefix(nodes, NodeKind.IMPORT) { groupId, prefix, memberIds ->
        collapseGroup(groupId, NodeKind.IMPORT, memberIds, prefix, "${memberIds.size} imports")
    }

    // --- 2. Prefix grouping for exports (fine then coarse) ---
    groupByPrefix(nodes, NodeKind.EXPORT) { groupId, prefix, memberIds ->
        collapseGroup(groupId, NodeKind.EXPORT, memberIds, prefix, "${memberIds.size} exports")
    }
    groupByCoarsePrefix(nodes, NodeKind.EXPORT) { groupId, prefix, memberIds ->
        collapseGroup(groupId, NodeKind.EXPORT, memberIds, prefix, "${memberIds.size} exports")
    }

    // --- 3. Same-module grouping for core instances ---
    val coreInstances = nodes.values.filter { it.kind == NodeKind.CORE_INSTANCE && it.groupMembers == null }
    val byModule = coreInstances.filter { it.sublabel?.startsWith("instantiates") == true }
        .groupBy { it.sublabel!! }
    for ((sub, members) in byModule) {
        if (members.size < GROUP_THRESHOLD) continue
        val memberIds = members.map { it.id }
        val groupId = "group:core-instance:${sub.substringAfterLast(' ')}"
        collapseGroup(
            groupId, NodeKind.CORE_INSTANCE, memberIds,
            "${members.size} core instances", sub,
        )
    }

    // --- 4. Shared-dependency grouping for core instances ---
    // If a node has N incoming edges from distinct core instances, group those sources.
    groupSharedDependencies(nodes, edges, expandedGroups)

    return DependencyGraph(nodes, edges)
}

/**
 * Finds nodes of [kind] whose labels share a namespace prefix (up to and
 * including the first `/`) and calls [onGroup] for each group that meets
 * [GROUP_THRESHOLD].
 */
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

/**
 * Second-pass coarser grouping: groups remaining ungrouped nodes (and
 * first-pass group nodes) by the top-level namespace before the first `:`.
 * E.g. `wasi:cli/…` group + `wasi:io/…` group → single `wasi:…` group.
 */
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
        // Collect all leaf member IDs (flatten nested groups)
        val memberIds = members.flatMap { n ->
            n.groupMembers ?: listOf(n.id)
        }
        val groupId = "group:${kind.name.lowercase()}:$ns:*"
        onGroup(groupId, "$ns:…", memberIds)
    }
}

/**
 * Groups core-instance nodes that are shared dependencies of a common
 * target node. If a node receives [GROUP_THRESHOLD]+ edges from distinct
 * core instances, those source instances are collapsed into a group.
 */
private fun groupSharedDependencies(
    nodes: MutableMap<String, GraphNode>,
    edges: MutableList<GraphEdge>,
    expandedGroups: Set<String>,
) {
    // For each target node, find incoming edges from core-instance sources
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
        // Only group if these sources don't have other outgoing edges (they're "private" deps)
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

private fun buildDependencyGraph(provider: WasmComponentBinaryProvider): DependencyGraph {
    val nodes = mutableMapOf<String, GraphNode>()
    val edges = mutableListOf<GraphEdge>()

    // Imports
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

    // Core modules
    provider.modules.forEachIndexed { i, mod ->
        val id = "module:$i"
        nodes[id] = GraphNode(id, NodeKind.MODULE, "core module $i", "${mod.contentSize} bytes")
    }

    // Nested components
    provider.nestedComponents.forEachIndexed { i, _ ->
        val id = "component:$i"
        val exportCount = provider.nestedComponentExports(i).size
        nodes[id] = GraphNode(id, NodeKind.NESTED_COMPONENT, "component $i", "$exportCount exports")
    }

    // Core instances
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

    // Component instances
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

    // Exports
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

// --- Layout ---

private data class NodeRect(val x: Float, val y: Float, val width: Float, val height: Float)

private data class LayoutEdge(val fromId: String, val toId: String, val label: String?)

private enum class EdgeKind { NORMAL, SAME_LAYER, BACK_EDGE }

private data class GraphLayout(
    val nodePositions: Map<String, NodeRect>,
    val edges: List<LayoutEdge>,
    val totalWidth: Float,
    val totalHeight: Float,
)

private const val NODE_PADDING_H = 20f
private const val NODE_PADDING_V = 12f
private const val HORIZONTAL_GAP = 40f
private const val VERTICAL_GAP = 140f
/** Horizontal gap between disjoint graph components. */
private const val COMPONENT_GAP = 80f
/** Maximum width (in graph coordinates) before a layer row wraps. */
private const val MAX_ROW_WIDTH = 1800f
/** Extra vertical gap between wrapped sub-rows within the same layer. */
private const val WRAP_ROW_GAP = 60f

/**
 * Finds connected components in the graph using union-find on edges.
 * Returns a list of node-ID sets, one per connected component.
 */
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

/**
 * Layered layout that measures each node's text to determine its width,
 * so labels are never truncated. Layers: imports → modules/components →
 * instances → exports.
 *
 * Disjoint graph components are laid out side-by-side. Within each layer,
 * nodes wrap to a new row when the accumulated width exceeds [MAX_ROW_WIDTH].
 */
private fun layoutGraph(graph: DependencyGraph, textMeasurer: TextMeasurer): GraphLayout {
    val labelStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    val subStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    // Measure each node's required size
    val nodeSizes = mutableMapOf<String, Pair<Float, Float>>() // id -> (width, height)
    for ((id, node) in graph.nodes) {
        val labelWidth = textMeasurer.measure(node.label, style = labelStyle).size.width.toFloat()
        val subWidth = if (node.sublabel != null) {
            textMeasurer.measure(node.sublabel, style = subStyle).size.width.toFloat()
        } else 0f
        val textWidth = maxOf(labelWidth, subWidth)
        val width = textWidth + NODE_PADDING_H * 2
        val labelHeight = textMeasurer.measure(node.label, style = labelStyle).size.height.toFloat()
        val subHeight = if (node.sublabel != null) {
            textMeasurer.measure(node.sublabel, style = subStyle).size.height.toFloat()
        } else 0f
        val height = if (node.sublabel != null) labelHeight + subHeight + NODE_PADDING_V * 2 + 4f else 36f
        nodeSizes[id] = width to height
    }

    // Identify isolated nodes (no incoming or outgoing edges at all)
    val nodesWithEdges = buildSet {
        for (e in graph.edges) { add(e.fromId); add(e.toId) }
    }
    val isolatedNodes = graph.nodes.values.filter { it.id !in nodesWithEdges }
    val connectedNodeIds = graph.nodes.keys.filter { it in nodesWithEdges }.toSet()

    val positions = mutableMapOf<String, NodeRect>()
    var isolatedRowHeight = 0f

    // Layout isolated nodes as a first row
    if (isolatedNodes.isNotEmpty()) {
        val rows = wrapLayerRows(isolatedNodes, nodeSizes)
        var currentY = 0f
        var maxRowWidth = 0f
        for (row in rows) {
            val rowWidth = row.sumOf { (nodeSizes[it.id]?.first ?: 0f).toDouble() }.toFloat() +
                (row.size - 1) * HORIZONTAL_GAP
            maxRowWidth = maxOf(maxRowWidth, rowWidth)
            val rowHeight = row.maxOf { nodeSizes[it.id]?.second ?: 36f }
            var x = 0f
            for (node in row) {
                val (w, h) = nodeSizes[node.id] ?: (100f to 36f)
                val yOffset = (rowHeight - h) / 2f
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
            val shift = -rowWidth / 2f - minX
            for ((id, rect) in entries) {
                positions[id] = NodeRect(rect.x + shift, rect.y, rect.width, rect.height)
            }
        }
        isolatedRowHeight = currentY + VERTICAL_GAP - WRAP_ROW_GAP
    }

    // Build a sub-graph containing only connected nodes for component layout
    val connectedGraph = DependencyGraph(
        graph.nodes.filterKeys { it in connectedNodeIds },
        graph.edges,
    )

    // Split into disjoint connected components
    val components = findConnectedComponents(connectedGraph)

    // Layout each component independently, then place them side-by-side
    var componentOffsetX = 0f
    var totalHeight = isolatedRowHeight

    for (componentNodeIds in components) {
        val compResult = layoutComponent(graph, componentNodeIds, nodeSizes)
        // Shift all positions by componentOffsetX and isolatedRowHeight
        for ((id, rect) in compResult.first) {
            positions[id] = NodeRect(rect.x + componentOffsetX, rect.y + isolatedRowHeight, rect.width, rect.height)
        }
        componentOffsetX += compResult.second + COMPONENT_GAP
        totalHeight = maxOf(totalHeight, compResult.third + isolatedRowHeight)
    }

    val totalWidth = (componentOffsetX - COMPONENT_GAP).coerceAtLeast(0f)
    // Center the whole layout so it's symmetric around x=0
    val shiftX = -totalWidth / 2f
    val centeredPositions = positions.mapValues { (_, r) ->
        if (r.y < isolatedRowHeight) r // isolated nodes already centered
        else NodeRect(r.x + shiftX, r.y, r.width, r.height)
    }

    val layoutEdges = graph.edges.map { LayoutEdge(it.fromId, it.toId, it.label) }
    return GraphLayout(centeredPositions, layoutEdges, totalWidth, totalHeight)
}

/**
 * Lays out a single connected component. Returns (positions, width, height).
 */
private fun layoutComponent(
    graph: DependencyGraph,
    nodeIds: Set<String>,
    nodeSizes: Map<String, Pair<Float, Float>>,
): Triple<Map<String, NodeRect>, Float, Float> {
    // Identify leaf nodes (no outgoing edges within this component)
    val nodesWithOutgoing = graph.edges
        .filter { it.fromId in nodeIds && it.toId in nodeIds && it.fromId != it.toId }
        .map { it.fromId }.toSet()
    // Only move CORE_INSTANCE and INSTANCE leaves to a dedicated row;
    // IMPORT and EXPORT leaves stay in their kind-based layer.
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
    // Build main layers excluding movable leaf nodes, then add a dedicated leaf layer
    val mainLayers = layerKinds.map { kinds ->
        graph.nodes.values.filter { it.id in nodeIds && it.id !in leafIds && it.kind in kinds }
    }.filter { it.isNotEmpty() }
    val leafLayer = graph.nodes.values.filter { it.id in leafIds }
    val layers = if (leafLayer.isNotEmpty()) mainLayers + listOf(leafLayer) else mainLayers

    // Reorder nodes within each layer using barycenter heuristic to reduce edge crossings.
    // We do a forward pass (top-down) then a backward pass (bottom-up).
    val reorderedLayers = reorderLayersToReduceCrossings(layers, graph, nodeIds)

    val positions = mutableMapOf<String, NodeRect>()
    var currentY = 0f
    var maxWidth = 0f

    for (layer in reorderedLayers) {
        // Split layer into wrapped sub-rows
        val rows = wrapLayerRows(layer, nodeSizes)
        for (row in rows) {
            val rowWidth = row.sumOf { (nodeSizes[it.id]?.first ?: 0f).toDouble() }.toFloat() +
                (row.size - 1) * HORIZONTAL_GAP
            maxWidth = maxOf(maxWidth, rowWidth)
            val rowHeight = row.maxOf { nodeSizes[it.id]?.second ?: 36f }
            // x will be centered later
            var x = 0f
            for (node in row) {
                val (w, h) = nodeSizes[node.id] ?: (100f to 36f)
                val yOffset = (rowHeight - h) / 2f
                positions[node.id] = NodeRect(x, currentY + yOffset, w, h)
                x += w + HORIZONTAL_GAP
            }
            currentY += rowHeight + WRAP_ROW_GAP
        }
        // Replace the last WRAP_ROW_GAP with the larger VERTICAL_GAP between layers
        currentY += VERTICAL_GAP - WRAP_ROW_GAP
    }

    // Center each row within the component's maxWidth.
    val rowGroups = positions.entries.groupBy { (_, r) -> r.y }
        .toSortedMap()
    for ((_, entries) in rowGroups) {
        val minX = entries.minOf { it.value.x }
        val maxX = entries.maxOf { it.value.x + it.value.width }
        val rowWidth = maxX - minX
        val shift = (maxWidth - rowWidth) / 2f - minX
        for ((id, rect) in entries) {
            positions[id] = NodeRect(rect.x + shift, rect.y, rect.width, rect.height)
        }
    }

    return Triple(positions, maxWidth, currentY)
}

/**
 * Reorders nodes within each layer using the barycenter heuristic to reduce
 * edge crossings. Performs two sweeps: top-down then bottom-up.
 */
private fun reorderLayersToReduceCrossings(
    layers: List<List<GraphNode>>,
    graph: DependencyGraph,
    nodeIds: Set<String>,
): List<List<GraphNode>> {
    if (layers.size <= 1) return layers

    // Build adjacency: for each node, which nodes in other layers is it connected to?
    val relevantEdges = graph.edges.filter { it.fromId in nodeIds && it.toId in nodeIds && it.fromId != it.toId }

    // Map each node to its layer index
    val nodeToLayer = mutableMapOf<String, Int>()
    for ((li, layer) in layers.withIndex()) {
        for (node in layer) nodeToLayer[node.id] = li
    }

    // Start with current order; track position within layer
    val result = layers.map { it.toMutableList() }

    fun positionOf(nodeId: String, layerNodes: List<GraphNode>): Int {
        return layerNodes.indexOfFirst { it.id == nodeId }
    }

    // Forward pass: for each layer (top to bottom), reorder by barycenter of neighbors in previous layers
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

    // Backward pass: for each layer (bottom to top), reorder by barycenter of neighbors in next layers
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

/**
 * Splits a layer's nodes into sub-rows so that no row exceeds [MAX_ROW_WIDTH].
 */
private fun wrapLayerRows(
    layer: List<GraphNode>,
    nodeSizes: Map<String, Pair<Float, Float>>,
): List<List<GraphNode>> {
    val rows = mutableListOf<MutableList<GraphNode>>()
    var currentRow = mutableListOf<GraphNode>()
    var currentWidth = 0f

    for (node in layer) {
        val w = nodeSizes[node.id]?.first ?: 100f
        val needed = if (currentRow.isEmpty()) w else w + HORIZONTAL_GAP
        if (currentRow.isNotEmpty() && currentWidth + needed > MAX_ROW_WIDTH) {
            rows.add(currentRow)
            currentRow = mutableListOf()
            currentWidth = 0f
        }
        currentRow.add(node)
        currentWidth += needed
    }
    if (currentRow.isNotEmpty()) rows.add(currentRow)
    return rows
}

// --- Drawing ---

private object NodeColors {
    // Dark theme colors
    val IMPORT = Color(0xFF5BA3CF.toInt())
    val MODULE = Color(0xFF8CB369.toInt())
    val COMPONENT = Color(0xFFA882DD.toInt())
    val CORE_INSTANCE = Color(0xFFD4A054.toInt())
    val INSTANCE = Color(0xFFE07A96.toInt())
    val EXPORT = Color(0xFF3CC0B8.toInt())
    // Light theme colors (higher contrast / darker)
    val IMPORT_LIGHT = Color(0xFF2470B3.toInt())
    val MODULE_LIGHT = Color(0xFF4A7A2E.toInt())
    val COMPONENT_LIGHT = Color(0xFF7B4FBF.toInt())
    val CORE_INSTANCE_LIGHT = Color(0xFFB07D2A.toInt())
    val INSTANCE_LIGHT = Color(0xFFC24466.toInt())
    val EXPORT_LIGHT = Color(0xFF1A8F87.toInt())
}

private fun nodeColor(kind: NodeKind, isDark: Boolean): Color = when (kind) {
    NodeKind.IMPORT -> if (isDark) NodeColors.IMPORT else NodeColors.IMPORT_LIGHT
    NodeKind.MODULE -> if (isDark) NodeColors.MODULE else NodeColors.MODULE_LIGHT
    NodeKind.NESTED_COMPONENT -> if (isDark) NodeColors.COMPONENT else NodeColors.COMPONENT_LIGHT
    NodeKind.CORE_INSTANCE -> if (isDark) NodeColors.CORE_INSTANCE else NodeColors.CORE_INSTANCE_LIGHT
    NodeKind.INSTANCE -> if (isDark) NodeColors.INSTANCE else NodeColors.INSTANCE_LIGHT
    NodeKind.EXPORT -> if (isDark) NodeColors.EXPORT else NodeColors.EXPORT_LIGHT
}

private fun DrawScope.drawNode(
    x: Float, y: Float, width: Float, height: Float,
    node: GraphNode, textMeasurer: TextMeasurer, isHovered: Boolean,
    isLeaf: Boolean = false, isDark: Boolean = true,
) {
    val color = nodeColor(node.kind, isDark)
    val isGroup = node.groupMembers != null
    val bgAlpha = if (isHovered) 0.35f else if (isGroup) 0.22f else 0.15f
    val borderWidth = if (isHovered) 3f else 2f

    // Background
    drawRoundRect(
        color = color.copy(alpha = bgAlpha),
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = CornerRadius(8f),
    )

    // Border
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = CornerRadius(8f),
        style = Stroke(width = borderWidth),
    )

    // Double border for leaf nodes
    if (isLeaf) {
        val inset = 4f
        drawRoundRect(
            color = color.copy(alpha = 0.7f),
            topLeft = Offset(x + inset, y + inset),
            size = Size(width - inset * 2, height - inset * 2),
            cornerRadius = CornerRadius(5f),
            style = Stroke(width = 1.5f),
        )
    }

    // Stacked-card effect for group nodes
    if (isGroup) {
        val off = 4f
        drawRoundRect(
            color = color.copy(alpha = 0.10f),
            topLeft = Offset(x + off, y + off),
            size = Size(width, height),
            cornerRadius = CornerRadius(8f),
        )
        drawRoundRect(
            color = color.copy(alpha = 0.5f),
            topLeft = Offset(x + off, y + off),
            size = Size(width, height),
            cornerRadius = CornerRadius(8f),
            style = Stroke(width = 1f),
        )
    }

    // Label
    val labelStyle = TextStyle(
        color = color,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )
    val labelResult = textMeasurer.measure(node.label, style = labelStyle)
    drawText(
        labelResult,
        topLeft = Offset(
            x + (width - labelResult.size.width) / 2f,
            y + if (node.sublabel != null) NODE_PADDING_V else (height - labelResult.size.height) / 2f,
        ),
    )

    // Sublabel
    if (node.sublabel != null) {
        val subStyle = TextStyle(
            color = color.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        val subResult = textMeasurer.measure(node.sublabel, style = subStyle)
        drawText(
            subResult,
            topLeft = Offset(
                x + (width - subResult.size.width) / 2f,
                y + height - subResult.size.height - NODE_PADDING_V,
            ),
        )
    }
}


private fun DrawScope.drawEdge(
    startX: Float, startY: Float,
    endX: Float, endY: Float,
    label: String?,
    textMeasurer: TextMeasurer,
    isHighlighted: Boolean,
    labelT: Float = 0.65f,
    isDark: Boolean = true,
    sourceNodeWidth: Float = Float.MAX_VALUE,
    edgeKind: EdgeKind = EdgeKind.NORMAL,
) {
    val edgeColor = if (isDark) {
        if (isHighlighted) Color(0xFFCCCCCC.toInt()) else Color(0xFF666666.toInt())
    } else {
        if (isHighlighted) Color(0xFF333333.toInt()) else Color(0xFF999999.toInt())
    }
    val strokeWidth = if (isHighlighted) 2.5f else 1.5f

    when (edgeKind) {
        EdgeKind.SAME_LAYER -> {
            // Draw as an arc below the nodes
            val dist = kotlin.math.abs(endX - startX)
            val arcDrop = (dist * 0.4f).coerceIn(30f, 120f)
            val midX = (startX + endX) / 2f
            val arcY = startY + arcDrop
            val path = Path().apply {
                moveTo(startX, startY)
                cubicTo(startX, arcY, endX, arcY, endX, endY)
            }
            drawPath(path, edgeColor, style = Stroke(width = strokeWidth))
            // Arrowhead pointing up into the target node bottom
            val arrowSize = 6f
            drawPath(
                Path().apply {
                    moveTo(endX, endY)
                    lineTo(endX - arrowSize, endY + arrowSize * 1.5f)
                    lineTo(endX + arrowSize, endY + arrowSize * 1.5f)
                    close()
                },
                color = edgeColor,
            )
        }
        EdgeKind.BACK_EDGE -> {
            // Draw as a curve going upward with the arc looping to the side
            val dist = kotlin.math.abs(endX - startX).coerceAtLeast(60f)
            val loopOffset = dist * 0.3f + 40f
            val sideDir = if (startX <= endX) -1f else 1f
            val sideX = minOf(startX, endX) + sideDir * loopOffset
            val path = Path().apply {
                moveTo(startX, startY)
                cubicTo(sideX, startY, sideX, endY, endX, endY)
            }
            drawPath(path, edgeColor, style = Stroke(width = strokeWidth))
            // Arrowhead pointing down into the target node bottom
            val arrowSize = 6f
            drawPath(
                Path().apply {
                    moveTo(endX, endY)
                    lineTo(endX - arrowSize, endY + arrowSize * 1.5f)
                    lineTo(endX + arrowSize, endY + arrowSize * 1.5f)
                    close()
                },
                color = edgeColor,
            )
        }
        EdgeKind.NORMAL -> {
            // Standard top-to-bottom bezier
            val path = Path().apply {
                moveTo(startX, startY)
                val midY = (startY + endY) / 2f
                cubicTo(startX, midY, endX, midY, endX, endY)
            }
            drawPath(path, edgeColor, style = Stroke(width = strokeWidth))
            // Arrowhead pointing down
            val arrowSize = 6f
            drawPath(
                Path().apply {
                    moveTo(endX, endY)
                    lineTo(endX - arrowSize, endY - arrowSize * 1.5f)
                    lineTo(endX + arrowSize, endY - arrowSize * 1.5f)
                    close()
                },
                color = edgeColor,
            )
        }
    }

    // Edge label — placed directly under the source node
    if (label != null) {
        val labelStyle = TextStyle(
            color = if (isDark) {
                if (isHighlighted) Color(0xFFDDDDDD.toInt()) else Color(0xFF999999.toInt())
            } else {
                if (isHighlighted) Color(0xFF222222.toInt()) else Color(0xFF555555.toInt())
            },
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        // Wrap label text to fit within source node width
        val maxLabelWidth = (sourceNodeWidth - 8f).coerceAtLeast(40f)
        val singleResult = textMeasurer.measure(label, style = labelStyle)
        val labelY = startY + 14f + (labelT - 0.65f) * 40f

        if (singleResult.size.width > maxLabelWidth) {
            // Split label into lines that fit within maxLabelWidth
            val words = label.split(Regex("(?<=[/:\\-])|(?=[/:\\-])| "))
            val lines = mutableListOf<String>()
            var currentLine = StringBuilder()
            for (word in words) {
                val candidate = if (currentLine.isEmpty()) word else currentLine.toString() + word
                val candidateWidth = textMeasurer.measure(candidate, style = labelStyle).size.width
                if (currentLine.isNotEmpty() && candidateWidth > maxLabelWidth) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                } else {
                    currentLine.append(word)
                }
            }
            if (currentLine.isNotEmpty()) lines.add(currentLine.toString())

            val lineResults = lines.map { textMeasurer.measure(it, style = labelStyle) }
            val totalHeight = lineResults.sumOf { it.size.height }
            val maxWidth = lineResults.maxOf { it.size.width }

            drawRoundRect(
                color = if (isDark) Color(0xCC1A1A1A.toInt()) else Color(0xCCF0F0F0.toInt()),
                topLeft = Offset(startX - maxWidth / 2f - 4f, labelY - 2f),
                size = Size(maxWidth.toFloat() + 8f, totalHeight.toFloat() + 4f),
                cornerRadius = CornerRadius(3f),
            )
            var yOff = labelY
            for (lr in lineResults) {
                drawText(lr, topLeft = Offset(startX - lr.size.width / 2f, yOff))
                yOff += lr.size.height
            }
        } else {
            val labelX = startX - singleResult.size.width / 2f

            drawRoundRect(
                color = if (isDark) Color(0xCC1A1A1A.toInt()) else Color(0xCCF0F0F0.toInt()),
                topLeft = Offset(labelX - 4f, labelY - 2f),
                size = Size(singleResult.size.width + 8f, singleResult.size.height + 4f),
                cornerRadius = CornerRadius(3f),
            )
            drawText(singleResult, topLeft = Offset(labelX, labelY))
        }
    }
}

private fun DrawScope.drawCollapseButton(x: Float, y: Float, size: Float, isDark: Boolean = true) {
    val borderColor = if (isDark) Color(0xFFAAAAAA.toInt()) else Color(0xFF888888.toInt())
    val bgColor = if (isDark) Color(0xCC1E1E1E.toInt()) else Color(0xCCF0F0F0.toInt())
    val iconColor = if (isDark) Color(0xFFCCCCCC.toInt()) else Color(0xFF333333.toInt())

    // Background
    drawRoundRect(
        color = bgColor,
        topLeft = Offset(x, y),
        size = Size(size, size),
        cornerRadius = CornerRadius(4f),
    )
    // Border
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(x, y),
        size = Size(size, size),
        cornerRadius = CornerRadius(4f),
        style = Stroke(width = 1.5f),
    )
    // Minus icon
    val pad = 6f
    val centerY = y + size / 2f
    drawLine(
        color = iconColor,
        start = Offset(x + pad, centerY),
        end = Offset(x + size - pad, centerY),
        strokeWidth = 2f,
    )
}
