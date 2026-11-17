package org.jetbrains.hexana.javabytecode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaBytecodeDecoderTest {
    @Test
    fun `decodes the canonical empty-constructor body`() {
        // The body of `public Hexana() { super(); }` compiled with javac:
        //   0: aload_0
        //   1: invokespecial #1   // Methodref java/lang/Object."<init>":()V
        //   4: return
        val code = byteArrayOf(
            0x2a,                                       // aload_0
            0xb7.toByte(), 0x00, 0x01,                  // invokespecial #1
            0xb1.toByte(),                              // return
        )
        val insns = JavaBytecodeDecoder.decode(code)
        assertEquals(3, insns.size)

        assertEquals(JavaOpcode.ALOAD_0, insns[0].opcode)
        assertEquals(0, insns[0].bytecodeOffset)
        assertEquals(1, insns[0].size)
        assertEquals(JavaBytecodeOperands.None, insns[0].operands)

        assertEquals(JavaOpcode.INVOKESPECIAL, insns[1].opcode)
        assertEquals(1, insns[1].bytecodeOffset)
        assertEquals(3, insns[1].size)
        assertEquals(JavaBytecodeOperands.Short1(1), insns[1].operands)

        assertEquals(JavaOpcode.RETURN, insns[2].opcode)
        assertEquals(4, insns[2].bytecodeOffset)
        assertEquals(1, insns[2].size)
    }

    @Test
    fun `bipush carries a signed byte immediate`() {
        val code = byteArrayOf(0x10, 0xFF.toByte(), 0xac.toByte()) // bipush -1; ireturn
        val insns = JavaBytecodeDecoder.decode(code)
        assertEquals(2, insns.size)
        assertEquals(JavaOpcode.BIPUSH, insns[0].opcode)
        val operands = insns[0].operands
        assertTrue(operands is JavaBytecodeOperands.Byte1)
        assertEquals(-1, operands.value)
    }

    @Test
    fun `iinc carries var index and signed delta`() {
        val code = byteArrayOf(0x84.toByte(), 0x02, 0xFE.toByte(), 0xb1.toByte()) // iinc 2 -2; return
        val insns = JavaBytecodeDecoder.decode(code)
        assertEquals(JavaOpcode.IINC, insns[0].opcode)
        val operands = insns[0].operands
        assertTrue(operands is JavaBytecodeOperands.IInc)
        assertEquals(2, operands.varIndex)
        assertEquals(-2, operands.delta)
    }

    @Test
    fun `goto carries a signed 2-byte branch offset`() {
        val code = byteArrayOf(0xa7.toByte(), 0xFF.toByte(), 0xFD.toByte()) // goto -3
        val insns = JavaBytecodeDecoder.decode(code)
        assertEquals(JavaOpcode.GOTO, insns[0].opcode)
        val operands = insns[0].operands
        assertTrue(operands is JavaBytecodeOperands.Short1)
        assertEquals(-3, operands.value)
    }

    @Test
    fun `tableswitch decodes with padding and full jump table`() {
        // Layout, starting at offset 0:
        //   0: nop
        //   1: tableswitch     (instruction start at 1; padding so we land on 4)
        //      pad(2 bytes: 02 03 = anything; here zeros)
        //      default = 0x14
        //      low     = 0
        //      high    = 2
        //      offsets = [10, 11, 12]
        //   total size of tableswitch instruction = 1 + 2pad + 4 + 4 + 4 + 3*4 = 27
        val code = ByteArray(1 + 27) { 0 }
        code[0] = 0x00 // nop
        code[1] = 0xaa.toByte() // tableswitch
        // padding bytes at code[2..3] left as 0
        // default(4) at code[4..7] = 0x14
        code[7] = 0x14
        // low(4) at code[8..11] = 0
        // high(4) at code[12..15] = 2
        code[15] = 0x02
        // jump offsets at code[16..27]: [10, 11, 12]
        code[19] = 10
        code[23] = 11
        code[27] = 12

        val insns = JavaBytecodeDecoder.decode(code)
        assertEquals(2, insns.size)
        val ts = insns[1]
        assertEquals(JavaOpcode.TABLESWITCH, ts.opcode)
        assertEquals(1, ts.bytecodeOffset)
        assertEquals(27, ts.size)
        val operands = ts.operands
        assertTrue(operands is JavaBytecodeOperands.TableSwitch)
        assertEquals(0x14, operands.defaultOffset)
        assertEquals(0, operands.low)
        assertEquals(2, operands.high)
        assertEquals(listOf(10, 11, 12), operands.jumpOffsets)
    }

    @Test
    fun `JavaCodeAttributeParser decodes maxStack maxLocals and instructions`() {
        // Hand-build a Code attribute body for a single-instruction `return`
        // with max_stack=0, max_locals=1, no exception handlers, no nested
        // attributes.
        val info = byteArrayOf(
            0x00, 0x00,             // max_stack = 0
            0x00, 0x01,             // max_locals = 1
            0x00, 0x00, 0x00, 0x01, // code_length = 1
            0xb1.toByte(),          // return
            0x00, 0x00,             // exception_table_length = 0
            0x00, 0x00,             // attributes_count = 0
        )
        val parsed = JavaCodeAttributeParser.parse(info)
        assertEquals(0, parsed.maxStack)
        assertEquals(1, parsed.maxLocals)
        assertEquals(1, parsed.code.size)
        assertEquals(1, parsed.instructions.size)
        assertEquals(JavaOpcode.RETURN, parsed.instructions.single().opcode)
        assertEquals(emptyList(), parsed.exceptionTable)
        assertEquals(emptyList(), parsed.attributes)
    }
}
