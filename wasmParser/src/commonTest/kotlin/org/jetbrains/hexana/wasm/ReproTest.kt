package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repro for func 25400: struct_set after if-block with ref_null/struct_new.
 *
 * Bytes (from disassembly, base 0x4fcca6):
 *   20 00                local_get 0
 *   d1                   ref_is_null
 *   04 40                if Empty
 *     23 81 33           global_get 6529
 *     23 a8 3a           global_get 7464
 *     23 a7 d4 01        global_get 27175
 *     41 00              i32_const 0
 *     d0 98 c3 00        ref_null Concrete(8600)
 *     fb 00 9d 43        struct_new 8605
 *     21 00              local_set 0
 *     0b                 end
 *   20 00                local_get 0
 *   20 01                local_get 1
 *   fb 05 9d 43 04       struct_set 8605 4
 *   01                   nop
 *   20 00                local_get 0
 *   0f                   return
 *   0b                   end
 *
 * Reported failure: parser reaches consumeGCOpCode with offset=0x4FCCC8
 * and opcode=0x43 (which is mid-ULEB128 of the typeidx).
 */
class ReproTest {
    @Test
    fun `repro - struct_set after if-block`() {
        val bytes = byteArrayOf(
            // local_get 0
            0x20, 0x00,
            // ref_is_null
            0xd1.toByte(),
            // if Empty
            0x04, 0x40,
            // global_get 6529
            0x23, 0x81.toByte(), 0x33,
            // global_get 7464
            0x23, 0xa8.toByte(), 0x3a,
            // global_get 27175
            0x23, 0xa7.toByte(), 0xd4.toByte(), 0x01,
            // i32_const 0
            0x41, 0x00,
            // ref_null Concrete(8600)
            0xd0.toByte(), 0x98.toByte(), 0xc3.toByte(), 0x00,
            // struct_new 8605
            0xfb.toByte(), 0x00, 0x9d.toByte(), 0x43,
            // local_set 0
            0x21, 0x00,
            // end (of if)
            0x0b,
            // local_get 0
            0x20, 0x00,
            // local_get 1
            0x20, 0x01,
            // struct_set 8605 4
            0xfb.toByte(), 0x05, 0x9d.toByte(), 0x43, 0x04,
            // nop
            0x01,
            // local_get 0
            0x20, 0x00,
            // return
            0x0f,
            // end
            0x0b,
        )

        val buf = bytes.toCommonByteBuffer()
        val ops = buf.parseOpCodes()

        val structSet = ops.filterIsInstance<OpCodes.GC.StructSet>().singleOrNull()
        assertTrue(structSet != null, "struct_set must be parsed; got ${ops.map { it::class.simpleName }}")
        assertEquals(8605u, structSet.typeIndex, "type index")
        assertEquals(4u, structSet.fieldIndex, "field index")
    }
}
