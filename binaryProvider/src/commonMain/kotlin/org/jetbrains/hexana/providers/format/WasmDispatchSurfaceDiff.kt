package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphNode
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

/**
 * One candidate that entered or left a signature's dispatch table between two
 * builds. [leftCodeIdx]/[rightCodeIdx] are defined-function code indices on
 * their respective sides (null for imports and for the side where the
 * function does not exist), ready to drive the diff view's WAT detail panel.
 * [functionAddedOrRemoved] distinguishes "the function itself is new/gone"
 * from "an existing function was newly added to / removed from the table" —
 * the latter is the quiet mechanism that pins code wasm-opt cannot remove.
 */
public data class DispatchCandidateChange(
    val name: String,
    val leftCodeIdx: Int?,
    val rightCodeIdx: Int?,
    val functionAddedOrRemoved: Boolean,
)

/** Per-signature dispatch-surface delta between two builds. */
public data class DispatchSignatureDelta(
    val signatureText: String,
    val baselineCount: Int,
    val currentCount: Int,
    val entered: List<DispatchCandidateChange>,
    val left: List<DispatchCandidateChange>,
    /** Functions containing a call_indirect of this signature, per side. */
    val baselineCallers: Int,
    val currentCallers: Int,
)

public data class WasmDispatchSurfaceDiffResult(
    /** Signatures whose candidate set or caller count changed, highest churn first. */
    val deltas: List<DispatchSignatureDelta>,
    val unchangedSignatures: Int,
)

/**
 * Diffs the `call_indirect` dispatch surface of two builds: per canonical
 * signature, which statically known candidates entered or left the table.
 *
 * Cross-build candidate identity comes from the content-hash function matcher
 * ([WasmFunctionMatchProvider]), NOT from name comparison — wasm-bindgen
 * closure shims re-hash their names every build and would otherwise report
 * full churn. Imported candidates are matched by `module.field`. Signatures
 * are joined by their canonical text; typed-funcref (WasmGC) signatures
 * render their heap-type index, so cross-build identity for those is only as
 * stable as the type index (documented v1 limitation).
 */
public object WasmDispatchSurfaceDiffOrchestrator {

    public fun diff(
        left: WasmBinaryProvider,
        right: WasmBinaryProvider,
        leftMap: WasmSymbolMap? = null,
        rightMap: WasmSymbolMap? = null,
    ): WasmDispatchSurfaceDiffResult? {
        if (!left.canProvideWasmModule() || !right.canProvideWasmModule()) return null
        val leftDispatch = left.dispatchCandidates ?: return null
        val rightDispatch = right.dispatchCandidates ?: return null
        val match = WasmFunctionMatchProvider.match(left, right, leftMap, rightMap) ?: return null

        val leftImp = left.importFuncCount
        val rightImp = right.importFuncCount

        // Global-funcidx identity maps. Defined functions pair via the
        // matcher's codeIdx pairs; imported functions pair by module.field.
        val leftToRight = HashMap<Int, Int>()
        val rightToLeft = HashMap<Int, Int>()
        for (m in match.matches) {
            leftToRight[leftImp + m.leftIdx] = rightImp + m.rightIdx
            rightToLeft[rightImp + m.rightIdx] = leftImp + m.leftIdx
        }
        val rightImportIdxByName = HashMap<Pair<String, String>, Int>()
        importFuncKeys(right).forEachIndexed { idx, key ->
            if (key !in rightImportIdxByName) rightImportIdxByName[key] = idx
        }
        importFuncKeys(left).forEachIndexed { leftIdx, key ->
            val rightIdx = rightImportIdxByName[key] ?: return@forEachIndexed
            leftToRight[leftIdx] = rightIdx
            rightToLeft[rightIdx] = leftIdx
        }

        val leftCandidates = leftDispatch.allCandidates().mapKeys { it.key.text() }
        val rightCandidates = rightDispatch.allCandidates().mapKeys { it.key.text() }
        val leftCallers = callerCounts(left)
        val rightCallers = callerCounts(right)

        val leftResolver = WasmSymbolResolver(left, symbolMap = leftMap)
        val rightResolver = WasmSymbolResolver(right, symbolMap = rightMap)
        fun name(resolver: WasmSymbolResolver, funcIdx: Int): String =
            resolver.resolve(SymbolKind.FUNCTION, funcIdx) ?: "\$func$funcIdx"

        val allSignatures = leftCandidates.keys + rightCandidates.keys + leftCallers.keys + rightCallers.keys
        val deltas = ArrayList<DispatchSignatureDelta>()
        var unchanged = 0
        for (signature in allSignatures) {
            val base = leftCandidates[signature] ?: IntArray(0)
            val cur = rightCandidates[signature] ?: IntArray(0)
            val baseSet = HashSet<Int>(base.size * 2).also { s -> base.forEach { s.add(it) } }
            val curSet = HashSet<Int>(cur.size * 2).also { s -> cur.forEach { s.add(it) } }

            val entered = buildList {
                for (r in cur) {
                    val l = rightToLeft[r]
                    if (l != null && l in baseSet) continue
                    add(
                        DispatchCandidateChange(
                            name = name(rightResolver, r),
                            leftCodeIdx = l?.minus(leftImp)?.takeIf { it >= 0 },
                            rightCodeIdx = (r - rightImp).takeIf { it >= 0 },
                            functionAddedOrRemoved = l == null,
                        ),
                    )
                }
            }
            val leftTable = buildList {
                for (l in base) {
                    val r = leftToRight[l]
                    if (r != null && r in curSet) continue
                    add(
                        DispatchCandidateChange(
                            name = name(leftResolver, l),
                            leftCodeIdx = (l - leftImp).takeIf { it >= 0 },
                            rightCodeIdx = r?.minus(rightImp)?.takeIf { it >= 0 },
                            functionAddedOrRemoved = r == null,
                        ),
                    )
                }
            }

            val baseCallers = leftCallers[signature] ?: 0
            val curCallers = rightCallers[signature] ?: 0
            if (entered.isEmpty() && leftTable.isEmpty() && baseCallers == curCallers) {
                unchanged++
                continue
            }
            deltas.add(
                DispatchSignatureDelta(
                    signatureText = signature,
                    baselineCount = base.size,
                    currentCount = cur.size,
                    entered = entered,
                    left = leftTable,
                    baselineCallers = baseCallers,
                    currentCallers = curCallers,
                ),
            )
        }
        deltas.sortByDescending { it.entered.size + it.left.size }
        return WasmDispatchSurfaceDiffResult(deltas, unchanged)
    }

    /** Function-import `module.field` keys in funcidx order (imports precede defined functions). */
    private fun importFuncKeys(p: WasmBinaryProvider): List<Pair<String, String>> =
        p.imports.filter { it.type is WasmTypeIndexExternalType }.map { it.prefix to it.name }

    /** signatureText -> number of functions dispatching through it, from the per-signature hubs. */
    private fun callerCounts(p: WasmBinaryProvider): Map<String, Int> {
        val cg = p.callGraphResult ?: return emptyMap()
        return cg.nodeMap.values
            .filterIsInstance<CallGraphNode.IndirectCallHub>()
            .associate { hub -> hub.signatureText to (cg.graph.predecessors[hub.id]?.size ?: 0) }
    }
}
