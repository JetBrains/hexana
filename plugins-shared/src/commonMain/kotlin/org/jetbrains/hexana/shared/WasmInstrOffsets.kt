package org.jetbrains.hexana.shared

import org.jetbrains.hexana.wasm.opcodes.AtomicOpCode
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode

/**
 * One entry in a linearised instruction sequence used for backward offset deduction.
 *
 * [isEndMarker] is true for the implicit `end` byte that closes a block/loop/if/else:
 * those must not inherit the block-start opcode's recorded offset.
 * [byteLen] is the byte length when the offset is not already recorded on the opcode, or
 * null when the real offset is carried by the opcode itself (i.e. an [OpCodeWithOffset]).
 * A value of -1 signals "unknown length" (prefixed opcodes like SIMD/atomics/GC).
 * [recordedOffset] is the offset carried by the opcode, or -1 if absent.
 */
class FlattenedInstr(
    val isEndMarker: Boolean,
    val byteLen: Int?,
    val recordedOffset: Int,
    var offset: Int = -1,
)

/**
 * Shared backward-pass offset deduction for Wasm singleton (zero-operand) opcodes.
 *
 * Singleton opcodes are shared object instances whose inherited offset field
 * is 0 and meaningless. This object reconstructs their true byte offsets by
 * walking the flattened instruction list in reverse.
 */
object WasmInstrOffsets {

    /**
     * Byte length of an opcode that carries no recorded offset, or null when
     * the offset is already recorded on the opcode itself (value-carrying classes) or
     * cannot be known (prefixed singletons -- SIMD/atomics/GC).
     */
    fun singletonByteLen(op: OpCode): Int? {
        val recorded = (op as? OpCodeWithOffset)?.offset?.takeIf { it > 0 }
        if (recorded != null) return null
        if (op is VectorOpCode || op is AtomicOpCode) return -1 // poison: multi-byte, length unknown
        return 1
    }

    /**
     * Backward pass assigning offsets to singletons; returns false when the result is
     * inconsistent (non-increasing offsets indicate a wrong byte-length assumption).
     *
     * [entryEnd] is the exclusive byte boundary of the function body
     * (entry.offset + entry.size). The function's closing `end` byte is at
     * entryEnd - 1.
     */
    fun deduceOffsets(flat: List<FlattenedInstr>, entryEnd: Int): Boolean {
        var next = entryEnd - 1 // the function's closing `end` byte
        for (i in flat.indices.reversed()) {
            val f = flat[i]
            // END markers reference the block opcode whose recorded offset is the block
            // START -- never use it for the end byte's position.
            val recorded = if (f.isEndMarker) null
            else f.recordedOffset.takeIf { it > 0 }
            f.offset = when {
                recorded != null -> recorded
                f.byteLen != null && f.byteLen > 0 && next > 0 -> next - f.byteLen
                else -> -1
            }
            next = f.offset
        }
        // Sanity: known offsets must be strictly increasing.
        var prev = -1
        for (f in flat) {
            if (f.offset < 0) continue
            if (f.offset <= prev) return false
            prev = f.offset
        }
        return true
    }
}
