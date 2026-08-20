@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.shared.WasmDisassemblyIndex

/**
 * One disassembly row: the instruction's module-absolute byte [offset]
 * (-1 when it could not be deduced) and its rendered WAT-style text.
 */
@JsExport
class DisasmRow(val offset: Int, val instruction: String)

/**
 * A function's code-section entry: code-section-local [index] and the
 * module-absolute byte range [start, end) of the full entry.
 */
@JsExport
class DisasmFunctionRange(val index: Int, val start: Int, val end: Int)

/**
 * JS-exported wrapper around the shared [WasmDisassemblyIndex] for the
 * VS Code CDP debug adapter's DAP `disassemble` implementation.
 *
 * Function bodies are parsed lazily on first access and cached by
 * code-section-local index, so stepping inside one function is lookup-only
 * (same design constraints as the IntelliJ Wasm tab: 50MB / 14k-function
 * modules, only visited functions are ever decoded).
 */
@JsExport
class WasmDisassembly internal constructor(private val index: WasmDisassemblyIndex) {

    private val cache = mutableMapOf<Int, Array<DisasmRow>>()

    /** Number of functions in the code section. */
    fun functionCount(): Int = index.codeEntries.size

    /** The entry range covering [moduleAbsoluteOffset], or null when outside the code section. */
    fun functionRangeForOffset(moduleAbsoluteOffset: Int): DisasmFunctionRange? {
        val i = index.functionIndexForOffset(moduleAbsoluteOffset)
        if (i < 0) return null
        return functionRange(i)
    }

    /** The entry range of the function at code-section-local [localIndex], or null when out of bounds. */
    fun functionRange(localIndex: Int): DisasmFunctionRange? {
        val entry = index.codeEntries.getOrNull(localIndex) ?: return null
        return DisasmFunctionRange(localIndex, entry.offset, entry.offset + entry.size)
    }

    /** Disassembly rows of the function at code-section-local [localIndex] (cached; empty on parse failure). */
    fun rowsForFunction(localIndex: Int): Array<DisasmRow> = cache.getOrPut(localIndex) {
        index.disassembleFunction(localIndex).map { DisasmRow(it.offset, it.instruction) }.toTypedArray()
    }
}

/**
 * Indexes [wasmBytes] for disassembly, or returns null when the bytes are
 * not a parseable wasm module.
 */
@JsExport
fun createWasmDisassembly(wasmBytes: ByteArray): WasmDisassembly? =
    WasmDisassemblyIndex.create(wasmBytes.toCommonByteBuffer())?.let { WasmDisassembly(it) }
