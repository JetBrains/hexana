package org.jetbrains.hexana.wasm.analysis.typegraph

/**
 * A set of type indices forming the neighborhood of a focus type.
 *
 * [included] is sorted. [totalTypes] is the module's total type count (for bounds reference).
 */
class TypeNeighborhood(val included: IntArray, val totalTypes: Int)

/**
 * Computes the neighborhood of [focus] in [index].
 *
 * When [includeHierarchy] is true: the full supertype chain up to the root is always included,
 * plus BFS down the direct-subtype edges up to [subtypeDepth] levels.
 *
 * When [includeFieldRefs] is true: BFS over field-ref edges in both directions
 * (fieldRefs + referencingTypes) up to [fieldHops] from [focus].
 * FUNC-kind nodes are included when reached but their outgoing field-ref edges are never traversed.
 *
 * Focus is always included in the result.
 */
fun computeTypeNeighborhood(
    index: TypeGraphIndex,
    focus: Int,
    includeHierarchy: Boolean,
    includeFieldRefs: Boolean,
    subtypeDepth: Int = 2,
    fieldHops: Int = 2,
): TypeNeighborhood {
    val result = HashSet<Int>()
    result.add(focus)

    if (includeHierarchy) {
        // Full supertype chain (walk up to root, no depth limit)
        var current = focus
        while (true) {
            val supers = index.superTypes(current)
            if (supers.isEmpty()) break
            // Take the first supertype (single-inheritance in practice)
            val parent = supers[0]
            if (parent in result) break
            result.add(parent)
            current = parent
        }

        // BFS down subtypes up to subtypeDepth levels
        val queue = ArrayDeque<Pair<Int, Int>>() // (typeIdx, depth)
        queue.add(focus to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (depth >= subtypeDepth) continue
            for (sub in index.directSubtypes(node)) {
                result.add(sub)
                queue.add(sub to depth + 1)
            }
        }
    }

    if (includeFieldRefs) {
        // BFS over field-ref edges (both directions) up to fieldHops from focus.
        // FUNC-kind nodes are included but not traversed.
        val visited = HashSet<Int>()
        val queue = ArrayDeque<Pair<Int, Int>>() // (typeIdx, hop)
        queue.add(focus to 0)
        visited.add(focus)

        while (queue.isNotEmpty()) {
            val (node, hop) = queue.removeFirst()
            result.add(node)
            if (hop >= fieldHops) continue
            // Do not traverse outgoing edges of FUNC-kind nodes
            val isFunc = index.kind(node) == TypeGraphNodeKind.FUNC
            if (!isFunc) {
                for (ref in index.fieldRefs(node)) {
                    if (visited.add(ref)) queue.add(ref to hop + 1)
                }
            }
            // Always traverse incoming edges (referencingTypes), regardless of kind
            for (ref in index.referencingTypes(node)) {
                if (visited.add(ref)) queue.add(ref to hop + 1)
            }
        }
    }

    val arr = result.toIntArray()
    arr.sort()
    return TypeNeighborhood(arr, index.typeCount)
}
