package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.analysis.diff.MatchKey
import org.jetbrains.hexana.wasm.analysis.diff.NodeKind
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffNode
import org.jetbrains.hexana.wasm.analysis.diff.WasmSizeDiff

/**
 * Builds a name-enriched size diff between two WASM modules.
 *
 * The pure engine ([WasmSizeDiff]) aligns trees by an opaque key; this layer
 * supplies that key. Functions are matched by **resolved symbol name** (stable
 * under index renumbering) when a name is available on a side, otherwise by
 * code-section index. Sections and data segments fall back to the engine's
 * index/id key. After diffing, function nodes are enriched with display names
 * (the resolved name, or a `$func<globalIndex>` fallback), since the raw size
 * tree only carries `"func N"` placeholders.
 *
 * This is the v1 (size-impact) matcher: no content hashing, no fuzzy matching.
 */
public object WasmSizeDiffOrchestrator {

    private const val FN_NAME_PREFIX = "fn:name:"
    private const val FN_INDEX_PREFIX = "fn:idx:"

    /**
     * @return the enriched diff tree, or `null` if either side has no size
     *   analysis (parse failure, or a non-core module such as a component).
     */
    public fun diff(left: WasmBinaryProvider, right: WasmBinaryProvider): SizeDiffNode? {
        val treeLeft = left.sizeAnalysis ?: return null
        val treeRight = right.sizeAnalysis ?: return null

        // Pre-compute, per node identity, the match key for every function on each
        // side. A function's key depends on which module it came from (its own
        // resolver and import count), which the engine's single key extractor
        // cannot see — so we resolve eagerly here and look up by identity.
        val keys = HashMap<BinarySizeNode, MatchKey>()
        indexFunctionKeys(treeLeft, left, keys)
        indexFunctionKeys(treeRight, right, keys)

        val keyOf: (BinarySizeNode) -> MatchKey = { node ->
            keys[node] ?: WasmSizeDiff.indexKey(node)
        }

        val diff = WasmSizeDiff.diff(treeLeft, treeRight, keyOf)
        return enrichNames(diff, left.importFuncCount, right.importFuncCount)
    }

    private fun indexFunctionKeys(
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
     * Rewrites FUNCTION node names to resolved display names. Name-matched nodes
     * carry their name in the key; index-matched/unmatched nodes get a
     * `$func<globalIndex>` fallback computed from the side's import count.
     */
    private fun enrichNames(node: SizeDiffNode, importLeft: Int, importRight: Int): SizeDiffNode {
        val children = node.children.map { enrichNames(it, importLeft, importRight) }
        if (node.kind != NodeKind.FUNCTION) return node.copy(children = children)

        val key = node.key.value
        val (leftName, rightName) = when {
            key.startsWith(FN_NAME_PREFIX) -> {
                val name = key.removePrefix(FN_NAME_PREFIX)
                (if (node.sizeA != null) name else null) to (if (node.sizeB != null) name else null)
            }
            key.startsWith(FN_INDEX_PREFIX) -> {
                val codeIndex = key.removePrefix(FN_INDEX_PREFIX).toIntOrNull()
                val ln = if (node.sizeA != null && codeIndex != null) "\$func${importLeft + codeIndex}" else null
                val rn = if (node.sizeB != null && codeIndex != null) "\$func${importRight + codeIndex}" else null
                ln to rn
            }
            else -> node.leftName to node.rightName
        }
        return node.copy(leftName = leftName, rightName = rightName, children = children)
    }
}
