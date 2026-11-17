package org.jetbrains.hexana.disassembly.llvmobjdump

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlvmObjdumpDisassemblyParserTest {
    @Test
    fun `parses functions and instructions from snapshot`() {
        val text = readSnapshot("disassemble-x86_64.txt")

        val disassembly = LlvmObjdumpDisassemblyParser.parse(text)

        assertEquals(2, disassembly.functions.size)
        assertEquals(9, disassembly.instructions.size)

        val main = disassembly.functions[0]
        assertEquals("main", main.name)
        assertEquals(0x401140uL, main.address)
        assertEquals(".text", main.sectionName)
        assertEquals(7, main.instructions.size)

        val first = main.instructions[0]
        assertEquals(0x401140uL, first.address)
        assertEquals(".text", first.sectionName)
        assertEquals(listOf(0x55), first.bytes)
        assertEquals("pushq", first.mnemonic)
        assertEquals("%rbp", first.operands)
        assertEquals("main", first.functionName)

        val call = main.instructions[3]
        assertEquals(listOf(0xe8, 0xe0, 0xfe, 0xff, 0xff), call.bytes)
        assertEquals("callq", call.mnemonic)
        assertEquals("0x401030 <puts@plt>", call.operands)
    }

    @Test
    fun `parses space-separated byte columns`() {
        val text = readSnapshot("disassemble-x86_64.txt")

        val helper = LlvmObjdumpDisassemblyParser.parse(text).functions[1]

        assertEquals("helper", helper.name)
        assertEquals(0x401160uL, helper.address)
        assertEquals(listOf(0x83, 0xc7, 0x01), helper.instructions[0].bytes)
        assertEquals("addl", helper.instructions[0].mnemonic)
        assertEquals("$0x1, %edi", helper.instructions[0].operands)
    }

    @Test
    fun `parses fixed width instruction word byte columns`() {
        val disassembly = LlvmObjdumpDisassemblyParser.parse(
            """
            Disassembly of section __TEXT,__text:

            0000000100000d10 <__ZN13hexana_sample6squareEi>:
            100000d10: 1b007c00    	mul	w0, w0, w0
            100000d14: d65f03c0    	ret
            """.trimIndent(),
        )

        val first = disassembly.instructions[0]
        assertEquals(0x100000d10uL, first.address)
        assertEquals(listOf(0x00, 0x7c, 0x00, 0x1b), first.bytes)
        assertEquals("mul", first.mnemonic)
        assertEquals("w0, w0, w0", first.operands)
        assertEquals("mul w0, w0, w0", first.assembly)
    }

    @Test
    fun `creates unknown function for instructions without function header`() {
        val disassembly = LlvmObjdumpDisassemblyParser.parse(
            """
            Disassembly of section .init:
              401000: f3 0f 1e fa endbr64
              401004: c3 retq
            """.trimIndent(),
        )

        assertEquals(1, disassembly.functions.size)
        assertEquals("<unknown>", disassembly.functions.single().name)
        assertEquals(0x401000uL, disassembly.functions.single().address)
        assertEquals(".init", disassembly.functions.single().sectionName)
        assertEquals(2, disassembly.functions.single().instructions.size)
        assertEquals(listOf("<unknown>", "<unknown>"), disassembly.instructions.map { it.functionName })
    }

    @Test
    fun `keeps local labels inside current function`() {
        val disassembly = LlvmObjdumpDisassemblyParser.parse(
            """
            Disassembly of section .text:

            0000000000001000 <main>:
                1000: 90 nop
            0000000000001001 <.LBB0_2>:
                1001: 90 nop
            0000000000001002 <${'$'}local>:
                1002: c3 retq
            0000000000001003 <Ltmp0>:
                1003: 90 nop
            0000000000001004 <LBB0_3>:
                1004: 90 nop
            0000000000001005 <?Llocal>:
                1005: c3 retq
            """.trimIndent(),
        )

        assertEquals(1, disassembly.functions.size)
        assertEquals("main", disassembly.functions.single().name)
        assertEquals(6, disassembly.functions.single().instructions.size)
        assertEquals(List(6) { "main" }, disassembly.instructions.map { it.functionName })
    }

    @Test
    fun `keeps assembly text when byte column is not parseable`() {
        val disassembly = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <weird>:
                1000: zz zz definitely not bytes
            """.trimIndent(),
        )

        val instruction = disassembly.instructions.single()
        assertEquals(emptyList<Int>(), instruction.bytes)
        assertEquals("zz", instruction.mnemonic)
        assertEquals("zz definitely not bytes", instruction.operands)
        assertEquals("zz zz definitely not bytes", instruction.assembly)
    }

    @Test
    fun `ignores non instruction text`() {
        val disassembly = LlvmObjdumpDisassemblyParser.parse(
            """
            not-an-address: this is a banner
            Disassembly of section .text:
            """.trimIndent(),
        )

        assertTrue(disassembly.functions.isEmpty())
        assertTrue(disassembly.instructions.isEmpty())
    }

    private fun readSnapshot(name: String): String =
        Path.of("src/test/data/llvm-objdump", name).toFile().readText()
}
