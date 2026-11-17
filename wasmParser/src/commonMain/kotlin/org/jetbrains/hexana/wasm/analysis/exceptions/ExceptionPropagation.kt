package org.jetbrains.hexana.wasm.analysis.exceptions

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.parseCodeEntry

/**
 * The exceptions a function can throw that are NOT caught within it — its
 * static tag set plus a "dynamic" flag (an uncaught `throw_ref`, an unguarded
 * `call_indirect`, or an unguarded call to an imported function, all of which
 * may throw something not statically known).
 */
data class ThrownSet(val tags: Set<Int>, val dynamic: Boolean) {
    val isEmpty: Boolean get() = tags.isEmpty() && !dynamic
    companion object { val EMPTY = ThrownSet(emptySet(), false) }
}

/** A function that escapes exceptions, with the effective set and whether it is an exported entry. */
data class ThrowingFunction(val funcIdx: Int, val name: String?, val thrown: ThrownSet, val isExportedEntry: Boolean)

/**
 * Interprocedural exception propagation (E2 of the exception-flow spec). A
 * function's effective thrown set = its local escapes ∪ each callee's
 * effective thrown set minus what a try_table around that call catches. Solved
 * as a monotone fixed point over the call graph. The effective thrown set of
 * an exported function is its "panic surface": exceptions that escape the
 * module to the host from that entry point.
 */
class ExceptionPropagation internal constructor(
    private val effective: Map<Int, ThrownSet>,
    private val exportedEntries: Set<Int>,
    private val names: Map<Int, String>,
) {
    /** Effective uncaught-throw set for [funcIdx]. */
    fun thrownBy(funcIdx: Int): ThrownSet = effective[funcIdx] ?: ThrownSet.EMPTY

    /**
     * The module's panic surface: exported entry points whose effective thrown
     * set is non-empty, i.e. exceptions that provably escape the module to the
     * host. Sorted by funcidx for deterministic output.
     */
    fun uncaughtAtEntry(): List<ThrowingFunction> =
        exportedEntries
            .mapNotNull { idx ->
                val t = effective[idx] ?: return@mapNotNull null
                if (t.isEmpty) null else ThrowingFunction(idx, names[idx], t, isExportedEntry = true)
            }
            .sortedBy { it.funcIdx }
}

object ExceptionPropagationBuilder {
    /**
     * @param imports may-throw policy for imported functions: an unguarded
     *   call to an import contributes a dynamic (host) throw. Kept as its own
     *   category so callers can label host-origin throws honestly.
     */
    fun build(buffer: CommonByteBuffer, module: WasmModule, names: Map<Int, String> = emptyMap()): ExceptionPropagation {
        val importFuncCount = countImportedFunctions(buffer, module)
        val codeOffsets = module.codeSection?.let { with(it) { buffer.scanOffsets() } } ?: emptyList()

        // Per defined function: its FunctionEffect (local escapes + guarded calls).
        val effects = HashMap<Int, org.jetbrains.hexana.wasm.analysis.exceptions.FunctionEffect>(codeOffsets.size * 2)
        for (i in codeOffsets.indices) {
            val funcIdx = importFuncCount + i
            val record = buffer.parseCodeEntry(codeOffsets[i].offset)
            effects[funcIdx] = ExceptionFlow.functionEffect(record.opCodes)
        }

        // Seed each function with its local escapes.
        val thrown = HashMap<Int, MutableThrown>()
        for ((funcIdx, effect) in effects) {
            thrown[funcIdx] = MutableThrown(effect.localTags.toHashSet(), effect.localDynamic || effect.hasUnguardedIndirectOrImportRisk)
        }

        // Fixed point: a callee's escaping tags flow up through any call not
        // guarded by a try_table catching them; a catch_all guarded call lets
        // nothing through. Imported callees (funcIdx < importFuncCount) have no
        // body — an unguarded call to one contributes a dynamic (host) throw.
        var changed = true
        while (changed) {
            changed = false
            for ((funcIdx, effect) in effects) {
                val cur = thrown.getValue(funcIdx)
                for (call in effect.calls) {
                    if (call.underCatchAll) continue
                    val calleeThrown = thrown[call.calleeFuncIdx]
                    if (calleeThrown == null) {
                        // Imported (bodiless) callee: may throw an unknown host exception.
                        if (call.calleeFuncIdx < importFuncCount && cur.addDynamic()) changed = true
                        continue
                    }
                    for (tag in calleeThrown.tags) {
                        if (tag !in call.caughtTags && cur.tags.add(tag)) changed = true
                    }
                    if (calleeThrown.dynamic && cur.addDynamic()) changed = true
                }
            }
        }

        val exported = exportedFunctionIndices(buffer, module)
        val effectiveImmutable = thrown.mapValues { ThrownSet(it.value.tags.toSet(), it.value.dynamic) }
        return ExceptionPropagation(effectiveImmutable, exported, names)
    }

    private class MutableThrown(val tags: HashSet<Int>, var dynamic: Boolean) {
        fun addDynamic(): Boolean = if (!dynamic) { dynamic = true; true } else false
    }

    private fun countImportedFunctions(buffer: CommonByteBuffer, module: WasmModule): Int {
        val section = module.importSection ?: return 0
        return with(section) { buffer.getImportRecords() }.count { it.type is WasmTypeIndexExternalType }
    }

    private fun exportedFunctionIndices(buffer: CommonByteBuffer, module: WasmModule): Set<Int> {
        val section = module.exportSection ?: return emptySet()
        val out = HashSet<Int>()
        for (export in with(section) { buffer.scan() }) {
            if (export is WasmFunctionExportRecordEntry) out.add(export.index.toInt())
        }
        return out
    }
}
