package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed as keyIsCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed as keyIsMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.typeDisplayName
import org.jetbrains.hexana.wasm.analysis.typegraph.TypeGraphIndex
import org.jetbrains.hexana.wasm.analysis.typegraph.TypeGraphNodeKind
import org.jetbrains.hexana.wasm.analysis.typegraph.computeTypeNeighborhood
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private const val DOUBLE_CLICK_INTERVAL_MS = 400L
// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private const val NODE_PADDING_H = 20f
// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private const val NODE_PADDING_V = 12f
// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private const val HORIZONTAL_GAP = 40f
// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private const val VERTICAL_GAP = 140f
// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private const val COMPONENT_GAP = 80f
// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private const val MAX_ROW_WIDTH = 1800f
// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private const val WRAP_ROW_GAP = 60f

/** Inflate amount around a rec-group bounding box to draw the cluster frame. */
private const val REC_GROUP_FRAME_INFLATE = 12f

/** Y of the graph origin below the canvas top; drawing, hit-testing, and scroll math share it. */
private const val TG_ORIGIN_TOP = 40f

/** Screen-pixel breathing room kept scrollable around the graph content. */
private const val TG_SCROLL_MARGIN = 40f

/** Pan pixels per wheel-line unit. */
private const val TG_WHEEL_PAN_STEP = 40f

/** Per-event pan cap so precise trackpad deltas on the same channel cannot over-pan. */
private const val TG_WHEEL_PAN_MAX = 120f

private fun wheelPanAmount(delta: Float): Float =
    (-delta * TG_WHEEL_PAN_STEP).coerceIn(-TG_WHEEL_PAN_MAX, TG_WHEEL_PAN_MAX)

// --- Filter mode ---

internal enum class TypeGraphFilter { HIERARCHY, FIELD_REFS, BOTH }

// --- Graph model ---

internal data class TypeGraphNode(
    val id: Int,         // typeIdx
    val kind: TypeGraphNodeKind,
    val label: String,   // typeDisplayName result
    val sublabel: String, // "struct, N fields" / "array" / "func"
    val layer: Int,
    val isFocus: Boolean,
)

internal enum class TypeEdgeKind { SUBTYPE, FIELD_REF }

internal data class TypeGraphEdge(
    val from: Int,
    val to: Int,
    val kind: TypeEdgeKind,
)

/** Graph model used by the canvas layout. All keys are typeIdx. */
internal data class TypeGraph(
    val nodes: Map<Int, TypeGraphNode>,
    val edges: List<TypeGraphEdge>,
)

// --- Layout types (copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears) ---

internal data class TgNodeRect(val x: Float, val y: Float, val width: Float, val height: Float)

private data class TgLayoutEdge(val from: Int, val to: Int, val kind: TypeEdgeKind)

// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private enum class TgEdgeKind { NORMAL, SAME_LAYER, BACK_EDGE }

// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private enum class TgHighlightKind { NONE, OUTGOING, INCOMING }

private data class TgLayout(
    val nodePositions: Map<Int, TgNodeRect>,
    val edges: List<TgLayoutEdge>,
    val recGroupFrames: List<RecGroupFrame>,
    val totalWidth: Float,
    val totalHeight: Float,
    val nodeLabelResults: Map<Int, TextLayoutResult>,
    val nodeSubResults: Map<Int, TextLayoutResult>,
    val recCaptionResults: Map<Int, TextLayoutResult>,
)

private data class RecGroupFrame(
    val groupIndex: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val memberIds: List<Int>,
    val honest: Boolean,
)

// --- Pure logic helpers (internal so tests can reach them) ---

/**
 * Assigns layer = subtype hierarchy depth (BFS from superTypes).
 * Nodes with no supertype get depth 0. FUNC nodes without a supertype also get depth 0.
 * Returns a map from typeIdx to depth.
 *
 * Exposed internal for unit tests.
 */
internal fun assignTypeLayers(included: IntArray, index: TypeGraphIndex): Map<Int, Int> {
    val includedSet = included.toHashSet()
    val depth = mutableMapOf<Int, Int>()

    // Seed: nodes whose supertypes are absent or not in the included set get depth 0.
    for (t in included) {
        val supers = index.superTypes(t).filter { it in includedSet }
        if (supers.isEmpty()) depth[t] = 0
    }

    // BFS downward through subtype edges.
    val queue = ArrayDeque<Int>()
    for ((t, d) in depth) if (d == 0) queue.add(t)

    while (queue.isNotEmpty()) {
        val t = queue.removeFirst()
        val d = depth[t] ?: continue
        for (sub in index.directSubtypes(t)) {
            if (sub !in includedSet) continue
            val existing = depth[sub]
            val candidate = d + 1
            if (existing == null || candidate > existing) {
                depth[sub] = candidate
                queue.add(sub)
            }
        }
    }

    // Any remaining nodes not reachable via edges get depth 0.
    for (t in included) {
        if (t !in depth) depth[t] = 0
    }
    return depth
}

/**
 * Computes the union of the neighborhood set with expansion additions.
 * expandedTypes contains typeIdx values the user has explicitly expanded;
 * their direct neighbors (subtypes + supertypes + fieldRefs + referencingTypes
 * subject to filter) are added to the base included set.
 *
 * Exposed internal for unit tests.
 */
internal fun unionWithExpansions(
    base: IntArray,
    expandedTypes: Set<Int>,
    index: TypeGraphIndex,
    filter: TypeGraphFilter,
): Set<Int> {
    val result = base.toHashSet()
    for (t in expandedTypes) {
        if (t !in result) continue
        if (filter != TypeGraphFilter.FIELD_REFS) {
            result.addAll(index.superTypes(t).asIterable())
            result.addAll(index.directSubtypes(t).asIterable())
        }
        if (filter != TypeGraphFilter.HIERARCHY) {
            result.addAll(index.fieldRefs(t).asIterable())
            result.addAll(index.referencingTypes(t).asIterable())
        }
    }
    return result
}

/**
 * Groups rec-group membership for the included set.
 * Returns a map from rec-group index to list of included typeIdx values.
 * Groups with fewer than 2 included members are omitted.
 *
 * Exposed internal for unit tests.
 */
internal fun recGroupClusters(included: Collection<Int>, index: TypeGraphIndex): Map<Int, List<Int>> {
    val byGroup = mutableMapOf<Int, MutableList<Int>>()
    for (t in included) {
        val g = index.recGroup(t)
        if (g >= 0) byGroup.getOrPut(g) { mutableListOf() }.add(t)
    }
    return byGroup.filter { (_, members) -> members.size >= 2 }
}

/**
 * Returns true when [frameBbox] (x, y, width, height) does not contain any node rect
 * whose id is NOT in [memberIds]. O(nodes) with early exit.
 */
internal fun recFrameIsHonest(
    frameX: Float, frameY: Float, frameWidth: Float, frameHeight: Float,
    memberIds: Set<Int>,
    allPositions: Map<Int, TgNodeRect>,
): Boolean {
    for ((id, r) in allPositions) {
        if (id in memberIds) continue
        // Check whether this non-member rect intersects the frame bbox.
        val intersects = r.x < frameX + frameWidth && r.x + r.width > frameX &&
            r.y < frameY + frameHeight && r.y + r.height > frameY
        if (intersects) return false
    }
    return true
}

/**
 * Computes [RecGroupFrame] list from final node positions, without text measurement.
 * The caption [TextLayoutResult] entries must be computed separately (full layout measures
 * them via textMeasurer; incremental layout carries them forward from the previous layout
 * for existing groups or measures new ones).
 */
private fun computeRecGroupFrames(
    recGroupClustersMap: Map<Int, List<Int>>,
    positions: Map<Int, TgNodeRect>,
): List<RecGroupFrame> {
    val result = mutableListOf<RecGroupFrame>()
    for ((groupIndex, members) in recGroupClustersMap) {
        val rects = members.mapNotNull { positions[it] }
        if (rects.size < 2) continue
        val minX = rects.minOf { it.x } - REC_GROUP_FRAME_INFLATE
        val minY = rects.minOf { it.y } - REC_GROUP_FRAME_INFLATE
        val maxX = rects.maxOf { it.x + it.width } + REC_GROUP_FRAME_INFLATE
        val maxY = rects.maxOf { it.y + it.height } + REC_GROUP_FRAME_INFLATE
        val memberSet = members.toSet()
        val honest = recFrameIsHonest(minX, minY, maxX - minX, maxY - minY, memberSet, positions)
        result.add(RecGroupFrame(groupIndex, minX, minY, maxX - minX, maxY - minY, members, honest))
    }
    return result
}

