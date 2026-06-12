package org.jetbrains.hexana.wasm.analysis.diff

/**
 * Classification of an entity/node in a diff.
 *
 * - [IDENTICAL] — matched, content/size equal, same index.
 * - [MOVED]     — matched and content-identical, but at a different index (renumbered).
 *                Emitted only by the v2b content-hash function matcher.
 * - [MODIFIED]  — matched, but the content/size/detail differs.
 * - [ADDED]     — present only in the right (head) module.
 * - [REMOVED]   — present only in the left (base) module.
 *
 * Size-tree alignment ([WasmSizeDiff]) and entity-key alignment ([WasmEntityDiff])
 * never emit [MOVED] themselves; it originates in the cross-module function matcher.
 */
public enum class DiffClass { IDENTICAL, MOVED, MODIFIED, ADDED, REMOVED }

/** The size-tree entity kind a [SizeDiffNode] was derived from. */
public enum class NodeKind { SECTION, FUNCTION, DATA_SEGMENT, OTHER }

/**
 * Opaque, stable match key for aligning two size trees within a sibling group.
 * Equality is all the diff algorithm relies on; the caller decides what makes two
 * nodes "the same" (by index, by resolved name, etc.) via the key extractor.
 */
public data class MatchKey(val value: String)

/**
 * One node of a size diff, mirroring the shape of the two aligned
 * [org.jetbrains.hexana.wasm.analysis.SizeNode] trees.
 *
 * [sizeA] is the byte size in the base (left) module, [sizeB] in the head (right)
 * module; the side where the node is absent is `null`. [leftName]/[rightName] are
 * the raw size-tree names — placeholders such as `"func 12"` at the engine layer;
 * a higher layer may overwrite them with resolved symbol names.
 */
public data class SizeDiffNode(
    val key: MatchKey,
    val kind: NodeKind,
    val classification: DiffClass,
    val sizeA: Int?,
    val sizeB: Int?,
    val leftName: String?,
    val rightName: String?,
    val sectionId: Int? = null,
    val children: List<SizeDiffNode> = emptyList(),
) {
    /** Signed byte delta, head minus base. Positive means the head module grew. */
    val delta: Int get() = (sizeB ?: 0) - (sizeA ?: 0)

    /** A display name preferring the head side, falling back to the base side. */
    val displayName: String get() = rightName ?: leftName ?: key.value

    /** Pre-order flattening of this node and all descendants. */
    public fun flatten(): List<SizeDiffNode> {
        val out = ArrayList<SizeDiffNode>()
        fun walk(node: SizeDiffNode) {
            out.add(node)
            node.children.forEach(::walk)
        }
        walk(this)
        return out
    }
}

/** Per-section roll-up of a size diff, used to build the summary header. */
public data class SectionDelta(
    val sectionId: Int?,
    val name: String,
    val delta: Int,
    val addedFns: Int,
    val removedFns: Int,
    val modifiedFns: Int,
)

/** Top-level roll-up of a size diff. */
public data class SizeDiffSummary(
    val totalDelta: Int,
    val sections: List<SectionDelta>,
)

/**
 * Folds a diff tree (rooted at a module node) into a [SizeDiffSummary]: the total
 * module delta plus a per-section breakdown with function add/remove/modify counts.
 */
public fun SizeDiffNode.summarize(): SizeDiffSummary {
    val sectionDeltas = children
        .filter { it.kind == NodeKind.SECTION }
        .map { section ->
            val functions = section.flatten().filter { it.kind == NodeKind.FUNCTION }
            SectionDelta(
                sectionId = section.sectionId,
                name = section.displayName,
                delta = section.delta,
                addedFns = functions.count { it.classification == DiffClass.ADDED },
                removedFns = functions.count { it.classification == DiffClass.REMOVED },
                modifiedFns = functions.count { it.classification == DiffClass.MODIFIED },
            )
        }
    return SizeDiffSummary(totalDelta = delta, sections = sectionDeltas)
}
