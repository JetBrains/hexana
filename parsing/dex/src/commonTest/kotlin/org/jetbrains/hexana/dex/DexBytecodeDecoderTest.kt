package org.jetbrains.hexana.dex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DexBytecodeDecoderTest {

    @Test
    fun `opcode table covers every byte value`() {
        for (code in 0..255) {
            val op = DexOpcode.forCode(code)
            assertTrue(op != null, "no opcode for 0x${code.toString(16)}")
            assertEquals(code, op.code)
        }
        assertNull(DexOpcode.forCode(256))
        assertTrue(DexOpcode.UNUSED_3E.isUnused)
        assertTrue(!DexOpcode.NOP.isUnused)
    }

    @Test
    fun `decodes a sequence of one-code-unit instructions`() {
        // nop; move v1, v2; return-void
        val insns = insns(0x0000, 0x2101, 0x000E)
        val decoded = DexBytecodeDecoder.decode(insns)

        assertEquals(3, decoded.size)
        assertEquals(DexOpcode.NOP, decoded[0].opcode)
        assertEquals(0, decoded[0].address)
        assertEquals(1, decoded[0].codeUnits)

        assertEquals(DexOpcode.MOVE, decoded[1].opcode)
        assertEquals(1, decoded[1].address)
        assertEquals(listOf(1, 2), decoded[1].operands.registers)

        assertEquals(DexOpcode.RETURN_VOID, decoded[2].opcode)
        assertEquals(2, decoded[2].address)
    }

    @Test
    fun `const 4 sign-extends its nibble literal`() {
        // const/4 v0, #-1
        val decoded = DexBytecodeDecoder.decode(insns(0xF012))
        assertEquals(DexOpcode.CONST_4, decoded[0].opcode)
        assertEquals(listOf(0), decoded[0].operands.registers)
        assertEquals(-1L, decoded[0].operands.literal)
    }

    @Test
    fun `const 16 and const lit8 sign-extend their literals`() {
        // const/16 v2, #-3
        val const16 = DexBytecodeDecoder.decode(insns(0x0213, 0xFFFD))
        assertEquals(DexOpcode.CONST_16, const16[0].opcode)
        assertEquals(listOf(2), const16[0].operands.registers)
        assertEquals(-3L, const16[0].operands.literal)

        // add-int/lit8 v1, v2, #-1  (AA=1, BB=2, CC=0xff)
        val lit8 = DexBytecodeDecoder.decode(insns(0x01D8, 0xFF02))
        assertEquals(DexOpcode.ADD_INT_LIT8, lit8[0].opcode)
        assertEquals(listOf(1, 2), lit8[0].operands.registers)
        assertEquals(-1L, lit8[0].operands.literal)
    }

    @Test
    fun `high16 literals are shifted into place`() {
        // const/high16 v0, #0x12340000
        val int = DexBytecodeDecoder.decode(insns(0x0015, 0x1234))
        assertEquals(0x1234_0000L, int[0].operands.literal)

        // const-wide/high16 v0, #0x1234000000000000
        val wide = DexBytecodeDecoder.decode(insns(0x0019, 0x1234))
        assertEquals(0x1234L shl 48, wide[0].operands.literal)
    }

    @Test
    fun `const-wide reads a full 64-bit literal`() {
        // const-wide v0, #0x123456789abcdef0
        val decoded = DexBytecodeDecoder.decode(insns(0x0018, 0xDEF0, 0x9ABC, 0x5678, 0x1234))
        assertEquals(1, decoded.size)
        assertEquals(5, decoded[0].codeUnits)
        assertEquals(0x123456789ABCDEF0uL.toLong(), decoded[0].operands.literal)
    }

    @Test
    fun `const-string carries a string pool index`() {
        // const-string v0, string@5
        val decoded = DexBytecodeDecoder.decode(insns(0x001A, 0x0005))
        assertEquals(DexIndexKind.STRING, decoded[0].opcode.indexKind)
        assertEquals(5, decoded[0].operands.index)

        // const-string/jumbo v0, string@0x00010002
        val jumbo = DexBytecodeDecoder.decode(insns(0x001B, 0x0002, 0x0001))
        assertEquals(0x0001_0002, jumbo[0].operands.index)
    }

    @Test
    fun `35c invoke unpacks its argument registers`() {
        // invoke-direct {v1, v2}, meth@3
        val decoded = DexBytecodeDecoder.decode(insns(0x2070, 0x0003, 0x0021))
        assertEquals(DexOpcode.INVOKE_DIRECT, decoded[0].opcode)
        assertEquals(3, decoded[0].codeUnits)
        assertEquals(listOf(1, 2), decoded[0].operands.registers)
        assertEquals(3, decoded[0].operands.index)
        assertTrue(!decoded[0].operands.isRange)
    }

    @Test
    fun `35c invoke with five arguments reads G last`() {
        // invoke-virtual {v1, v2, v3, v4, v9}, meth@1 — C..F packed, G in the first unit
        val decoded = DexBytecodeDecoder.decode(insns(0x596E, 0x0001, 0x4321))
        assertEquals(listOf(1, 2, 3, 4, 9), decoded[0].operands.registers)
    }

    @Test
    fun `3rc invoke expands its register range`() {
        // invoke-static/range {v3 .. v6}, meth@7
        val decoded = DexBytecodeDecoder.decode(insns(0x0477, 0x0007, 0x0003))
        assertEquals(DexOpcode.INVOKE_STATIC_RANGE, decoded[0].opcode)
        assertEquals(listOf(3, 4, 5, 6), decoded[0].operands.registers)
        assertEquals(7, decoded[0].operands.index)
        assertTrue(decoded[0].operands.isRange)
    }

    @Test
    fun `invoke-polymorphic carries both a method and a proto index`() {
        // invoke-polymorphic {v5}, meth@2, proto@9
        val decoded = DexBytecodeDecoder.decode(insns(0x10FA, 0x0002, 0x0005, 0x0009))
        assertEquals(DexOpcode.INVOKE_POLYMORPHIC, decoded[0].opcode)
        assertEquals(4, decoded[0].codeUnits)
        assertEquals(listOf(5), decoded[0].operands.registers)
        assertEquals(2, decoded[0].operands.index)
        assertEquals(9, decoded[0].operands.secondaryIndex)
    }

    @Test
    fun `branch targets are absolute code-unit addresses`() {
        // nop; if-eq v0, v1, +4 (at address 1)
        val forward = DexBytecodeDecoder.decode(insns(0x0000, 0x1032, 0x0004))
        assertEquals(DexOpcode.IF_EQ, forward[1].opcode)
        assertEquals(listOf(0, 1), forward[1].operands.registers)
        assertEquals(5, forward[1].operands.branchTarget)

        // nop; nop; goto -2 (at address 2)
        val backward = DexBytecodeDecoder.decode(insns(0x0000, 0x0000, 0xFE28))
        assertEquals(DexOpcode.GOTO, backward[2].opcode)
        assertEquals(0, backward[2].operands.branchTarget)

        // goto/32 +0x00010000
        val wide = DexBytecodeDecoder.decode(insns(0x002A, 0x0000, 0x0001))
        assertEquals(3, wide[0].codeUnits)
        assertEquals(0x0001_0000, wide[0].operands.branchTarget)
    }

    @Test
    fun `packed-switch payload decodes keys and stays in sync`() {
        // packed-switch v0, +4 ; a padding nop keeps the payload 4-byte aligned
        val decoded = DexBytecodeDecoder.decode(
            insns(
                0x002B, 0x0004, 0x0000, // packed-switch v0, +4
                0x0000, // nop (alignment padding)
                0x0100, 0x0002, 0x0064, 0x0000, // payload: size 2, first_key 100
                0x0008, 0x0000, // target[0] = +8
                0x000C, 0x0000, // target[1] = +12
            ),
        )
        assertEquals(3, decoded.size)
        assertEquals(DexOpcode.PACKED_SWITCH, decoded[0].opcode)
        assertEquals(4, decoded[0].operands.branchTarget)

        val payload = decoded[2]
        assertEquals(4, payload.address)
        assertEquals(8, payload.codeUnits)
        assertTrue(payload.isPayload)
        assertEquals(
            DexPayload.PackedSwitch(firstKey = 100, targets = listOf(8, 12)),
            payload.operands.payload,
        )
    }

    @Test
    fun `sparse-switch payload decodes key-target pairs`() {
        val decoded = DexBytecodeDecoder.decode(
            insns(
                0x0200, 0x0002, // payload: size 2
                0x0001, 0x0000, // key[0] = 1
                0x0005, 0x0000, // key[1] = 5
                0x0006, 0x0000, // target[0] = +6
                0x0009, 0x0000, // target[1] = +9
            ),
        )
        assertEquals(1, decoded.size)
        assertEquals(10, decoded[0].codeUnits)
        assertEquals(
            DexPayload.SparseSwitch(keys = listOf(1, 5), targets = listOf(6, 9)),
            decoded[0].operands.payload,
        )
    }

    @Test
    fun `fill-array-data payload decodes odd-sized data with padding`() {
        val decoded = DexBytecodeDecoder.decode(
            insns(
                0x0300, 0x0001, // payload: element_width 1
                0x0003, 0x0000, // size 3
                0x0201, 0x0003, // data 01 02 03 + one padding byte
            ),
        )
        assertEquals(1, decoded.size)
        assertEquals(6, decoded[0].codeUnits)
        assertEquals(
            DexPayload.FillArrayData(elementWidth = 1, size = 3, data = byteArrayOf(1, 2, 3)),
            decoded[0].operands.payload,
        )
    }

    @Test
    fun `a plain nop is not mistaken for a payload`() {
        val decoded = DexBytecodeDecoder.decode(insns(0x0000))
        assertTrue(!decoded[0].isPayload)
        assertNull(decoded[0].operands.payload)
    }

    @Test
    fun `reserved opcodes and truncated instructions fail loudly`() {
        assertFails { DexBytecodeDecoder.decode(insns(0x003E)) }
        // const-string needs a second code unit.
        assertFails { DexBytecodeDecoder.decode(insns(0x001A)) }
        assertFails { DexBytecodeDecoder.decode(byteArrayOf(0x1A)) }
    }
}

/** Builds an `insns[]` byte array from little-endian code units. */
internal fun insns(vararg units: Int): ByteArray {
    val out = ByteArray(units.size * 2)
    units.forEachIndexed { i, unit ->
        out[i * 2] = (unit and 0xFF).toByte()
        out[i * 2 + 1] = ((unit shr 8) and 0xFF).toByte()
    }
    return out
}