/**
 * Places newly revealed nodes (those in [newNodeSizes] but not in [previousPositions]) below
 * the [anchorRect] in wrapped rows, collision-avoiding against all occupied rects
 * ([previousPositions] filtered to [survivorIds]).
 *
 * Contract:
 * - Returns a map containing ONLY the new nodes' rects (keys are the typeIdx values from
 *   [newNodeSizes]). The caller merges it with the survivor positions.
 * - Any id in [newNodeSizes] that already exists in [previousPositions] is silently ignored:
 *   those nodes were already placed and must not be re-placed.
 * - Survivor positions are NOT modified. Transient overlap on the anchor's own rect update
 *   (fold-badge resize) is handled by the caller, not here.
 * - Termination: the push-down loop jumps rowStartY past the bottom of every conflicting rect
 *   and then repeats. Each iteration resolves at least one blocker, so the loop runs at most
 *   occupied.size + 1 times before all conflicts are cleared.
 *
 * [newNodeSizes] maps typeIdx -> (width, height).
 * [anchorRect] is the anchor node's existing rect (top-left corner in graph coordinates).
 * [maxRowWidth] caps the row width before wrapping.
 * [hGap] is the horizontal gap between nodes in a row.
 * [vGap] is the drop from the anchor bottom to the first row, and the minimum push step.
 * [rowGap] is the gap between successive rows of new nodes (defaults to [vGap]).
 *
 * Exposed internal so WasmTypeGraphPanelTest can call it directly.
 */
internal fun placeNewNodesIncrementally(
    previousPositions: Map<Int, TgNodeRect>,
    survivorIds: Set<Int>,
    newNodeSizes: Map<Int, Pair<Float, Float>>,
    anchorRect: TgNodeRect,
    maxRowWidth: Float,
    hGap: Float,
    vGap: Float,
    rowGap: Float = vGap,
): Map<Int, TgNodeRect> {
    // Ignore ids that are already placed in previousPositions (double-place guard).
    val filteredNewSizes = newNodeSizes.filterKeys { it !in previousPositions }
    if (filteredNewSizes.isEmpty()) return emptyMap()

    // Occupied rects: all survivors (includes anchor itself).
    val occupied = previousPositions.filterKeys { it in survivorIds }.values.toMutableList()

    // Build rows from new nodes ordered by id for determinism.
    val orderedNew = filteredNewSizes.entries.sortedBy { it.key }

    val rows = mutableListOf<List<Map.Entry<Int, Pair<Float, Float>>>>()
    var currentRow = mutableListOf<Map.Entry<Int, Pair<Float, Float>>>()
    var currentRowWidth = 0f
    for (entry in orderedNew) {
        val w = entry.value.first
        val needed = if (currentRow.isEmpty()) w else w + hGap
        if (currentRow.isNotEmpty() && currentRowWidth + needed > maxRowWidth) {
            rows.add(currentRow)
            currentRow = mutableListOf()
            currentRowWidth = 0f
        }
        currentRow.add(entry)
        currentRowWidth += needed
    }
    if (currentRow.isNotEmpty()) rows.add(currentRow)

    val result = mutableMapOf<Int, TgNodeRect>()
    // Start placing below the anchor (vGap = anchor drop).
    var rowStartY = anchorRect.y + anchorRect.height + vGap

    for (row in rows) {
        val rowHeight = row.maxOf { it.value.second }
        val rowTotalWidth = row.sumOf { it.value.first.toDouble() }.toFloat() +
            (row.size - 1) * hGap

        // Push down until the row does not overlap any occupied rect.
        // Each iteration jumps past the bottom of ALL currently conflicting rects,
        // so the loop runs at most occupied.size + 1 times (each jump resolves at
        // least one blocker and the occupied set is finite).
        var iters = 0
        while (iters <= occupied.size + 1) {
            val rowBottom = rowStartY + rowHeight
            val conflictingBottom = occupied
                .filter { occ -> rowStartY < occ.y + occ.height && rowBottom > occ.y }
                .maxOfOrNull { occ -> occ.y + occ.height }
            if (conflictingBottom == null) break
            rowStartY = conflictingBottom + vGap
            iters++
        }

        // Place each node in the row, centered on anchorRect.x.
        var x = anchorRect.x + anchorRect.width / 2f - rowTotalWidth / 2f
        for (entry in row) {
            val (w, h) = entry.value
            val yOffset = (rowHeight - h) / 2f
            val rect = TgNodeRect(x, rowStartY + yOffset, w, h)
            result[entry.key] = rect
            occupied.add(rect)
            x += w + hGap
        }
        rowStartY += rowHeight + rowGap
    }

    return result
}

// --- Graph builder ---

internal fun buildTypeGraph(
    included: Set<Int>,
    focusType: Int,
    index: TypeGraphIndex,
    provider: WasmBinaryProvider,
    filter: TypeGraphFilter,
    layers: Map<Int, Int>,
    foldFuncLeaves: Boolean = false,
    expandedTypes: Set<Int> = emptySet(),
): TypeGraph {
    // B1: Compute foldable FUNC nodes.
    // A FUNC node is foldable when:
    //   - folding is enabled (foldFuncLeaves=true, filter involves field-refs, node is not focus)
    //   - every struct parent that field-refs it is NOT in expandedTypes
    //   - the FUNC is not reachable via any other path (subtype/supertype chain)
    val foldedFuncs: Map<Int, List<Int>> // parentStructId -> list of folded FUNC child ids
    val foldedFuncSet: Set<Int>
    if (foldFuncLeaves && filter != TypeGraphFilter.HIERARCHY) {
        // For each FUNC node in included, determine if it is purely a field-ref leaf.
        // A FUNC is a pure field-ref leaf if:
        //   1. It is not the focus.
        //   2. It has no subtype edges into it from the included set (not a subtype target).
        //   3. It is not a supertype of anything in the included set.
        //   4. All struct parents that field-ref it are non-expanded.
        val funcParents = mutableMapOf<Int, MutableList<Int>>() // funcId -> [structParentIds]
        for (t in included) {
            if (index.kind(t) == TypeGraphNodeKind.FUNC) continue
            for (ref in index.fieldRefs(t)) {
                if (ref in included && index.kind(ref) == TypeGraphNodeKind.FUNC && ref != focusType) {
                    funcParents.getOrPut(ref) { mutableListOf() }.add(t)
                }
            }
        }
        // Check which FUNC nodes are reachable via subtype/supertype edges.
        val funcInHierarchy = mutableSetOf<Int>()
        if (filter != TypeGraphFilter.FIELD_REFS) {
            for (t in included) {
                for (sub in index.directSubtypes(t)) {
                    if (sub in included && index.kind(sub) == TypeGraphNodeKind.FUNC) funcInHierarchy.add(sub)
                }
                // Check if this FUNC is a supertype of something (unlikely but spec says check)
                if (index.kind(t) == TypeGraphNodeKind.FUNC) {
                    for (sup in index.superTypes(t)) {
                        if (sup in included) funcInHierarchy.add(t)
                    }
                }
            }
        }
        val result = mutableMapOf<Int, MutableList<Int>>()
        val foldable = mutableSetOf<Int>()
        for ((funcId, parents) in funcParents) {
            if (funcId in funcInHierarchy) continue // reachable via hierarchy, do not fold
            // All parents must be non-expanded for this FUNC to be foldable
            if (parents.all { it !in expandedTypes }) {
                foldable.add(funcId)
                // Assign to first (or only) parent for badge counting
                for (p in parents) {
                    result.getOrPut(p) { mutableListOf() }.add(funcId)
                }
            }
        }
        foldedFuncs = result
        foldedFuncSet = foldable
    } else {
        foldedFuncs = emptyMap()
        foldedFuncSet = emptySet()
    }

    val nodes = mutableMapOf<Int, TypeGraphNode>()
    for (t in included) {
        if (t in foldedFuncSet) continue // skip folded FUNC nodes
        val kind = index.kind(t)
        val foldedCount = foldedFuncs[t]?.size ?: 0
        val sublabel = when (kind) {
            TypeGraphNodeKind.STRUCT -> {
                val fc = index.fieldRefs(t).size
                val fieldPart = "struct, $fc field${if (fc == 1) "" else "s"}"
                if (foldedCount > 0) "$fieldPart  +$foldedCount func${if (foldedCount == 1) "" else "s"}" else fieldPart
            }
            TypeGraphNodeKind.ARRAY -> "array"
            TypeGraphNodeKind.FUNC -> "func"
        }
        nodes[t] = TypeGraphNode(
            id = t,
            kind = kind,
            label = provider.typeDisplayName(t),
            sublabel = sublabel,
            layer = layers[t] ?: 0,
            isFocus = t == focusType,
        )
    }

    val edges = mutableListOf<TypeGraphEdge>()
    if (filter != TypeGraphFilter.FIELD_REFS) {
        // SUBTYPE edges: from parent to child (parent renders above, child below)
        for (t in included) {
            if (t in foldedFuncSet) continue
            for (sub in index.directSubtypes(t)) {
                if (sub in included && sub !in foldedFuncSet) edges.add(TypeGraphEdge(t, sub, TypeEdgeKind.SUBTYPE))
            }
        }
    }
    if (filter != TypeGraphFilter.HIERARCHY) {
        // FIELD_REF edges: FUNC nodes get no outgoing field edges (leaf fence); skip folded FUNCs
        for (t in included) {
            if (t in foldedFuncSet) continue
            if (index.kind(t) == TypeGraphNodeKind.FUNC) continue
            for (ref in index.fieldRefs(t)) {
                if (ref in included && ref != t && ref !in foldedFuncSet) {
                    edges.add(TypeGraphEdge(t, ref, TypeEdgeKind.FIELD_REF))
                }
            }
        }
    }
    return TypeGraph(nodes, edges)
}

