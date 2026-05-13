package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.binaryDocumentOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates [prefixedOpcodeLength] against representative encodings for each
 * prefix family (0xFB GC, 0xFC bulk memory, 0xFD SIMD, 0xFE atomics). Without
 * proper coverage these prefixed instructions desync the cursor on any binary
 * that uses them — Skiko (graphics) makes heavy use of SIMD.
 */
class OpcodeLengthsTest {

    private fun bytes(vararg b: Int) =
        binaryDocumentOf(b.map { it.toByte() }.toByteArray()).byteSource()

    // ----- 0xFD SIMD ------------------------------------------------------

    @Test
    fun simd_v128_const_is_18_bytes() {
        // 0xFD 0x0C + 16 raw bytes
        val s = bytes(0xFD, 0x0C, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        assertEquals(18, prefixedOpcodeLength(0xFD, s, 0L))
    }

    @Test
    fun simd_i8x16_shuffle_is_18_bytes() {
        // 0xFD 0x0D + 16 lane idx bytes
        val s = bytes(0xFD, 0x0D, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(18, prefixedOpcodeLength(0xFD, s, 0L))
    }

    @Test
    fun simd_v128_load_is_4_bytes_with_natural_align() {
        // 0xFD 0x00 + align=2 (uleb 1 byte) + offset=0 (uleb 1 byte)
        val s = bytes(0xFD, 0x00, 0x02, 0x00)
        assertEquals(4, prefixedOpcodeLength(0xFD, s, 0L))
    }

    @Test
    fun simd_lane_extract_is_3_bytes() {
        // 0xFD 0x15 (i8x16.extract_lane_s) + 1 lane byte
        val s = bytes(0xFD, 0x15, 0x07)
        assertEquals(3, prefixedOpcodeLength(0xFD, s, 0L))
    }

    @Test
    fun simd_v128_load8_lane_is_memarg_plus_lane() {
        // 0xFD 0x54 + align=0 + offset=0 + lane=3 → 1+1+1+1+1 = 5
        val s = bytes(0xFD, 0x54, 0x00, 0x00, 0x03)
        assertEquals(5, prefixedOpcodeLength(0xFD, s, 0L))
    }

    @Test
    fun simd_arithmetic_no_immediate() {
        // 0xFD 0x6E (i8x16.add) — no immediate
        val s = bytes(0xFD, 0x6E)
        assertEquals(2, prefixedOpcodeLength(0xFD, s, 0L))
    }

    // ----- 0xFE Atomics --------------------------------------------------

    @Test
    fun atomic_fence_is_3_bytes() {
        // 0xFE 0x03 + 1 reserved byte
        val s = bytes(0xFE, 0x03, 0x00)
        assertEquals(3, prefixedOpcodeLength(0xFE, s, 0L))
    }

    @Test
    fun atomic_load_is_memarg() {
        // 0xFE 0x10 (i32.atomic.load) + align=2 + offset=0 → 1+1+1+1 = 4
        val s = bytes(0xFE, 0x10, 0x02, 0x00)
        assertEquals(4, prefixedOpcodeLength(0xFE, s, 0L))
    }

    @Test
    fun atomic_notify_is_memarg() {
        // 0xFE 0x00 (memory.atomic.notify) + align + offset
        val s = bytes(0xFE, 0x00, 0x02, 0x00)
        assertEquals(4, prefixedOpcodeLength(0xFE, s, 0L))
    }

    // ----- 0xFC Bulk memory + sat-trunc ---------------------------------

    @Test
    fun bulkmem_sat_trunc_no_imm() {
        // 0xFC 0x00 (i32.trunc_sat_f32_s)
        val s = bytes(0xFC, 0x00)
        assertEquals(2, prefixedOpcodeLength(0xFC, s, 0L))
    }

    @Test
    fun bulkmem_memory_copy_is_4_bytes() {
        // 0xFC 0x0A + memidx_dst=0 + memidx_src=0
        val s = bytes(0xFC, 0x0A, 0x00, 0x00)
        assertEquals(4, prefixedOpcodeLength(0xFC, s, 0L))
    }

    @Test
    fun bulkmem_memory_fill_is_3_bytes() {
        // 0xFC 0x0B + memidx=0
        val s = bytes(0xFC, 0x0B, 0x00)
        assertEquals(3, prefixedOpcodeLength(0xFC, s, 0L))
    }

    @Test
    fun bulkmem_data_drop_is_3_bytes() {
        // 0xFC 0x09 + dataidx=5
        val s = bytes(0xFC, 0x09, 0x05)
        assertEquals(3, prefixedOpcodeLength(0xFC, s, 0L))
    }

    // ----- 0xFB GC -------------------------------------------------------

    @Test
    fun gc_struct_new_is_3_bytes() {
        // 0xFB 0x00 + typeidx
        val s = bytes(0xFB, 0x00, 0x05)
        assertEquals(3, prefixedOpcodeLength(0xFB, s, 0L))
    }

    @Test
    fun gc_struct_get_is_4_bytes() {
        // 0xFB 0x02 + typeidx + fieldidx
        val s = bytes(0xFB, 0x02, 0x05, 0x01)
        assertEquals(4, prefixedOpcodeLength(0xFB, s, 0L))
    }

    @Test
    fun gc_array_len_no_imm() {
        // 0xFB 0x0F
        val s = bytes(0xFB, 0x0F)
        assertEquals(2, prefixedOpcodeLength(0xFB, s, 0L))
    }

    @Test
    fun gc_extern_convert_any_no_imm() {
        // 0xFB 0x1B
        val s = bytes(0xFB, 0x1B)
        assertEquals(2, prefixedOpcodeLength(0xFB, s, 0L))
    }

    @Test
    fun gc_ref_test_with_shorthand_heaptype() {
        // 0xFB 0x14 + heap-type byte 0x70 (funcref shorthand)
        val s = bytes(0xFB, 0x14, 0x70)
        assertEquals(3, prefixedOpcodeLength(0xFB, s, 0L))
    }

    @Test
    fun gc_br_on_cast_with_shorthand_heaptypes() {
        // 0xFB 0x18 + flags + labelidx + src heaptype + dst heaptype
        val s = bytes(0xFB, 0x18, 0x03, 0x02, 0x70, 0x6F)
        assertEquals(6, prefixedOpcodeLength(0xFB, s, 0L))
    }

    @Test
    fun gc_array_new_fixed_is_4_bytes() {
        // 0xFB 0x08 + typeidx + count
        val s = bytes(0xFB, 0x08, 0x02, 0x04)
        assertEquals(4, prefixedOpcodeLength(0xFB, s, 0L))
    }

    // ----- Multi-byte sub-opcode (rare but possible) --------------------

    @Test
    fun simd_with_multibyte_subopcode_advances_correctly() {
        // 0xFD + sub-op encoded as 2-byte ULEB128 (high bit set on first byte).
        // 0x80 0x02 → 256. Sub-op 256 is past our SIMD coverage but the prefix
        // helper should still advance past prefix + 2-byte sub-opcode = 3 bytes.
        val s = bytes(0xFD, 0x80, 0x02)
        assertEquals(3, prefixedOpcodeLength(0xFD, s, 0L))
    }
}
