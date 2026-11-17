package org.jetbrains.hexana.wasm.analysis.diff

import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.SizeNode

/**
 * Aligns two WASM size-analysis trees and classifies every node as
 * identical / modified / added / removed.
 *
 * The algorithm is a pure, recursive sibling-group alignment driven entirely by
 * the caller-supplied [keyOf] extractor — it has no knowledge of names, symbols,
 * or index spaces. A higher layer (which can resolve symbol names) supplies a key
 * extractor that matches functions by resolved name with an index fallback; the
 * built-in [indexKey] is used when no resolver is available (e.g. in unit tests).
 *
 * v1 scope: structural size alignment only. No content hashing, no fuzzy matching,
 * no `MOVED` detection — see [DiffClass].
 */
public object WasmSizeDiff {

    /**
     * Diffs two module size trees. The roots are assumed to correspond (both are
     * the whole module) and are matched directly; their descendants are aligned by
     * [keyOf] within each sibling group.
     */
    public fun diff(
        left: SizeNode.Module,
        right: SizeNode.Module,
        keyOf: (BinarySizeNode) -> MatchKey = ::indexKey,
    ): SizeDiffNode = matchNode(left, right, keyOf)

    /**
     * Default index/id-based key extractor. Stable only when entity indices are
     * stable across the two modules; a name-based extractor is preferred when
     * symbol names are available.
     */
    public fun indexKey(node: BinarySizeNode): MatchKey = when (node) {
        is SizeNode.Section ->
            node.sectionId?.let { MatchKey("sec:$it") } ?: MatchKey("sec:name:${node.name}")
        is SizeNode.Function -> MatchKey("fn:${node.funcIndex}")
        is SizeNode.DataSegment -> MatchKey("data:${node.segmentIndex}")
        else -> MatchKey("name:${node.name}")
    }

    private fun matchNode(
        left: BinarySizeNode,
        right: BinarySizeNode,
        keyOf: (BinarySizeNode) -> MatchKey,
    ): SizeDiffNode {
        val childDiffs = alignChildren(left.children, right.children, keyOf)
        val changed = left.size != right.size || childDiffs.any { it.classification != DiffClass.IDENTICAL }
        return SizeDiffNode(
            key = keyOf(left),
            kind = kindOf(left),
            classification = if (changed) DiffClass.MODIFIED else DiffClass.IDENTICAL,
            sizeA = left.size,
            sizeB = right.size,
            leftName = left.name,
            rightName = right.name,
            sectionId = (left as? SizeNode.Section)?.sectionId,
            children = childDiffs,
        )
    }

    private fun alignChildren(
        lefts: List<BinarySizeNode>,
        rights: List<BinarySizeNode>,
        keyOf: (BinarySizeNode) -> MatchKey,
    ): List<SizeDiffNode> {
        if (lefts.isEmpty() && rights.isEmpty()) return emptyList()

        val rightBuckets = HashMap<MatchKey, ArrayDeque<BinarySizeNode>>()
        for (r in rights) rightBuckets.getOrPut(keyOf(r)) { ArrayDeque() }.addLast(r)

        val out = ArrayList<SizeDiffNode>(lefts.size + rights.size)
        // Identity set: BinarySizeNode has no equals override, so this is reference equality.
        val matchedRight = HashSet<BinarySizeNode>()

        for (l in lefts) {
            val r = rightBuckets[keyOf(l)]?.removeFirstOrNull()
            if (r != null) {
                matchedRight.add(r)
                out.add(matchNode(l, r, keyOf))
            } else {
                out.add(oneSided(l, keyOf, DiffClass.REMOVED))
            }
        }
        // Leftover right-side nodes, in their original order, are additions.
        for (r in rights) {
            if (r !in matchedRight) out.add(oneSided(r, keyOf, DiffClass.ADDED))
        }
        return out
    }

    /** Builds a single-sided subtree (whole subtree marked [cls]: ADDED or REMOVED). */
    private fun oneSided(
        node: BinarySizeNode,
        keyOf: (BinarySizeNode) -> MatchKey,
        cls: DiffClass,
    ): SizeDiffNode {
        val isRemoved = cls == DiffClass.REMOVED
        return SizeDiffNode(
            key = keyOf(node),
            kind = kindOf(node),
            classification = cls,
            sizeA = if (isRemoved) node.size else null,
            sizeB = if (isRemoved) null else node.size,
            leftName = if (isRemoved) node.name else null,
            rightName = if (isRemoved) null else node.name,
            sectionId = (node as? SizeNode.Section)?.sectionId,
            children = node.children.map { oneSided(it, keyOf, cls) },
        )
    }

    private fun kindOf(node: BinarySizeNode): NodeKind = when (node) {
        is SizeNode.Section -> NodeKind.SECTION
        is SizeNode.Function -> NodeKind.FUNCTION
        is SizeNode.DataSegment -> NodeKind.DATA_SEGMENT
        else -> NodeKind.OTHER
    }
}
