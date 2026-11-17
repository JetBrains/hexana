package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.diff.match.FnvHash
import org.jetbrains.hexana.wasm.analysis.diff.match.FunctionDescriptor
import org.jetbrains.hexana.wasm.analysis.diff.match.FunctionMatchResult
import org.jetbrains.hexana.wasm.analysis.diff.match.FunctionMatcher
import org.jetbrains.hexana.wasm.analysis.diff.match.FunctionNormalizer
import org.jetbrains.hexana.wasm.analysis.diff.match.NormalizerContext
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord

/**
 * Bridges two [WasmBinaryProvider]s to the pure [FunctionMatcher]: builds a
 * renumber-invariant content hash + fingerprint for every defined function on
 * each side (resolving call targets to names so calls discriminate), wires up the
 * call graph, and returns the cross-module pairing (IDENTICAL / MOVED / MODIFIED
 * + ADDED/REMOVED).
 */
public object WasmFunctionMatchProvider {

    private const val ANON_CALLEE = 0L

    public fun match(
        left: WasmBinaryProvider,
        right: WasmBinaryProvider,
        leftMap: WasmSymbolMap? = null,
        rightMap: WasmSymbolMap? = null,
    ): FunctionMatchResult? {
        if (!left.canProvideWasmModule() || !right.canProvideWasmModule()) return null
        val normalizer = FunctionNormalizer()
        val leftSide = buildSide(left, normalizer, leftMap)
        val rightSide = buildSide(right, normalizer, rightMap)
        // Round-2 re-hasher: re-normalize a function with matched callees substituted by
        // their pair tokens. Uses the same shared normalizer so tags stay comparable.
        val rehash: (Boolean, Int, (Int) -> Long?) -> Long = { isLeft, pos, pairTokenOfCallee ->
            (if (isLeft) leftSide else rightSide).rehash(pos, pairTokenOfCallee, normalizer)
        }
        return FunctionMatcher.match(leftSide.descriptors, rightSide.descriptors, rehash)
    }

    /** Per-module matcher inputs, retaining the bodies + signatures round-2 re-hashing needs. */
    private class Side(
        val descriptors: List<FunctionDescriptor>,
        private val opCodes: List<List<OpCode>>,
        private val seeds: LongArray,
        private val paramCounts: IntArray,
        private val resultCounts: IntArray,
        private val round1Ctx: NormalizerContext,
    ) {
        fun rehash(pos: Int, pairTokenOfCallee: (Int) -> Long?, normalizer: FunctionNormalizer): Long {
            val ctx = object : NormalizerContext {
                override fun calleeToken(funcIdx: Int): Long =
                    pairTokenOfCallee(funcIdx) ?: round1Ctx.calleeToken(funcIdx)
                override fun typeArity(typeIdx: Int): Int = round1Ctx.typeArity(typeIdx)
                override fun globalToken(globalIdx: Int): Long = round1Ctx.globalToken(globalIdx)
            }
            return normalizer.normalize(opCodes[pos], paramCounts[pos], resultCounts[pos], seeds[pos], ctx).hash
        }
    }

    private fun buildSide(p: WasmBinaryProvider, normalizer: FunctionNormalizer, symbolMap: WasmSymbolMap?): Side {
        val resolver = WasmSymbolResolver(p, symbolMap = symbolMap)
        val importCount = p.importFuncCount
        val graph = p.callGraphResult?.graph
        val ctx = object : NormalizerContext {
            override fun calleeToken(funcIdx: Int): Long =
                resolver.resolve(SymbolKind.FUNCTION, funcIdx)?.let { FnvHash().update(it).value() } ?: ANON_CALLEE
            override fun typeArity(typeIdx: Int): Int = 0
            override fun globalToken(globalIdx: Int): Long = 0L
        }

        val signatures = p.functions
        val records = p.codeRecords
        val opCodes = ArrayList<List<OpCode>>(records.size)
        val seeds = LongArray(records.size)
        val paramCounts = IntArray(records.size)
        val resultCounts = IntArray(records.size)
        val descriptors = records.mapIndexed { codeIdx, record ->
            val globalIdx = importCount + codeIdx
            val (seed, params, results) = signatureSeed(signatures.getOrNull(codeIdx))
            seeds[codeIdx] = seed
            paramCounts[codeIdx] = params
            resultCounts[codeIdx] = results
            opCodes.add(record.opCodes)
            val normalized = normalizer.normalize(record.opCodes, params, results, seed, ctx)
            FunctionDescriptor(
                codeIdx = codeIdx,
                globalIdx = globalIdx,
                resolvedName = resolver.resolve(SymbolKind.FUNCTION, globalIdx),
                round1Hash = normalized.hash,
                fingerprint = normalized.fingerprint,
                callees = graph?.successors?.get(globalIdx).orEmpty(),
                callers = graph?.predecessors?.get(globalIdx).orEmpty(),
            )
        }
        return Side(descriptors, opCodes, seeds, paramCounts, resultCounts, ctx)
    }

    /** (signatureSeed, paramCount, resultCount) for a function's type. */
    private fun signatureSeed(sig: WasmTypeRecord?): Triple<Long, Int, Int> {
        if (sig is WasmSectionFunctionTypeRecord) {
            val h = FnvHash()
            sig.params.forEach { h.update(it.toString()) }
            h.update("->")
            sig.retTypes.forEach { h.update(it.toString()) }
            return Triple(h.value(), sig.params.size, sig.retTypes.size)
        }
        return Triple(0L, 0, 0)
    }
}
