package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

/**
 * One core module of an executed wasm binary, indexed for the debugger.
 *
 * @property moduleId the id the runtime addresses this module by — always 0 for a
 *           plain core module, the `module` section index for a component.
 * @property disassembly per-function disassembly in this module's own offset space.
 * @property stackAnalyzer symbolic operand stack for the same offsets, or null when
 *           this module's sections do not support the reconstruction.
 */
class WasmDebugModule internal constructor(
    val moduleId: Int,
    val disassembly: WasmDisassemblyIndex,
    val stackAnalyzer: WasmStackShapeAnalyzer?,
)

/**
 * The core modules a wasm debug session can stop inside, indexed the way a
 * gdbstub addresses them.
 *
 * A plain core module is a single entry with id 0 and needs nothing special. A
 * component-model binary is a container: it embeds one verbatim core module per
 * `module` section, the runtime compiles each of them separately, and the stub
 * reports stops as an `(id, offset)` pair where the offset counts from the
 * addressed module's own magic — not from the start of the component file (see
 * [wasmOffsetForGdbPc]). Each module therefore needs its own index, which is what
 * this class holds: every embedded module is [CommonByteBuffer.slice]d out so the
 * offsets its disassembly reports are exactly the offsets the stub sends.
 *
 * Modules are indexed eagerly in [create] and the result is immutable, so the
 * instance can be queried from the debugger's event-loop and coroutine threads
 * without synchronization. Indexing is a section walk, not a body parse: bodies
 * are still parsed per query by [WasmDisassemblyIndex].
 */
class WasmDebugModules private constructor(
    /** By module id; a null entry is a module that failed to index, keeping later ids aligned. */
    private val modules: List<WasmDebugModule?>,
    /** True when the executed binary is a component and [modules] are its embedded core modules. */
    val isComponent: Boolean,
) {

    /** Number of addressable module ids, including any that failed to index. */
    val moduleCount: Int get() = modules.size

    /**
     * The module a stop at [offset] belongs to, or null when no indexed module has a
     * function body covering it.
     *
     * [moduleId] is the id the stub reported, or null when the translation path has
     * no notion of one (the legacy wasmtime JIT addrmap, and V8/CDP). A reported id
     * that has a body at [offset] always wins. Otherwise the offset is looked up in
     * every module and accepted only when exactly one matches — enough to keep
     * single-module binaries working with an unknown id, and to absorb a component
     * whose compiled module order does not match its `module` section order, without
     * ever guessing between two equally plausible modules.
     */
    fun moduleFor(moduleId: Int?, offset: Int): WasmDebugModule? {
        if (moduleId != null) {
            val addressed = modules.getOrNull(moduleId)
            if (addressed != null && addressed.disassembly.functionIndexForOffset(offset) >= 0) {
                return addressed
            }
        }
        var match: WasmDebugModule? = null
        for (module in modules) {
            if (module == null || module.disassembly.functionIndexForOffset(offset) < 0) continue
            if (match != null) return null // ambiguous: two modules could own this offset
            match = module
        }
        return match
    }

    companion object {
        /**
         * Indexes [wasmBuffer]: the module itself when it is a core module, every
         * embedded core module when it is a component.
         *
         * @return null when the bytes are neither a core module with code nor a
         *         component with at least one indexable core module.
         */
        fun create(wasmBuffer: CommonByteBuffer): WasmDebugModules? {
            val shared = wasmBuffer.duplicate()
            return when (WasmBinaryProviderSelector.select(shared.duplicate())) {
                is BinaryProviderType.WasmComponent -> createForComponent(shared)
                is BinaryProviderType.Wasm -> indexModule(shared, moduleId = 0)
                    ?.let { WasmDebugModules(listOf(it), isComponent = false) }
                else -> null
            }
        }

        private fun createForComponent(shared: CommonByteBuffer): WasmDebugModules? {
            val embedded = try {
                WasmComponentBinaryProvider(shared.duplicate()).modules
            } catch (_: Exception) {
                return null
            }
            if (embedded.isEmpty()) return null
            val modules = embedded.mapIndexed { moduleId, coreModule ->
                val slice = try {
                    // slice() translates coordinates, so offset 0 of the slice is the
                    // embedded module's magic — the base the stub's offsets count from.
                    shared.slice(coreModule.contentOffset, coreModule.contentSize)
                } catch (_: Exception) {
                    return@mapIndexed null
                }
                indexModule(slice, moduleId)
            }
            if (modules.all { it == null }) return null
            return WasmDebugModules(modules, isComponent = true)
        }

        private fun indexModule(buffer: CommonByteBuffer, moduleId: Int): WasmDebugModule? {
            val disassembly = WasmDisassemblyIndex.create(buffer) ?: return null
            if (disassembly.codeEntries.isEmpty()) return null
            // The stack analyzer is optional: a module can be disassembled even when
            // its operand stack cannot be reconstructed.
            return WasmDebugModule(moduleId, disassembly, WasmStackShapeAnalyzer.create(buffer))
        }
    }
}
