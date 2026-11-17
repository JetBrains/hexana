@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.shared.WasmDebugModules
import org.jetbrains.hexana.shared.wasmModuleIdForGdbPc
import org.jetbrains.hexana.shared.wasmOffsetForGdbPc

/**
 * The core modules a wasm debug session can stop inside, each with its own
 * disassembly and operand-stack analysis.
 *
 * A plain core module is a single module with id 0, so this is a pass-through for
 * everything except components. A component-model binary is a container: the
 * runtime compiles each embedded core module separately and the stub addresses a
 * stop as `(module id, offset within that module)`, counting from the embedded
 * module's own magic rather than from the start of the component file. Resolving
 * against the whole file therefore finds nothing — the pair has to be resolved
 * against the right embedded module, which is what this class does.
 *
 * The per-module [WasmDisassembly] and [WasmStackShape] wrappers are created once
 * per module and reused, so their per-function caches survive across stops.
 */
@JsExport
class WasmModuleIndex internal constructor(private val modules: WasmDebugModules) {

    private val disassemblies = mutableMapOf<Int, WasmDisassembly>()
    private val stackShapes = mutableMapOf<Int, WasmStackShape>()

    /** Number of core modules the binary can stop in — 1 unless it is a component. */
    val moduleCount: Int get() = modules.moduleCount

    /** Whether the executed binary is a component, so offsets are per embedded core module. */
    val isComponent: Boolean get() = modules.isComponent

    /**
     * The disassembly of the core module owning [offset], or null when no indexed
     * module has a function body there.
     *
     * [moduleId] is the id the stub reported; pass a negative value when the id is
     * unknown (V8/CDP and the legacy wasmtime JIT addrmap report none) and the
     * module will be deduced from the offset alone. All offsets passed to the
     * returned [WasmDisassembly] are in that module's own offset space — the same
     * one the stub reports.
     */
    fun disassemblyFor(moduleId: Int, offset: Int): WasmDisassembly? {
        val module = modules.moduleFor(moduleId.takeIf { it >= 0 }, offset) ?: return null
        return disassemblies.getOrPut(module.moduleId) { WasmDisassembly(module.disassembly) }
    }

    /**
     * The operand-stack analysis of the core module owning [offset], or null when no
     * indexed module has a body there or that module's stack cannot be
     * reconstructed. Same [moduleId] convention as [disassemblyFor].
     */
    fun stackShapeFor(moduleId: Int, offset: Int): WasmStackShape? {
        val module = modules.moduleFor(moduleId.takeIf { it >= 0 }, offset) ?: return null
        val analyzer = module.stackAnalyzer ?: return null
        return stackShapes.getOrPut(module.moduleId) { WasmStackShape(analyzer) }
    }
}

/**
 * Indexes [wasmBytes] for disassembly and operand-stack reconstruction — the module
 * itself when it is a core module, every embedded core module when it is a
 * component.
 *
 * @return null when the bytes are neither a core module with a code section nor a
 *         component with at least one indexable core module.
 */
@JsExport
fun createWasmModuleIndex(wasmBytes: ByteArray): WasmModuleIndex? =
    WasmDebugModules.create(wasmBytes.toCommonByteBuffer())?.let { WasmModuleIndex(it) }

/**
 * Translates an LLDB program counter reported over a wasm gdbstub connection into a
 * byte offset inside the core module the stop is in.
 *
 * Accepts the DAP `instructionPointerReference` form (`"0x4000000000000052"`) as
 * well as a plain decimal string. Returns -1 when [pc] cannot be parsed.
 *
 * The offset is relative to that module's own start, so for a component-model
 * binary it must be resolved against the embedded module named by
 * [wasmModuleIdForGdbPcString] rather than against the component file.
 */
@JsExport
fun wasmOffsetForGdbPcString(pc: String): Int =
    parseGdbPc(pc)?.let { wasmOffsetForGdbPc(it) } ?: -1

/**
 * The id of the core module an LLDB wasm gdbstub PC points into (bits 32–47),
 * which a component's stops need in order to pick the right embedded module.
 * Always 0 for a plain core module. Returns -1 when [pc] cannot be parsed.
 */
@JsExport
fun wasmModuleIdForGdbPcString(pc: String): Int =
    parseGdbPc(pc)?.let { wasmModuleIdForGdbPc(it) } ?: -1

/** Parses a hex (`0x…`) or decimal PC string, or null when it is neither. */
private fun parseGdbPc(pc: String): ULong? {
    val text = pc.trim()
    return if (text.startsWith("0x", ignoreCase = true)) {
        text.substring(2).toULongOrNull(16)
    } else {
        text.toULongOrNull()
    }
}
