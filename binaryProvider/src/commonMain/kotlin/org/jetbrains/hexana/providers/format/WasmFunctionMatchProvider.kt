package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.diff.match.FnvHash
import org.jetbrains.hexana.wasm.analysis.diff.match.FunctionDescriptor
import org.jetbrains.hexana.wasm.analysis.diff.match.FunctionMatchResult
import org.jetbrains.hexana.wasm.analysis.diff.match.FunctionMatcher
import org.jetbrains.hexana.wasm.analysis.diff.match.FunctionNormalizer
import org.jetbrains.hexana.wasm.analysis.diff.match.NormalizerContext
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

    public fun match(left: WasmBinaryProvider, right: WasmBinaryProvider): FunctionMatchResult? {
        if (!left.canProvideWasmModule() || !right.canProvideWasmModule()) return null
        val normalizer = FunctionNormalizer()
        return FunctionMatcher.match(descriptors(left, normalizer), descriptors(right, normalizer))
    }

    private fun descriptors(p: WasmBinaryProvider, normalizer: FunctionNormalizer): List<FunctionDescriptor> {
        val resolver = WasmSymbolResolver(p)
        val importCount = p.importFuncCount
        val graph = p.callGraphResult?.graph
        val ctx = object : NormalizerContext {
            override fun calleeToken(funcIdx: Int): Long =
                resolver.resolve(SymbolKind.FUNCTION, funcIdx)?.let { FnvHash().update(it).value() } ?: ANON_CALLEE
            override fun typeArity(typeIdx: Int): Int = 0
            override fun globalToken(globalIdx: Int): Long = 0L
        }

        val signatures = p.functions
        return p.codeRecords.mapIndexed { codeIdx, record ->
            val globalIdx = importCount + codeIdx
            val (seed, params, results) = signatureSeed(signatures.getOrNull(codeIdx))
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