// --- Layout ---

// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private fun tgFindConnectedComponents(nodeIds: Set<Int>, edges: List<TypeGraphEdge>): List<Set<Int>> {
    val parent = nodeIds.associateWith { it }.toMutableMap()
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]!!
        var c = x
        while (c != r) { val n = parent[c]!!; parent[c] = r; c = n }
        return r
    }
    fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
    for (e in edges) { if (e.from in parent && e.to in parent) union(e.from, e.to) }
    return nodeIds.groupBy { find(it) }.values.map { it.toSet() }
}

// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private fun tgWrapLayerRows(
    layer: List<TypeGraphNode>,
    nodeSizes: Map<Int, Pair<Float, Float>>,
): List<List<TypeGraphNode>> {
    val rows = mutableListOf<MutableList<TypeGraphNode>>()
    var currentRow = mutableListOf<TypeGraphNode>()
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

// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private fun tgReorderLayersToReduceCrossings(
    layers: List<List<TypeGraphNode>>,
    edges: List<TypeGraphEdge>,
    nodeIds: Set<Int>,
): List<List<TypeGraphNode>> {
    if (layers.size <= 1) return layers
    val relevantEdges = edges.filter { it.from in nodeIds && it.to in nodeIds && it.from != it.to }
    val result = layers.map { it.toMutableList() }
    fun positionOf(id: Int, layerNodes: List<TypeGraphNode>): Int = layerNodes.indexOfFirst { it.id == id }

    // Forward pass
    for (li in 1 until result.size) {
        val prevPositions = result[li - 1].mapIndexed { idx, n -> n.id to idx }.toMap()
        val barycenters = result[li].associateWith { node ->
            val neighbors = relevantEdges
                .filter { (it.from == node.id && it.to in prevPositions) ||
                    (it.to == node.id && it.from in prevPositions) }
                .map { if (it.from == node.id) prevPositions[it.to]!! else prevPositions[it.from]!! }
            if (neighbors.isEmpty()) positionOf(node.id, result[li]).toDouble() else neighbors.average()
        }
        result[li].sortBy { barycenters[it] }
    }
    // Backward pass
    for (li in (result.size - 2) downTo 0) {
        val nextPositions = result[li + 1].mapIndexed { idx, n -> n.id to idx }.toMap()
        val barycenters = result[li].associateWith { node ->
            val neighbors = relevantEdges
                .filter { (it.from == node.id && it.to in nextPositions) ||
                    (it.to == node.id && it.from in nextPositions) }
                .map { if (it.from == node.id) nextPositions[it.to]!! else nextPositions[it.from]!! }
            if (neighbors.isEmpty()) positionOf(node.id, result[li]).toDouble() else neighbors.average()
        }
        result[li].sortBy { barycenters[it] }
    }
    return result
}

private fun layoutTypeGraph(
    graph: TypeGraph,
    textMeasurer: TextMeasurer,
    recGroupClustersMap: Map<Int, List<Int>>,
): TgLayout {
    val labelStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    val subStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    val nodeLabelResults = mutableMapOf<Int, TextLayoutResult>()
    val nodeSubResults = mutableMapOf<Int, TextLayoutResult>()
    val nodeSizes = mutableMapOf<Int, Pair<Float, Float>>()
    for ((id, node) in graph.nodes) {
        val labelResult = textMeasurer.measure(node.label, style = labelStyle)
        val subResult = textMeasurer.measure(node.sublabel, style = subStyle)
        nodeLabelResults[id] = labelResult
        nodeSubResults[id] = subResult
        val textWidth = maxOf(labelResult.size.width.toFloat(), subResult.size.width.toFloat())
        val width = textWidth + NODE_PADDING_H * 2
        val height = labelResult.size.height.toFloat() + subResult.size.height.toFloat() + NODE_PADDING_V * 2 + 4f
        nodeSizes[id] = width to height
    }

    val positions = mutableMapOf<Int, TgNodeRect>()

    // Layout by connected component, then side-by-side (copied from WasmComponentDependencyGraph.kt)
    val nodesWithEdges = buildSet<Int> { for (e in graph.edges) { add(e.from); add(e.to) } }
    val isolatedNodesList = graph.nodes.values.filter { it.id !in nodesWithEdges }
    val connectedNodeIds = graph.nodes.keys.filter { it in nodesWithEdges }.toSet()

    var isolatedRowHeight = 0f
    if (isolatedNodesList.isNotEmpty()) {
        val rows = tgWrapLayerRows(isolatedNodesList, nodeSizes)
        var currentY = 0f
        for (row in rows) {
            val rowHeight = row.maxOf { nodeSizes[it.id]?.second ?: 36f }
            var x = 0f
            for (node in row) {
                val (w, h) = nodeSizes[node.id] ?: (100f to 36f)
                val yOffset = (rowHeight - h) / 2f
                positions[node.id] = TgNodeRect(x, currentY + yOffset, w, h)
                x += w + HORIZONTAL_GAP
            }
            currentY += rowHeight + WRAP_ROW_GAP
        }
        val isoEntries = positions.entries.toList()
        val isoRowGroups = isoEntries.groupBy { (_, r) -> r.y }
        for ((_, entries) in isoRowGroups) {
            val minX = entries.minOf { it.value.x }
            val maxX = entries.maxOf { it.value.x + it.value.width }
            val rowWidth = maxX - minX
            val shift = -rowWidth / 2f - minX
            for ((id, rect) in entries) positions[id] = TgNodeRect(rect.x + shift, rect.y, rect.width, rect.height)
        }
        isolatedRowHeight = currentY + VERTICAL_GAP - WRAP_ROW_GAP
    }

    val connectedGraph = TypeGraph(graph.nodes.filterKeys { it in connectedNodeIds }, graph.edges)
    val components = tgFindConnectedComponents(connectedNodeIds, graph.edges)
    var componentOffsetX = 0f
    var totalHeight = isolatedRowHeight

    for (componentNodeIds in components) {
        val compResult = layoutTypeComponent(connectedGraph, componentNodeIds, nodeSizes)
        for ((id, rect) in compResult.first) {
            positions[id] = TgNodeRect(rect.x + componentOffsetX, rect.y + isolatedRowHeight, rect.width, rect.height)
        }
        componentOffsetX += compResult.second + COMPONENT_GAP
        totalHeight = maxOf(totalHeight, compResult.third + isolatedRowHeight)
    }

    val totalWidth = (componentOffsetX - COMPONENT_GAP).coerceAtLeast(0f)
    val shiftX = -totalWidth / 2f
    val centeredPositions = positions.mapValues { (_, r) ->
        if (r.y < isolatedRowHeight) r else TgNodeRect(r.x + shiftX, r.y, r.width, r.height)
    }

    // Compute rec-group frames using the shared helper, then measure captions.
    val recCaptionStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    val recCaptionResults = mutableMapOf<Int, TextLayoutResult>()
    val recFrames = computeRecGroupFrames(recGroupClustersMap, centeredPositions)
    for (frame in recFrames) {
        recCaptionResults[frame.groupIndex] = textMeasurer.measure("rec #${frame.groupIndex}", style = recCaptionStyle)
    }

    val layoutEdges = graph.edges.map { TgLayoutEdge(it.from, it.to, it.kind) }
    return TgLayout(centeredPositions, layoutEdges, recFrames, totalWidth, totalHeight,
        nodeLabelResults, nodeSubResults, recCaptionResults)
}

private fun layoutTypeComponent(
    graph: TypeGraph,
    nodeIds: Set<Int>,
    nodeSizes: Map<Int, Pair<Float, Float>>,
): Triple<Map<Int, TgNodeRect>, Float, Float> {
    val depthGroups = graph.nodes.values
        .filter { it.id in nodeIds }
        .groupBy { it.layer }
        .toSortedMap()
    val layers = depthGroups.values.toList()

    val reorderedLayers = tgReorderLayersToReduceCrossings(layers, graph.edges, nodeIds)

    val positions = mutableMapOf<Int, TgNodeRect>()
    var currentY = 0f
    var maxWidth = 0f

    for (layer in reorderedLayers) {
        val rows = tgWrapLayerRows(layer, nodeSizes)
        for (row in rows) {
            val rowWidth = row.sumOf { (nodeSizes[it.id]?.first ?: 0f).toDouble() }.toFloat() +
                (row.size - 1) * HORIZONTAL_GAP
            maxWidth = maxOf(maxWidth, rowWidth)
            val rowHeight = row.maxOf { nodeSizes[it.id]?.second ?: 36f }
            var x = 0f
            for (node in row) {
                val (w, h) = nodeSizes[node.id] ?: (100f to 36f)
                val yOffset = (rowHeight - h) / 2f
                positions[node.id] = TgNodeRect(x, currentY + yOffset, w, h)
                x += w + HORIZONTAL_GAP
            }
            currentY += rowHeight + WRAP_ROW_GAP
        }
        currentY += VERTICAL_GAP - WRAP_ROW_GAP
    }

    // Center each row within the component's maxWidth.
    val rowGroups = positions.entries.groupBy { (_, r) -> r.y }.toSortedMap()
    for ((_, entries) in rowGroups) {
        val minX = entries.minOf { it.value.x }
        val maxX = entries.maxOf { it.value.x + it.value.width }
        val rowWidth = maxX - minX
        val shift = (maxWidth - rowWidth) / 2f - minX
        for ((id, rect) in entries) positions[id] = TgNodeRect(rect.x + shift, rect.y, rect.width, rect.height)
    }
    return Triple(positions, maxWidth, currentY)
}

