package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.ByteSource

/**
 * Encoded byte length for opcodes whose first byte is a multi-byte prefix:
 * `0xFB` (GC), `0xFC` (bulk memory + saturating truncation), `0xFD` (SIMD),
 * `0xFE` (atomics / threads).
 *
 * Each prefix family encodes as `prefix_byte + sub_opcode_uleb128 + immediates`.
 * This module dispatches on the sub-opcode to compute the immediate-bytes
 * length, returning the total instruction length (prefix + sub + imms).
 *
 * Coverage is "skip-correct" — the returned length advances the cursor past
 * the instruction even if Hexana doesn't yet render the mnemonic. Sub-opcodes
 * outside the documented range fall back to "prefix + sub-opcode bytes only"
 * with no immediate accounting; subsequent rendering will desync at that
 * point but recover at the next op-instance with a real offset anchor.
 */
internal fun prefixedOpcodeLength(prefix: Int, source: ByteSource, atOffset: Long): Int {
    val sub = decodeULEB128(source, atOffset + 1L)
    val baseLen = 1 + sub.byteCount
    val immStart = atOffset + baseLen
    val subOp = sub.value.toInt()
    val immLen = when (prefix) {
        0xFC -> bulkMemoryImmLength(source, immStart, subOp)
        0xFD -> simdImmLength(source, immStart, subOp)
        0xFE -> atomicImmLength(source, immStart, subOp)
        0xFB -> gcImmLength(source, immStart, subOp)
        else -> 0
    }
    return baseLen + immLen
}

/**
 * Length of a heap-type encoding starting at [atOffset]. The heap type is
 * either a single shorthand byte (heaptype shorthand 0x69–0x74, `funcref`,
 * `externref`, etc.) or an SLEB128 s33 type index. Used by GC ref.cast /
 * ref.test / br_on_cast variants.
 */
private fun heapTypeLength(source: ByteSource, atOffset: Long): Int {
    if (atOffset >= source.length) return 1
    val b = source.get(atOffset).toInt() and 0xFF
    return if (b in 0x69..0x74) 1 else decodeSLEB128(source, atOffset).byteCount
}

private fun memArgLength(source: ByteSource, atOffset: Long): Int {
    val a = decodeULEB128(source, atOffset)
    val b = decodeULEB128(source, atOffset + a.byteCount)
    return a.byteCount + b.byteCount
}

// -----------------------------------------------------------------------
// 0xFC — Bulk memory (0x08–0x11) + non-trapping float-to-int sat (0x00–0x07)
// -----------------------------------------------------------------------
private fun bulkMemoryImmLength(source: ByteSource, atOffset: Long, subOp: Int): Int {
    return when (subOp) {
        in 0..7 -> 0                                   // i32/i64.trunc_sat_f32/f64.s/u — no imm
        8 -> uleb(source, atOffset).byteCount + uleb(source, atOffset + uleb(source, atOffset).byteCount).byteCount // memory.init: dataidx + memidx
        9 -> uleb(source, atOffset).byteCount          // data.drop: dataidx
        10 -> uleb(source, atOffset).byteCount + uleb(source, atOffset + uleb(source, atOffset).byteCount).byteCount // memory.copy: 2 memidx
        11 -> uleb(source, atOffset).byteCount         // memory.fill: memidx
        12 -> uleb(source, atOffset).byteCount + uleb(source, atOffset + uleb(source, atOffset).byteCount).byteCount // table.init
        13 -> uleb(source, atOffset).byteCount         // elem.drop
        14 -> uleb(source, atOffset).byteCount + uleb(source, atOffset + uleb(source, atOffset).byteCount).byteCount // table.copy
        15, 16, 17 -> uleb(source, atOffset).byteCount // table.grow / table.size / table.fill
        else -> 0
    }
}

// -----------------------------------------------------------------------
// 0xFD — SIMD (Vector spec)
// -----------------------------------------------------------------------
private fun simdImmLength(source: ByteSource, atOffset: Long, subOp: Int): Int {
    return when (subOp) {
        // Memory load/store: MemArg (2 ULEB)
        in 0x00..0x0B -> memArgLength(source, atOffset)
        // v128.const: 16 raw bytes
        0x0C -> 16
        // i8x16.shuffle: 16 lane idx bytes
        0x0D -> 16
        // Lane extract/replace family: 1 lane idx byte each
        in 0x15..0x22 -> 1
        // v128.load{8,16,32,64}_lane / v128.store*_lane (0x54–0x5B): MemArg + 1 lane byte
        in 0x54..0x5B -> memArgLength(source, atOffset) + 1
        // v128.load32_zero (0x5C), v128.load64_zero (0x5D): MemArg only
        0x5C, 0x5D -> memArgLength(source, atOffset)
        // Everything else: vector arithmetic / comparison / conversion — no immediate
        else -> 0
    }
}

// -----------------------------------------------------------------------
// 0xFE — Atomics (Threads proposal)
// -----------------------------------------------------------------------
private fun atomicImmLength(source: ByteSource, atOffset: Long, subOp: Int): Int {
    return when (subOp) {
        // atomic.fence: 1 reserved byte
        0x03 -> 1
        // memory.atomic.notify, memory.atomic.wait32/64: MemArg
        0x00, 0x01, 0x02 -> memArgLength(source, atOffset)
        // All the rmw / load / store atomic ops: MemArg
        else -> memArgLength(source, atOffset)
    }
}

// -----------------------------------------------------------------------
// 0xFB — GC (proposal merged in WebAssembly 3.0)
// -----------------------------------------------------------------------
private fun gcImmLength(source: ByteSource, atOffset: Long, subOp: Int): Int {
    return when (subOp) {
        // struct.new / struct.new_default / array.new / array.new_default /
        // array.get* / array.set / array.fill: 1 typeidx ULEB
        0x00, 0x01, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x10 -> uleb(source, atOffset).byteCount
        // struct.get / struct.get_s / struct.get_u / struct.set: 2 ULEB
        // (typeidx + fieldidx)
        0x02, 0x03, 0x04, 0x05 -> {
            val a = uleb(source, atOffset)
            a.byteCount + uleb(source, atOffset + a.byteCount).byteCount
        }
        // array.new_fixed / array.new_data / array.new_elem / array.copy /
        // array.init_data / array.init_elem: 2 ULEB (typeidx + count|dataidx|elemidx|src)
        0x08, 0x09, 0x0A, 0x11, 0x12, 0x13 -> {
            val a = uleb(source, atOffset)
            a.byteCount + uleb(source, atOffset + a.byteCount).byteCount
        }
        // array.len / any.convert_extern / extern.convert_any / ref.i31 /
        // i31.get_s / i31.get_u: no immediate
        0x0F, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> 0
        // ref.test / ref.test_null / ref.cast / ref.cast_null: 1 heap type
        0x14, 0x15, 0x16, 0x17 -> heapTypeLength(source, atOffset)
        // br_on_cast / br_on_cast_fail: 1 flags byte + labelidx ULEB +
        // src heaptype + dst heaptype
        0x18, 0x19 -> {
            val flagsLen = 1
            val label = uleb(source, atOffset + flagsLen)
            val srcStart = atOffset + flagsLen + label.byteCount
            val srcLen = heapTypeLength(source, srcStart)
            val dstLen = heapTypeLength(source, srcStart + srcLen)
            flagsLen + label.byteCount + srcLen + dstLen
        }
        else -> 0
    }
}

private fun uleb(source: ByteSource, atOffset: Long) = decodeULEB128(source, atOffset)
