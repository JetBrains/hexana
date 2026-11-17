package org.jetbrains.hexana.wasm.analysis.diff

import org.jetbrains.hexana.wasm.analysis.BinarySizeNode

/**
 * Aligns two generic [BinarySizeNode] trees (e.g. from native binary providers)
 * and classifies every node as identical / modified / added / removed.
 *
 * Children are matched by name. This is suitable for native formats where sections
 * and segments have stable names across builds of the same project.
 */
public object BinarySizeDiff {

    /**
     * Diffs two binary size trees. The roots are assumed to correspond (both are
     * the whole file) and are matched directly; their descendants are aligned by
     * name within each sibling group.
     */
    public fun diff(left: BinarySizeNode, right: BinarySizeNode): SizeDiffNode =
        matchNode(left, right)

    private fun nameKey(node: BinarySizeNode): MatchKey = MatchKey("name:${node.name}")

    private fun matchNode(left: BinarySizeNode, right: BinarySizeNode): SizeDiffNode {
        val childDiffs = alignChildren(left.children, right.children)
        val changed = left.size != right.size || childDiffs.any { it.classification != DiffClass.IDENTICAL }
        return SizeDiffNode(
            key = nameKey(left),
            kind = NodeKind.SECTION,
            classification = if (changed) DiffClass.MODIFIED else DiffClass.IDENTICAL,
            sizeA = left.size,
            sizeB = right.size,
            leftName = left.name,
            rightName = right.name,
            children = childDiffs,
        )
    }

    private fun alignChildren(
        lefts: List<BinarySizeNode>,
        rights: List<BinarySizeNode>,
    ): List<SizeDiffNode> {
        if (lefts.isEmpty() && rights.isEmpty()) return emptyList()

        val rightBuckets = HashMap<MatchKey, ArrayDeque<BinarySizeNode>>()
        for (r in rights) rightBuckets.getOrPut(nameKey(r)) { ArrayDeque() }.addLast(r)

        val out = ArrayList<SizeDiffNode>(lefts.size + rights.size)
        val matchedRight = HashSet<BinarySizeNode>()

        for (l in lefts) {
            val r = rightBuckets[nameKey(l)]?.removeFirstOrNull()
            if (r != null) {
                matchedRight.add(r)
                out.add(matchNode(l, r))
            } else {
                out.add(oneSided(l, DiffClass.REMOVED))
            }
        }
        for (r in rights) {
            if (r !in matchedRight) out.add(oneSided(r, DiffClass.ADDED))
        }
        return out
    }

    private fun oneSided(node: BinarySizeNode, cls: DiffClass): SizeDiffNode {
        val isRemoved = cls == DiffClass.REMOVED
        return SizeDiffNode(
            key = nameKey(node),
            kind = NodeKind.SECTION,
            classification = cls,
            sizeA = if (isRemoved) node.size else null,
            sizeB = if (isRemoved) null else node.size,
            leftName = if (isRemoved) node.name else null,
            rightName = if (isRemoved) null else node.name,
            children = node.children.map { oneSided(it, cls) },
        )
    }
}