// --- Drawing ---

private object TypeNodeColors {
    // Dark theme
    val STRUCT = Color(0xFF5BA3CF.toInt())
    val ARRAY = Color(0xFF8CB369.toInt())
    val FUNC = Color(0xFFD4A054.toInt())
    val FOCUS_RING = Color(0xFFFFD700.toInt())
    // Light theme
    val STRUCT_LIGHT = Color(0xFF2470B3.toInt())
    val ARRAY_LIGHT = Color(0xFF4A7A2E.toInt())
    val FUNC_LIGHT = Color(0xFFB07D2A.toInt())
    val FOCUS_RING_LIGHT = Color(0xFFB8860B.toInt())
    // Rec-group frame
    val REC_FRAME_DARK = Color(0x33AAAAAA.toInt())
    val REC_FRAME_LIGHT = Color(0x33555555.toInt())
    val REC_FRAME_BORDER_DARK = Color(0x88888888.toInt())
    val REC_FRAME_BORDER_LIGHT = Color(0x88444444.toInt())
}

private fun typeNodeColor(kind: TypeGraphNodeKind, isDark: Boolean): Color = when (kind) {
    TypeGraphNodeKind.STRUCT -> if (isDark) TypeNodeColors.STRUCT else TypeNodeColors.STRUCT_LIGHT
    TypeGraphNodeKind.ARRAY -> if (isDark) TypeNodeColors.ARRAY else TypeNodeColors.ARRAY_LIGHT
    TypeGraphNodeKind.FUNC -> if (isDark) TypeNodeColors.FUNC else TypeNodeColors.FUNC_LIGHT
}

private fun DrawScope.drawTypeNode(
    x: Float, y: Float, width: Float, height: Float,
    node: TypeGraphNode,
    labelResult: TextLayoutResult,
    subResult: TextLayoutResult,
    isHovered: Boolean,
    isDark: Boolean,
) {
    val color = typeNodeColor(node.kind, isDark)
    val bgAlpha = if (isHovered) 0.35f else if (node.isFocus) 0.28f else 0.15f
    val borderWidth = when {
        node.isFocus -> 3f
        isHovered -> 2.5f
        else -> 2f
    }

    drawRoundRect(
        color = color.copy(alpha = bgAlpha),
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = CornerRadius(8f),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = CornerRadius(8f),
        style = Stroke(width = borderWidth),
    )
    // Extra glow ring for the focus node
    if (node.isFocus) {
        val inset = -4f
        val focusRing = if (isDark) TypeNodeColors.FOCUS_RING else TypeNodeColors.FOCUS_RING_LIGHT
        drawRoundRect(
            color = focusRing.copy(alpha = 0.6f),
            topLeft = Offset(x + inset, y + inset),
            size = Size(width - inset * 2, height - inset * 2),
            cornerRadius = CornerRadius(12f),
            style = Stroke(width = 2f),
        )
    }

    drawText(
        labelResult,
        color = color,
        topLeft = Offset(
            x + (width - labelResult.size.width) / 2f,
            y + NODE_PADDING_V,
        ),
    )
    drawText(
        subResult,
        color = color.copy(alpha = 0.7f),
        topLeft = Offset(
            x + (width - subResult.size.width) / 2f,
            y + height - subResult.size.height - NODE_PADDING_V,
        ),
    )
}

// copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears
private val DASH_FIELD_REF by lazy { PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) }

private fun DrawScope.drawTypeEdge(
    startX: Float, startY: Float,
    endX: Float, endY: Float,
    kind: TypeEdgeKind,
    highlightKind: TgHighlightKind,
    edgeKind: TgEdgeKind,
    isDark: Boolean,
) {
    val isHighlighted = highlightKind != TgHighlightKind.NONE
    val edgeColor = when (highlightKind) {
        TgHighlightKind.OUTGOING -> if (isDark) Color(0xFF6CB4EE.toInt()) else Color(0xFF2B7ACC.toInt())
        TgHighlightKind.INCOMING -> if (isDark) Color(0xFF7BC67E.toInt()) else Color(0xFF2E8B32.toInt())
        TgHighlightKind.NONE -> if (isDark) Color(0xFF666666.toInt()) else Color(0xFF999999.toInt())
    }
    val strokeWidth = if (isHighlighted) 2.5f else 1.5f
    val pathEffect = if (kind == TypeEdgeKind.FIELD_REF) DASH_FIELD_REF else null
    val arrowSize = 10f
    val arrowLength = arrowSize * 1.5f
    val stroke = Stroke(width = strokeWidth, pathEffect = pathEffect)

    when (edgeKind) {
        TgEdgeKind.SAME_LAYER -> {
            val dist = kotlin.math.abs(endX - startX)
            val arcDrop = (dist * 0.4f).coerceIn(30f, 120f)
            val path = Path().apply {
                moveTo(startX, startY)
                cubicTo(startX, startY + arcDrop, endX, endY + arcDrop, endX, endY + arrowLength)
            }
            drawPath(path, edgeColor, style = stroke)
            drawPath(Path().apply {
                moveTo(endX, endY); lineTo(endX - arrowSize, endY + arrowLength); lineTo(endX + arrowSize, endY + arrowLength); close()
            }, color = edgeColor)
        }
        TgEdgeKind.BACK_EDGE -> {
            val dist = kotlin.math.abs(endX - startX).coerceAtLeast(60f)
            val loopOffset = dist * 0.3f + 40f
            val sideDir = if (startX <= endX) -1f else 1f
            val sideX = minOf(startX, endX) + sideDir * loopOffset
            val path = Path().apply {
                moveTo(startX, startY)
                cubicTo(sideX, startY, sideX, endY, endX, endY + arrowLength)
            }
            drawPath(path, edgeColor, style = stroke)
            drawPath(Path().apply {
                moveTo(endX, endY); lineTo(endX - arrowSize, endY + arrowLength); lineTo(endX + arrowSize, endY + arrowLength); close()
            }, color = edgeColor)
        }
        TgEdgeKind.NORMAL -> {
            val midY = (startY + endY) / 2f
            val path = Path().apply {
                moveTo(startX, startY)
                cubicTo(startX, midY, endX, midY, endX, endY - arrowLength)
            }
            drawPath(path, edgeColor, style = stroke)
            drawPath(Path().apply {
                moveTo(endX, endY); lineTo(endX - arrowSize, endY - arrowLength); lineTo(endX + arrowSize, endY - arrowLength); close()
            }, color = edgeColor)
        }
    }
}

// --- Public panel composable ---

