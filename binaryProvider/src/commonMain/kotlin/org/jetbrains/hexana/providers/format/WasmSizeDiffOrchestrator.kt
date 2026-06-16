package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.analysis.diff.MatchKey
import org.jetbrains.hexana.wasm.analysis.diff.NodeKind
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffNode
import org.jetbrains.hexana.wasm.analysis.diff.WasmSizeDiff
import org.jetbrains.hexana.wasm.analysis.diff.match.FunctionMatch

/**
 * Builds a name-enriched size diff between two WASM modules.
 *
 * The pure engine ([WasmSizeDiff]) aligns trees by an opaque key; this layer
 * supplies that key. Functions are paired by the **semantic [FunctionMatcher]**
 * (renumber-invariant content hash + name/call-graph/fuzzy passes), so a size row —
 * and the WAT drill-in it opens — corresponds to the *same* function on both sides
 * even when the function set churned (inlining, dead-code elimination, added imports).
 * The matcher's paired functions are given a shared `fn:pair:<L>:<R>` key; unmatched
 * functions become `fn:rmL:<L>` / `fn:addR:<R>` so they fall out as REMOVED / ADDED.
 *
 * Earlier this layer keyed functions by resolved name with a **code-section index**
 * fallback — that positionally mis-paired functions on a churned/minified build (and
 * its drill-in then diffed two unrelated functions). The name/index keying survives
 * only as a fallback for the rare case the matcher cannot run.
 *
 * After diffing, function nodes are enriched with display names (the resolved name,
 * or a `$func<globalIndex>` fallback), since the raw size tree only carries `"func N"`.
 */
public object WasmSizeDiffOrchestrator {

    private const val FN_PAIR_PREFIX = "fn:pair:"     // fn:pair:<leftCodeIdx>:<rightCodeIdx>
    private const val FN_RM_PREFIX = "fn:rmL:"        // fn:rmL:<leftCodeIdx>
    private const val FN_ADD_PREFIX = "fn:addR:"      // fn:addR:<rightCodeIdx>
    // Fallback keys (used only when the semantic matcher cannot run).
    private const val FN_NAME_PREFIX = "fn:name:"
    private const val FN_INDEX_PREFIX = "fn:idx:"

    /**
     * @return the enriched diff tree, or `null` if either side has no size
     *   analysis (parse failure, or a non-core module such as a component).
     */
    public fun diff(left: WasmBinaryProvider, right: WasmBinaryProvider): SizeDiffNode? {
        val treeLeft = left.sizeAnalysis ?: return null
        val treeRight = right.sizeAnalysis ?: return null

        // Pre-compute, per node identity, the match key for every function on each side.
        // BinarySizeNode has no equals override, so the map is keyed by reference identity.
        val keys = HashMap<BinarySizeNode, MatchKey>()
        val match = WasmFunctionMatchProvider.match(left, right)
        // Matched functions keyed by right code index (unique) so enrichNames can carry the
        // matcher's classification (incl. MOVED) + confidence onto the size row.
        val byRight: Map<Int, FunctionMatch> = if (match != null) {
            val leftToRight = match.matches.associate { it.leftIdx to it.rightIdx }
            val rightToLeft = match.matches.associate { it.rightIdx to it.leftIdx }
            indexFunctionKeysSemantic(treeLeft, isLeft = true, leftToRight, rightToLeft, keys)
            indexFunctionKeysSemantic(treeRight, isLeft = false, leftToRight, rightToLeft, keys)
            match.matches.associateBy { it.rightIdx }
        } else {
            // Rare: a side could not be matched as a core module. Preserve the old
            // resolved-name / index keying rather than collapsing everything to add/remove.
            indexFunctionKeysByName(treeLeft, left, keys)
            indexFunctionKeysByName(treeRight, right, keys)
            emptyMap()
        }

        val keyOf: (BinarySizeNode) -> MatchKey = { node ->
            keys[node] ?: WasmSizeDiff.indexKey(node)
        }

        val diff = WasmSizeDiff.diff(treeLeft, treeRight, keyOf)
        return enrichNames(diff, left, right, byRight)
    }

