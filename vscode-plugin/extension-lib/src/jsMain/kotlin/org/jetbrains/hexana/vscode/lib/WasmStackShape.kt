@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.shared.WasmStackShapeAnalyzer
import org.jetbrains.hexana.shared.WasmStackShapeResult
import org.jetbrains.hexana.shared.decodeWasmValueHex
import org.jetbrains.hexana.shared.parseRawPacketResponse

/**
 * A symbolic operand-stack slot: wasm value type, human-readable origin,
 * and — when the value provably still mirrors a local or global — the
 * index usable with `qWasmLocal`/`qWasmGlobal` (-1 when absent).
 */
@JsExport
class SymbolicStackSlot(
    val type: String,
    val provenance: String,
    val localRef: Int,
    val globalRef: Int,
)

/**
 * Result of [WasmStackShape.stackAt]: [slots] bottom-to-top when the
 * reconstruction succeeded, otherwise null with [unavailableReason] set.
 */
@JsExport
class StackShapeResult(
    val slots: Array<SymbolicStackSlot>?,
    val unavailableReason: String?,
)

/**
 * JS-exported wrapper around the shared [WasmStackShapeAnalyzer]: statically
 * reconstructs the wasm operand stack (types + provenance) at an instruction
 * offset. The concrete values are read separately via gdbstub raw packets.
 */
@JsExport
class WasmStackShape internal constructor(private val analyzer: WasmStackShapeAnalyzer) {

    /** Stack state BEFORE the instruction at module-absolute [offset] executes. */
    fun stackAt(offset: Int): StackShapeResult =
        when (val result = analyzer.stackAt(offset) { null }) {
            is WasmStackShapeResult.Ok -> StackShapeResult(
                result.slots.map {
                    SymbolicStackSlot(it.type, it.provenance, it.localRef ?: -1, it.globalRef ?: -1)
                }.toTypedArray(),
                null,
            )
            is WasmStackShapeResult.Unavailable -> StackShapeResult(null, result.reason)
        }
}

/** Indexes [wasmBytes] for stack-shape analysis, or null when not a parseable core module. */
@JsExport
fun createWasmStackShape(wasmBytes: ByteArray): WasmStackShape? =
    WasmStackShapeAnalyzer.create(wasmBytes.toCommonByteBuffer())?.let { WasmStackShape(it) }

/**
 * Decodes a gdbstub stack/local value hex payload (little-endian, 4 bytes for
 * i32/f32, 8 for i64/f64) per the wasm value type from the static analyzer.
 */
@JsExport
fun decodeWasmStackValueHex(hex: String, typeDisplay: String): String? =
    decodeWasmValueHex(hex, typeDisplay)

/**
 * Extracts the hex payload from LLDB `process plugin packet send` console
 * output ("response: <hex>"); null on empty or absent response.
 */
@JsExport
fun parseRawPacketOutput(lines: Array<String>): String? =
    parseRawPacketResponse(lines.toList())