/**
 * Focused WasmGC Type Graph panel.
 *
 * [focusTypeFlow] is set by the WAT panel "Show type graph" action; null means nothing is
 * selected yet. When a type is selected this panel computes its neighborhood via
 * [computeTypeNeighborhood] (off EDT), builds a layered graph, and renders it on a
 * pan/zoom canvas. Double-clicking a node invokes [onNavigateToType] to jump back to
 * the WAT view.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WasmTypeGraphPanel(
    provider: WasmBinaryProvider,
    focusTypeFlow: MutableStateFlow<Int?>,
    onNavigateToType: (typeIdx: Int, byteOffset: Long) -> Unit,
    uiProbeSink: ((String, String) -> Unit)? = null,
) {
    val focusType by focusTypeFlow.collectAsState()
    var filter by remember { mutableStateOf(TypeGraphFilter.BOTH) }
    var expandedTypes by remember { mutableStateOf(emptySet<Int>()) }
    var showAllConfirmPending by remember { mutableStateOf(false) }
    // B2: neighborhood depth/hops controls (start at 1, reset with focus).
    var subtypeDepth by remember { mutableStateOf(1) }
    var fieldHops by remember { mutableStateOf(1) }
    // B3: type-info popup state.
    var typeInfoPopupData by remember { mutableStateOf<TypeInfoData?>(null) }
    var typeInfoPopupRequest by remember { mutableStateOf<Int?>(null) }

    // Reset expansion and depth when focus changes.
    LaunchedEffect(focusType) {
        expandedTypes = emptySet()
        showAllConfirmPending = false
        subtypeDepth = 1
        fieldHops = 1
        typeInfoPopupData = null
        typeInfoPopupRequest = null
    }

    // B3: Off-EDT TypeInfoData build triggered by Alt+click on a node.
    LaunchedEffect(typeInfoPopupRequest) {
        val idx = typeInfoPopupRequest ?: return@LaunchedEffect
        typeInfoPopupData = withContext(Dispatchers.IO) {
            buildTypeInfoData(idx, provider)
        }
    }

    // Early-exit states that need no data.
    val focus = focusType
    if (focus == null) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("Open the WAT view and use 'Show type graph' on a type")
        }
        return
    }

    val index = provider.typeGraphIndex
    if (index == null || !index.hasGcTypes) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("No GC types in this module")
        }
        return
    }

    // Off-EDT data computation.
    // Snapshots of all LaunchedEffect keys are captured at dispatch time and stored on
    // GraphData so the layout gate can compare against what actually built this data,
    // not against the live state at render time (which may have advanced further).
    data class GraphData(
        val graph: TypeGraph,
        val included: Set<Int>,
        val recClusters: Map<Int, List<Int>>,
        // Snapshot of the expandedTypes set that produced this GraphData.
        val expandedTypes: Set<Int>,
        // Snapshot of the other keys that produced this GraphData.
        val snapshotFocus: Int,
        val snapshotFilter: TypeGraphFilter,
        val snapshotShowAll: Boolean,
        val snapshotSubtypeDepth: Int,
        val snapshotFieldHops: Int,
    )

    var graphData by remember { mutableStateOf<GraphData?>(null) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(focus, filter, expandedTypes, showAll, subtypeDepth, fieldHops) {
        val startNanos = System.nanoTime()
        // Snapshot all keys before crossing to the IO thread.
        val snapFocus = focus
        val snapFilter = filter
        val snapExpandedTypes = expandedTypes
        val snapShowAll = showAll
        val snapSubtypeDepth = subtypeDepth
        val snapFieldHops = fieldHops
        val data = withContext(Dispatchers.IO) {
            val baseNeighborhood = if (snapShowAll) {
                IntArray(index.typeCount) { it }
            } else {
                computeTypeNeighborhood(
                    index, snapFocus,
                    includeHierarchy = snapFilter != TypeGraphFilter.FIELD_REFS,
                    includeFieldRefs = snapFilter != TypeGraphFilter.HIERARCHY,
                    subtypeDepth = snapSubtypeDepth,
                    fieldHops = snapFieldHops,
                ).included
            }
            val includedSet = unionWithExpansions(baseNeighborhood, snapExpandedTypes, index, snapFilter)
            val includedArr = includedSet.toIntArray().also { it.sort() }
            val layers = assignTypeLayers(includedArr, index)
            val clusters = recGroupClusters(includedSet, index)
            // B1: fold FUNC leaves only in neighborhood mode (not show-all)
            val graph = buildTypeGraph(
                includedSet, snapFocus, index, provider, snapFilter, layers,
                foldFuncLeaves = !snapShowAll,
                expandedTypes = snapExpandedTypes,
            )
            GraphData(graph, includedSet, clusters, snapExpandedTypes, snapFocus, snapFilter, snapShowAll, snapSubtypeDepth, snapFieldHops)
        }
        graphData = data
        val buildMillis = (System.nanoTime() - startNanos) / 1_000_000
        uiProbeSink?.invoke(
            "typeGraph",
            "focus=$snapFocus shown=${data.graph.nodes.size} included=${data.included.size}" +
                " total=${index.typeCount} filter=$snapFilter" +
                " showAll=$snapShowAll expanded=${snapExpandedTypes.size} buildMillis=$buildMillis",
        )
    }

    val data = graphData

    Column(modifier = Modifier.fillMaxSize()) {
        // Header bar: focus title + filter controls + counters + show-all button.
        val isDarkHeader = JewelTheme.isDark
        val mutedColor = if (isDarkHeader) Color(0xFFAAAAAA.toInt()) else Color(0xFF6E7177.toInt())
        val selectedBg = if (isDarkHeader) Color(0xFF3A3A3A.toInt()) else Color(0xFFDDDDDD.toInt())
        val normalBg = Color.Transparent

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Focus type name: leftmost, truncated when long.
            val focusName = provider.typeDisplayName(focus)
            val focusTitleText = if (showAll) {
                "All types  ($focusName type $focus)"
            } else {
                "$focusName  (type $focus)"
            }
            Text(
                text = focusTitleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 260.dp),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDarkHeader) Color(0xFFCCCCCC.toInt()) else Color(0xFF333333.toInt()),
                ),
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Filter segmented control (three pills)
            for (mode in TypeGraphFilter.entries) {
                val label = when (mode) {
                    TypeGraphFilter.HIERARCHY -> "Hierarchy"
                    TypeGraphFilter.FIELD_REFS -> "Field refs"
                    TypeGraphFilter.BOTH -> "Both"
                }
                val selected = filter == mode
                Text(
                    text = label,
                    modifier = Modifier
                        .background(if (selected) selectedBg else normalBg, shape = RoundedCornerShape(4.dp))
                        .clickable { if (!selected) { filter = mode; showAll = false } }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isDarkHeader) Color(0xFFCCCCCC.toInt()) else Color(0xFF333333.toInt()),
                    ),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Counter
            val shown = data?.included?.size ?: 0
            val total = index.typeCount
            Text(
                text = "showing $shown of $total types",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = mutedColor),
            )

            // B2: depth/hops compact dropdowns (1..5).
            val depthHopsStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (isDarkHeader) Color(0xFFCCCCCC.toInt()) else Color(0xFF333333.toInt()),
            )
            val maxDepthHops = 5
            val menuBg = if (isDarkHeader) Color(0xFF2B2B2B.toInt()) else Color(0xFFF5F5F5.toInt())
            var depthMenuOpen by remember { mutableStateOf(false) }
            Box {
                Text(
                    text = "depth: $subtypeDepth",
                    modifier = Modifier
                        .background(if (subtypeDepth > 1) selectedBg else normalBg, shape = RoundedCornerShape(4.dp))
                        .clickable { depthMenuOpen = true }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = depthHopsStyle,
                )
                if (depthMenuOpen) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, 28),
                        focusable = true,
                        onDismissRequest = { depthMenuOpen = false },
                    ) {
                        Column(
                            modifier = Modifier
                                .background(menuBg, shape = RoundedCornerShape(4.dp))
                                .border(1.dp, selectedBg, shape = RoundedCornerShape(4.dp)),
                        ) {
                            for (v in 1..maxDepthHops) {
                                Text(
                                    text = "$v",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { subtypeDepth = v; depthMenuOpen = false }
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = depthHopsStyle,
                                )
                            }
                        }
                    }
                }
            }
            var hopsMenuOpen by remember { mutableStateOf(false) }
            Box {
                Text(
                    text = "hops: $fieldHops",
                    modifier = Modifier
                        .background(if (fieldHops > 1) selectedBg else normalBg, shape = RoundedCornerShape(4.dp))
                        .clickable { hopsMenuOpen = true }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = depthHopsStyle,
                )
                if (hopsMenuOpen) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, 28),
                        focusable = true,
                        onDismissRequest = { hopsMenuOpen = false },
                    ) {
                        Column(
                            modifier = Modifier
                                .background(menuBg, shape = RoundedCornerShape(4.dp))
                                .border(1.dp, selectedBg, shape = RoundedCornerShape(4.dp)),
                        ) {
                            for (v in 1..maxDepthHops) {
                                Text(
                                    text = "$v",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { fieldHops = v; hopsMenuOpen = false }
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = depthHopsStyle,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Show-all button
            if (!showAll) {
                if (showAllConfirmPending) {
                    Text(
                        text = "Large module: $total types. Click again to render.",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = mutedColor),
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    text = "Show all types ($total)",
                    modifier = Modifier
                        .background(selectedBg, shape = RoundedCornerShape(4.dp))
                        .clickable {
                            if (total > 300 && !showAllConfirmPending) {
                                showAllConfirmPending = true
                            } else {
                                showAll = true
                                showAllConfirmPending = false
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (isDarkHeader) Color(0xFFCCCCCC.toInt()) else Color(0xFF333333.toInt()),
                    ),
                )
            }
        }

        // Canvas area
        if (data == null) {
            Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                Text("Loading...")
            }
            return@Column
        }

        if (data.graph.nodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                Text("No types to show for the current filter.")
            }
            return@Column
        }

        val textMeasurer = rememberTextMeasurer()

        // Holder for the last committed layout and the param tuple that produced it.
        // Stored outside remember(data) so incremental path can read the previous layout.
        // Plain mutable class, not MutableState, to avoid triggering recomposition on assignment.
        class LayoutRef {
            var layout: TgLayout? = null
            // The "stable" params tuple: everything except expandedTypes.
            var stableFocus: Int = -1
            var stableFilter: TypeGraphFilter = TypeGraphFilter.BOTH
            var stableShowAll: Boolean = false
            var stableSubtypeDepth: Int = -1
            var stableFieldHops: Int = -1
            // The expandedTypes at the time of the last layout, used to diff against current.
            var stableExpanded: Set<Int> = emptySet()
            // Node strings at the time of last layout; keyed by typeIdx.
            var prevNodeLabels: Map<Int, String> = emptyMap()
            var prevNodeSubLabels: Map<Int, String> = emptyMap()
        }
        val layoutRef = remember { LayoutRef() }

        val labelStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        val subStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        val recCaptionStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)

        // Compute layout at most once per distinct GraphData instance.
        // All layoutRef reads/writes are inside this remember block so that unrelated
        // recompositions (popup state, showAllConfirmPending, etc.) never re-execute them.
        // textMeasurer is captured by value; theme changes that matter flow through data/isDark.
        // Returns Pair(TgLayout, newNodeIds) so the auto-pan effect can see which nodes were added.
        val (layout: TgLayout, newNodeIds: Set<Int>) = remember(data) {
            // Decide incremental vs full layout.
            // Incremental: stable params match AND previous layout exists AND the data's
            // expandedTypes snapshot differs from the last committed stableExpanded by exactly
            // one id AND that id has a rect in the previous layout (anchor known).
            // Using data.expandedTypes (not live expandedTypes) avoids generation skew: the
            // GraphData was built for this exact snapshot.
            val prevLayout = layoutRef.layout
            val stableParamsMatch = prevLayout != null &&
                layoutRef.stableFocus == data.snapshotFocus &&
                layoutRef.stableFilter == data.snapshotFilter &&
                layoutRef.stableShowAll == data.snapshotShowAll &&
                layoutRef.stableSubtypeDepth == data.snapshotSubtypeDepth &&
                layoutRef.stableFieldHops == data.snapshotFieldHops

            val expandedDelta: Set<Int> = if (stableParamsMatch) {
                val prev = layoutRef.stableExpanded
                val added = data.expandedTypes - prev
                val removed = prev - data.expandedTypes
                // Accept delta only if exactly one id changed (added xor removed, not both).
                if ((added.size == 1 && removed.isEmpty()) || (added.isEmpty() && removed.size == 1)) {
                    added + removed
                } else emptySet()
            } else emptySet()

            val anchorId = expandedDelta.firstOrNull()
            val useIncremental = stableParamsMatch && anchorId != null && prevLayout!!.nodePositions.containsKey(anchorId)

            if (!useIncremental || prevLayout == null) {
                val fresh = layoutTypeGraph(data.graph, textMeasurer, data.recClusters)
                layoutRef.layout = fresh
                layoutRef.stableFocus = data.snapshotFocus
                layoutRef.stableFilter = data.snapshotFilter
                layoutRef.stableShowAll = data.snapshotShowAll
                layoutRef.stableSubtypeDepth = data.snapshotSubtypeDepth
                layoutRef.stableFieldHops = data.snapshotFieldHops
                layoutRef.stableExpanded = data.expandedTypes
                layoutRef.prevNodeLabels = data.graph.nodes.mapValues { (_, n) -> n.label }
                layoutRef.prevNodeSubLabels = data.graph.nodes.mapValues { (_, n) -> n.sublabel }
                fresh to emptySet<Int>()
            } else {
                // Incremental path.
                val currentIds = data.graph.nodes.keys
                val previousIds = prevLayout.nodePositions.keys
                val survivorIds = currentIds.intersect(previousIds)
                val newIds = currentIds - previousIds.toSet()

                // Carry forward survivor text results; re-measure nodes whose strings changed.
                val nodeLabelResults = mutableMapOf<Int, TextLayoutResult>()
                val nodeSubResults = mutableMapOf<Int, TextLayoutResult>()

                for (id in survivorIds) {
                    val node = data.graph.nodes[id] ?: continue
                    val prevLabel = layoutRef.prevNodeLabels[id]
                    val prevSub = layoutRef.prevNodeSubLabels[id]
                    if (prevLabel == node.label && prevLabel != null) {
                        prevLayout.nodeLabelResults[id]?.let { nodeLabelResults[id] = it }
                    } else {
                        nodeLabelResults[id] = textMeasurer.measure(node.label, style = labelStyle)
                    }
                    if (prevSub == node.sublabel && prevSub != null) {
                        prevLayout.nodeSubResults[id]?.let { nodeSubResults[id] = it }
                    } else {
                        nodeSubResults[id] = textMeasurer.measure(node.sublabel, style = subStyle)
                    }
                }
                for (id in newIds) {
                    val node = data.graph.nodes[id] ?: continue
                    nodeLabelResults[id] = textMeasurer.measure(node.label, style = labelStyle)
                    nodeSubResults[id] = textMeasurer.measure(node.sublabel, style = subStyle)
                }

                // Build merged positions: survivors keep their rects; anchor gets its width/height
                // updated in-place if the fold-badge changed (keep top-left, update size).
                val mergedPositions = mutableMapOf<Int, TgNodeRect>()
                for (id in survivorIds) {
                    val labelR = nodeLabelResults[id] ?: continue
                    val subR = nodeSubResults[id] ?: continue
                    val textWidth = maxOf(labelR.size.width.toFloat(), subR.size.width.toFloat())
                    val newW = textWidth + NODE_PADDING_H * 2
                    val newH = labelR.size.height.toFloat() + subR.size.height.toFloat() + NODE_PADDING_V * 2 + 4f
                    val prev = prevLayout.nodePositions[id]!!
                    // Keep top-left; update size (transient overlap on anchor is acceptable).
                    mergedPositions[id] = TgNodeRect(prev.x, prev.y, newW, newH)
                }

                // Place new nodes incrementally below the anchor.
                // vGap = anchor drop; rowGap = WRAP_ROW_GAP for inter-row spacing.
                val anchorRect = mergedPositions[anchorId!!]
                if (anchorRect != null && newIds.isNotEmpty()) {
                    val newSizes = newIds.mapNotNull { id ->
                        val lR = nodeLabelResults[id] ?: return@mapNotNull null
                        val sR = nodeSubResults[id] ?: return@mapNotNull null
                        val tw = maxOf(lR.size.width.toFloat(), sR.size.width.toFloat())
                        val w = tw + NODE_PADDING_H * 2
                        val h = lR.size.height.toFloat() + sR.size.height.toFloat() + NODE_PADDING_V * 2 + 4f
                        id to (w to h)
                    }.toMap()
                    val placed = placeNewNodesIncrementally(
                        previousPositions = mergedPositions,
                        survivorIds = survivorIds,
                        newNodeSizes = newSizes,
                        anchorRect = anchorRect,
                        maxRowWidth = MAX_ROW_WIDTH,
                        hGap = HORIZONTAL_GAP,
                        vGap = VERTICAL_GAP,
                        rowGap = WRAP_ROW_GAP,
                    )
                    mergedPositions.putAll(placed)
                }

                // Recompute rec-group frames and carry forward caption results for existing groups.
                val recFrames = computeRecGroupFrames(data.recClusters, mergedPositions)
                val recCaptionResults = mutableMapOf<Int, TextLayoutResult>()
                for (frame in recFrames) {
                    recCaptionResults[frame.groupIndex] = prevLayout.recCaptionResults[frame.groupIndex]
                        ?: textMeasurer.measure("rec #${frame.groupIndex}", style = recCaptionStyle)
                }

                val totalWidth = if (mergedPositions.isEmpty()) 0f else
                    mergedPositions.values.maxOf { it.x + it.width } - mergedPositions.values.minOf { it.x }
                val totalHeight = if (mergedPositions.isEmpty()) 0f else
                    mergedPositions.values.maxOf { it.y + it.height }

                val layoutEdges = data.graph.edges.map { TgLayoutEdge(it.from, it.to, it.kind) }
                val inc = TgLayout(mergedPositions, layoutEdges, recFrames, totalWidth, totalHeight,
                    nodeLabelResults, nodeSubResults, recCaptionResults)
                layoutRef.layout = inc
                layoutRef.stableExpanded = data.expandedTypes
                layoutRef.prevNodeLabels = data.graph.nodes.mapValues { (_, n) -> n.label }
                layoutRef.prevNodeSubLabels = data.graph.nodes.mapValues { (_, n) -> n.sublabel }
                inc to newIds
            }
        }

        // Content bounds in graph coordinates; rec-group frames inflate past node rects.
        val contentBounds = remember(layout) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (r in layout.nodePositions.values) {
                minX = minOf(minX, r.x)
                minY = minOf(minY, r.y)
                maxX = maxOf(maxX, r.x + r.width)
                maxY = maxOf(maxY, r.y + r.height)
            }
            for (f in layout.recGroupFrames) {
                minX = minOf(minX, f.x)
                minY = minOf(minY, f.y)
                maxX = maxOf(maxX, f.x + f.width)
                maxY = maxOf(maxY, f.y + f.height)
            }
            Rect(minX, minY, maxX, maxY)
        }

        var panOffset by remember { mutableStateOf(Offset.Zero) }
        var scale by remember { mutableStateOf(1f) }
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        var hoveredNodeId by remember { mutableStateOf<Int?>(null) }
        var lastPressedNodeId by remember { mutableStateOf<Int?>(null) }
        var lastPressUptimeMillis by remember { mutableLongStateOf(0L) }
        val isDark = JewelTheme.isDark

        // Pan bounds: keep the graph within TG_SCROLL_MARGIN of the viewport so it
        // cannot be dragged out of reach; the scrollbars share the same range.
        fun clampPan(offset: Offset, scaleNow: Float): Offset {
            if (canvasSize.width == 0 || canvasSize.height == 0) return offset
            val panAtLeftEdge = TG_SCROLL_MARGIN - canvasSize.width / 2f - contentBounds.left * scaleNow
            val panAtRightEdge = canvasSize.width / 2f - TG_SCROLL_MARGIN - contentBounds.right * scaleNow
            val panAtTopEdge = TG_SCROLL_MARGIN - TG_ORIGIN_TOP - contentBounds.top * scaleNow
            val panAtBottomEdge = canvasSize.height - TG_ORIGIN_TOP - TG_SCROLL_MARGIN - contentBounds.bottom * scaleNow
            return Offset(
                offset.x.coerceIn(minOf(panAtLeftEdge, panAtRightEdge), maxOf(panAtLeftEdge, panAtRightEdge)),
                offset.y.coerceIn(minOf(panAtTopEdge, panAtBottomEdge), maxOf(panAtTopEdge, panAtBottomEdge)),
            )
        }

        fun applyScale(newScale: Float) {
            scale = newScale.coerceIn(0.2f, 3f)
            panOffset = clampPan(panOffset, scale)
        }

        // Auto-pan: after an incremental expansion that added new nodes, scroll to make the
        // top of the new-node bounding box visible if it is fully outside the viewport.
        // Keyed on (layout, canvasSize) so it re-fires when the canvas size becomes known
        // after the layout was already set (avoids the early-return / never-fires problem).
        // newNodeIds comes from the remember(data) block above (non-empty only on incremental path).
        LaunchedEffect(layout, canvasSize) {
            if (newNodeIds.isEmpty() || canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
            val newRects = newNodeIds.mapNotNull { layout.nodePositions[it] }
            if (newRects.isEmpty()) return@LaunchedEffect
            val newMinY = newRects.minOf { it.y }
            val newMaxY = newRects.maxOf { it.y + it.height }
            val newMinX = newRects.minOf { it.x }
            val newMaxX = newRects.maxOf { it.x + it.width }
            // Screen coordinates of new-node bounding box.
            val originX = canvasSize.width / 2f + panOffset.x
            val originY = TG_ORIGIN_TOP + panOffset.y
            val screenTop = originY + newMinY * scale
            val screenBottom = originY + newMaxY * scale
            val screenLeft = originX + newMinX * scale
            val screenRight = originX + newMaxX * scale
            // If any part is inside the viewport, do not move.
            val viewW = canvasSize.width.toFloat()
            val viewH = canvasSize.height.toFloat()
            val partiallyVisible = screenTop < viewH && screenBottom > 0f && screenLeft < viewW && screenRight > 0f
            if (!partiallyVisible) {
                // Bring the top of new nodes to TG_SCROLL_MARGIN below origin.
                val targetPanY = TG_SCROLL_MARGIN - TG_ORIGIN_TOP - newMinY * scale
                panOffset = clampPan(Offset(panOffset.x, targetPanY), scale)
            }
        }

        val canvasFocusRequester = remember { FocusRequester() }

        val hScrollAdapter = remember(contentBounds) {
            object : ScrollbarAdapter {
                override val contentSize: Double
                    get() = (contentBounds.width * scale + 2 * TG_SCROLL_MARGIN).toDouble()
                override val viewportSize: Double
                    get() = canvasSize.width.toDouble()
                override val scrollOffset: Double
                    get() {
                        val left = canvasSize.width / 2f + panOffset.x + contentBounds.left * scale - TG_SCROLL_MARGIN
                        return (-left).toDouble().coerceIn(0.0, maxOf(0.0, contentSize - viewportSize))
                    }
                override suspend fun scrollTo(scrollOffset: Double) {
                    val x = TG_SCROLL_MARGIN - scrollOffset.toFloat() - canvasSize.width / 2f - contentBounds.left * scale
                    panOffset = clampPan(Offset(x, panOffset.y), scale)
                }
            }
        }
        val vScrollAdapter = remember(contentBounds) {
            object : ScrollbarAdapter {
                override val contentSize: Double
                    get() = (contentBounds.height * scale + 2 * TG_SCROLL_MARGIN).toDouble()
                override val viewportSize: Double
                    get() = canvasSize.height.toDouble()
                override val scrollOffset: Double
                    get() {
                        val top = TG_ORIGIN_TOP + panOffset.y + contentBounds.top * scale - TG_SCROLL_MARGIN
                        return (-top).toDouble().coerceIn(0.0, maxOf(0.0, contentSize - viewportSize))
                    }
                override suspend fun scrollTo(scrollOffset: Double) {
                    val y = TG_SCROLL_MARGIN - scrollOffset.toFloat() - TG_ORIGIN_TOP - contentBounds.top * scale
                    panOffset = clampPan(Offset(panOffset.x, y), scale)
                }
            }
        }

        val modLabel = if (SystemInfo.isMac) "Cmd" else "Ctrl"
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .focusRequester(canvasFocusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (KeymapShortcuts.isModifierOnly(event)) return@onPreviewKeyEvent false
                    val zoomMod = if (SystemInfo.isMac) event.keyIsMetaPressed else event.keyIsCtrlPressed
                    if (!zoomMod) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Equals, Key.NumPadAdd -> { applyScale(scale * 1.2f); true }
                        Key.Minus, Key.NumPadSubtract -> { applyScale(scale / 1.2f); true }
                        Key.Zero, Key.NumPad0 -> { scale = 1f; panOffset = Offset.Zero; true }
                        else -> false
                    }
                },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(contentBounds) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            panOffset = clampPan(panOffset + dragAmount, scale)
                        }
                    }
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                        val mods = event.keyboardModifiers
                        val zoomModifier = if (SystemInfo.isMac) mods.isMetaPressed else mods.isCtrlPressed
                        when {
                            zoomModifier -> {
                                val zoomFactor = if (delta.y > 0) 0.9f else 1.1f
                                applyScale(scale * zoomFactor)
                            }
                            mods.isShiftPressed -> {
                                // Shift+wheel on a mouse: horizontal pan with y delta.
                                panOffset = clampPan(panOffset + Offset(wheelPanAmount(delta.y), 0f), scale)
                            }
                            else -> {
                                // Plain wheel: pan (content follows natural scroll direction).
                                panOffset = clampPan(
                                    panOffset + Offset(wheelPanAmount(delta.x), wheelPanAmount(delta.y)),
                                    scale,
                                )
                            }
                        }
                    }
                    .onPointerEvent(PointerEventType.Move) { event ->
                        val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                        val origin = Offset(size.width / 2f, TG_ORIGIN_TOP) + panOffset
                        val graphX = (pos.x - origin.x) / scale
                        val graphY = (pos.y - origin.y) / scale
                        hoveredNodeId = layout.nodePositions.entries.firstOrNull { (_, r) ->
                            graphX >= r.x && graphX <= r.x + r.width &&
                                graphY >= r.y && graphY <= r.y + r.height
                        }?.key
                    }
                    .onPointerEvent(PointerEventType.Exit) { hoveredNodeId = null }
                    .onPointerEvent(PointerEventType.Press) { event ->
                        canvasFocusRequester.requestFocus()
                        val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                        val origin = Offset(size.width / 2f, TG_ORIGIN_TOP) + panOffset
                        val graphX = (pos.x - origin.x) / scale
                        val graphY = (pos.y - origin.y) / scale
                        val clickedId = layout.nodePositions.entries.firstOrNull { (_, r) ->
                            graphX >= r.x && graphX <= r.x + r.width &&
                                graphY >= r.y && graphY <= r.y + r.height
                        }?.key

                        // B3: Alt+click opens the type-info popup; does not toggle expansion
                        // or count as the first click of a double-click sequence.
                        // event.keyboardModifiers is available on the PointerEvent in Compose Desktop.
                        if (clickedId != null && event.keyboardModifiers.isAltPressed) {
                            typeInfoPopupRequest = clickedId
                            return@onPointerEvent
                        }

                        val pressUptimeMillis = event.changes.firstOrNull()?.uptimeMillis ?: 0L
                        val isDoubleClick = clickedId != null &&
                            clickedId == lastPressedNodeId &&
                            pressUptimeMillis - lastPressUptimeMillis <= DOUBLE_CLICK_INTERVAL_MS
                        lastPressedNodeId = if (isDoubleClick) null else clickedId
                        lastPressUptimeMillis = pressUptimeMillis

                        if (clickedId != null) {
                            if (isDoubleClick) {
                                // Double-click: navigate back to WAT
                                val offset = provider.leafTypeRecords.getOrNull(clickedId)?.offset?.toLong() ?: 0L
                                onNavigateToType(clickedId, offset)
                            } else {
                                // Single click: expand neighbors
                                expandedTypes = if (clickedId in expandedTypes) {
                                    expandedTypes - clickedId
                                } else {
                                    expandedTypes + clickedId
                                }
                            }
                        }
                    }
            ) {
                val origin = Offset(size.width / 2f, TG_ORIGIN_TOP) + panOffset

                withTransform({
                    translate(origin.x, origin.y)
                    scale(scale, scale, Offset.Zero)
                }) {
                    // Draw rec-group cluster frames FIRST (behind nodes).
                    // Honest frames: bbox contains only member nodes -> full fill + border + caption.
                    // Dishonest frames: bbox engulfs non-members -> accent bar on each member's left
                    // edge + caption once above the first member (skipped if it overlaps another node).
                    val recFrameBg = if (isDark) TypeNodeColors.REC_FRAME_DARK else TypeNodeColors.REC_FRAME_LIGHT
                    val recFrameBorder = if (isDark) TypeNodeColors.REC_FRAME_BORDER_DARK else TypeNodeColors.REC_FRAME_BORDER_LIGHT
                    val accentBarWidth = 3.5f
                    for (frame in layout.recGroupFrames) {
                        if (frame.honest) {
                            drawRoundRect(
                                color = recFrameBg,
                                topLeft = Offset(frame.x, frame.y),
                                size = Size(frame.width, frame.height),
                                cornerRadius = CornerRadius(8f),
                            )
                            drawRoundRect(
                                color = recFrameBorder,
                                topLeft = Offset(frame.x, frame.y),
                                size = Size(frame.width, frame.height),
                                cornerRadius = CornerRadius(8f),
                                style = Stroke(width = 1.5f),
                            )
                            val caption = layout.recCaptionResults[frame.groupIndex] ?: continue
                            drawText(
                                caption,
                                color = recFrameBorder,
                                topLeft = Offset(frame.x + 6f, frame.y + 4f),
                            )
                        } else {
                            // Accent bar on left edge of each member node.
                            for (memberId in frame.memberIds) {
                                val memberRect = layout.nodePositions[memberId] ?: continue
                                drawRect(
                                    color = recFrameBorder,
                                    topLeft = Offset(memberRect.x, memberRect.y),
                                    size = Size(accentBarWidth, memberRect.height),
                                )
                            }
                            // Caption above the first member whose position is known.
                            val caption = layout.recCaptionResults[frame.groupIndex] ?: continue
                            val firstRect = frame.memberIds.mapNotNull { layout.nodePositions[it] }
                                .minWithOrNull(compareBy({ it.y }, { it.x })) ?: continue
                            val captionW = caption.size.width.toFloat()
                            val captionH = caption.size.height.toFloat()
                            val captionX = firstRect.x
                            val captionY = firstRect.y - captionH - 2f
                            // Skip caption if it would overlap any node rect.
                            val captionOverlaps = layout.nodePositions.values.any { r ->
                                captionX < r.x + r.width && captionX + captionW > r.x &&
                                    captionY < r.y + r.height && captionY + captionH > r.y
                            }
                            if (!captionOverlaps) {
                                drawText(caption, color = recFrameBorder, topLeft = Offset(captionX, captionY))
                            }
                        }
                    }

                    // Build edge draw infos.
                    for (edge in layout.edges) {
                        val from = layout.nodePositions[edge.from] ?: continue
                        val to = layout.nodePositions[edge.to] ?: continue
                        val sameLayer = kotlin.math.abs(from.y - to.y) < 1f
                        val isBackEdge = !sameLayer && from.y + from.height > to.y + to.height
                        val edgeKind = when {
                            sameLayer -> TgEdgeKind.SAME_LAYER
                            isBackEdge -> TgEdgeKind.BACK_EDGE
                            else -> TgEdgeKind.NORMAL
                        }
                        val hl = when (hoveredNodeId) {
                            edge.from -> TgHighlightKind.OUTGOING
                            edge.to -> TgHighlightKind.INCOMING
                            else -> TgHighlightKind.NONE
                        }
                        val (startX, startY, endX, endY) = when (edgeKind) {
                            TgEdgeKind.NORMAL -> arrayOf(
                                from.x + from.width / 2f, from.y + from.height,
                                to.x + to.width / 2f, to.y,
                            )
                            TgEdgeKind.SAME_LAYER -> arrayOf(
                                from.x + from.width / 2f, from.y + from.height,
                                to.x + to.width / 2f, to.y + to.height,
                            )
                            TgEdgeKind.BACK_EDGE -> arrayOf(
                                from.x + from.width / 2f, from.y,
                                to.x + to.width / 2f, to.y + to.height,
                            )
                        }
                        drawTypeEdge(startX, startY, endX, endY, edge.kind, hl, edgeKind, isDark)
                    }

                    // Draw nodes.
                    for ((id, rect) in layout.nodePositions) {
                        val node = data.graph.nodes[id] ?: continue
                        val labelResult = layout.nodeLabelResults[id] ?: continue
                        val subResult = layout.nodeSubResults[id] ?: continue
                        drawTypeNode(rect.x, rect.y, rect.width, rect.height, node, labelResult, subResult, hoveredNodeId == id, isDark)
                    }
                }
            }

            VerticalScrollbar(
                adapter = vScrollAdapter,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = typeGraphScrollbarStyle(),
            )
            HorizontalScrollbar(
                adapter = hScrollAdapter,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                style = typeGraphScrollbarStyle(),
            )

            // Zoom controls (copied from WasmComponentDependencyGraph.kt, extract when a third consumer appears)
            val controlBg = if (isDark) Color(0xCC1E1E1E.toInt()) else Color(0xCCF5F5F5.toInt())
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
                    modifier = Modifier.size(28.dp).clickable { applyScale(scale * 1.2f) },
                    contentAlignment = Alignment.Center,
                ) { Text("+", color = controlText) }
                Spacer(modifier = Modifier.height(2.dp))
                Text("${(scale * 100).toInt()}%", color = controlMuted, fontSize = 9.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier.size(28.dp).clickable { applyScale(scale / 1.2f) },
                    contentAlignment = Alignment.Center,
                ) { Text("−", color = controlText) }
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier.size(28.dp).clickable { scale = 1f; panOffset = Offset.Zero },
                    contentAlignment = Alignment.Center,
                ) { Text("⌂", color = controlText, fontSize = 14.sp) }
            }

            // B3: type-info popup for Alt+click on a node.
            val popupData = typeInfoPopupData
            if (popupData != null) {
                WasmTypeInfoPopup(
                    data = popupData,
                    onGotoType = { typeIdx ->
                        typeInfoPopupData = null
                        typeInfoPopupRequest = null
                        val off = provider.leafTypeRecords.getOrNull(typeIdx)?.offset?.toLong() ?: 0L
                        onNavigateToType(typeIdx, off)
                    },
                    onGotoFunc = { _ ->
                        // Type Graph panel has no WAT func navigation; dismiss.
                        typeInfoPopupData = null
                        typeInfoPopupRequest = null
                    },
                    onDismiss = {
                        typeInfoPopupData = null
                        typeInfoPopupRequest = null
                    },
                )
            }

            // Legend
            val legendBg = if (isDark) Color(0xCC1E1E1E.toInt()) else Color(0xCCF5F5F5.toInt())
            val legendMuted = if (isDark) Color(0xFFAAAAAA.toInt()) else Color(0xFF6E7177.toInt())
            var legendExpanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(legendBg)
                    .clickable { legendExpanded = !legendExpanded }
                    .padding(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (legendExpanded) "▼" else "▶", color = legendMuted)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Legend")
                }
                if (legendExpanded) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TypeLegendItem(if (isDark) TypeNodeColors.STRUCT else TypeNodeColors.STRUCT_LIGHT, "Struct")
                    TypeLegendItem(if (isDark) TypeNodeColors.ARRAY else TypeNodeColors.ARRAY_LIGHT, "Array")
                    TypeLegendItem(if (isDark) TypeNodeColors.FUNC else TypeNodeColors.FUNC_LIGHT, "Func")
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(modifier = Modifier.width(24.dp).height(2.dp).background(legendMuted))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Subtype edge", color = legendMuted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        // Visual indicator for dashed = field ref
                        Box(modifier = Modifier.width(24.dp).height(2.dp).background(legendMuted.copy(alpha = 0.5f)))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Field ref edge (dashed)", color = legendMuted)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Click node to expand neighbors", color = legendMuted)
                    Text("Double-click to navigate to WAT", color = legendMuted)
                    Text("Drag or scroll to pan", color = legendMuted)
                    Text(
                        "$modLabel+scroll or $modLabel++/− to zoom, $modLabel+0 to reset",
                        color = legendMuted,
                    )
                }
            }
        }
    }
}

// Overlay scrollbars for the pan/zoom canvas (pattern from RowDetailPanel.kt).
@Composable
private fun typeGraphScrollbarStyle(): ScrollbarStyle {
    val color = if (JewelTheme.isDark) Color(0xFFCCCCCC.toInt()) else Color(0xFF6E7177.toInt())
    return ScrollbarStyle(
        minimalHeight = 16.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 300,
        unhoverColor = color.copy(alpha = 0.35f),
        hoverColor = color.copy(alpha = 0.70f),
    )
}

@Composable
private fun TypeLegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Box(modifier = Modifier.size(12.dp).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label)
    }
}