    /**
     * Keys function nodes from the semantic matcher pairing. A matched function gets the
     * SAME `fn:pair:<L>:<R>` string on both sides (so [WasmSizeDiff] aligns them);
     * an unmatched function gets a side-unique `fn:rmL:` / `fn:addR:` key.
     */
    private fun indexFunctionKeysSemantic(
        tree: SizeNode.Module,
        isLeft: Boolean,
        leftToRight: Map<Int, Int>,
        rightToLeft: Map<Int, Int>,
        into: MutableMap<BinarySizeNode, MatchKey>,
    ) {
        fun walk(node: BinarySizeNode) {
            if (node is SizeNode.Function) {
                val ci = node.funcIndex
                into[node] = if (isLeft) {
                    leftToRight[ci]?.let { MatchKey("$FN_PAIR_PREFIX$ci:$it") } ?: MatchKey("$FN_RM_PREFIX$ci")
                } else {
                    rightToLeft[ci]?.let { MatchKey("$FN_PAIR_PREFIX$it:$ci") } ?: MatchKey("$FN_ADD_PREFIX$ci")
                }
            }
            node.children.forEach(::walk)
        }
        walk(tree)
    }

    /** Fallback keying by resolved name, code-section index otherwise. */
    private fun indexFunctionKeysByName(
        tree: SizeNode.Module,
        provider: WasmBinaryProvider,
        into: MutableMap<BinarySizeNode, MatchKey>,
    ) {
        val resolver = WasmSymbolResolver(provider)
        val importCount = provider.importFuncCount
        fun walk(node: BinarySizeNode) {
            if (node is SizeNode.Function) {
                val name = resolver.resolve(SymbolKind.FUNCTION, importCount + node.funcIndex)
                into[node] = name?.let { MatchKey(FN_NAME_PREFIX + it) }
                    ?: MatchKey(FN_INDEX_PREFIX + node.funcIndex)
            }
            node.children.forEach(::walk)
        }
        walk(tree)
    }

    /**
     * Rewrites FUNCTION node names to resolved display names and, for matched pairs,
     * carries the cross-module matcher's classification (incl. MOVED) + confidence onto
     * the node — so the Size tab agrees with the Entities tab and a pure renumber shows
     * as MOVED rather than a (zero) size delta. One-sided nodes keep ADDED/REMOVED. The
     * resolved name (export/name-section/import) is preferred, else `$func<globalIndex>`.
     */
    private fun enrichNames(
        root: SizeDiffNode,
        left: WasmBinaryProvider,
        right: WasmBinaryProvider,
        byRight: Map<Int, FunctionMatch>,
    ): SizeDiffNode {
        val leftRes = WasmSymbolResolver(left)
        val rightRes = WasmSymbolResolver(right)
        val importLeft = left.importFuncCount
        val importRight = right.importFuncCount
        fun nameLeft(codeIdx: Int) =
            leftRes.resolve(SymbolKind.FUNCTION, importLeft + codeIdx) ?: "\$func${importLeft + codeIdx}"
        fun nameRight(codeIdx: Int) =
            rightRes.resolve(SymbolKind.FUNCTION, importRight + codeIdx) ?: "\$func${importRight + codeIdx}"

        fun walk(node: SizeDiffNode): SizeDiffNode {
            val children = node.children.map(::walk)
            if (node.kind != NodeKind.FUNCTION) return node.copy(children = children)

            val key = node.key.value
            return when {
                key.startsWith(FN_PAIR_PREFIX) -> {
                    val (l, r) = key.removePrefix(FN_PAIR_PREFIX).split(":")
                    val m = byRight[r.toInt()]
                    node.copy(
                        classification = m?.classification ?: node.classification,
                        confidence = m?.confidence,
                        leftName = nameLeft(l.toInt()),
                        rightName = nameRight(r.toInt()),
                        children = children,
                    )
                }
                key.startsWith(FN_RM_PREFIX) ->
                    node.copy(leftName = nameLeft(key.removePrefix(FN_RM_PREFIX).toInt()), rightName = null, children = children)
                key.startsWith(FN_ADD_PREFIX) ->
                    node.copy(leftName = null, rightName = nameRight(key.removePrefix(FN_ADD_PREFIX).toInt()), children = children)
                key.startsWith(FN_NAME_PREFIX) -> {
                    val name = key.removePrefix(FN_NAME_PREFIX)
                    node.copy(
                        leftName = if (node.sizeA != null) name else null,
                        rightName = if (node.sizeB != null) name else null,
                        children = children,
                    )
                }
                key.startsWith(FN_INDEX_PREFIX) -> {
                    val ci = key.removePrefix(FN_INDEX_PREFIX).toIntOrNull()
                    node.copy(
                        leftName = if (node.sizeA != null && ci != null) "\$func${importLeft + ci}" else null,
                        rightName = if (node.sizeB != null && ci != null) "\$func${importRight + ci}" else null,
                        children = children,
                    )
                }
                else -> node.copy(children = children)
            }
        }
        return walk(root)
    }
}
