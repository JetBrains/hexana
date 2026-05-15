package org.jetbrains.hexana.disassembly.llvmobjdump

import java.nio.file.Path
import org.jetbrains.hexana.disassembly.core.Arch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlvmObjdumpNativeDisassemblyBuilderTest {
    @Test
    fun `empty disassembly produces empty result`() {
        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(
            LlvmObjdumpDisassembly(functions = emptyList()),
            arch = Arch.X86_64,
        )
        assertEquals(emptyList<LlvmObjdumpDecodedFunction>(), result.functions)
        assertEquals(emptyList<LlvmObjdumpValidInstruction>(), result.instructions)
    }

    @Test
    fun `converts llvm objdump disassembly into typed instructions`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(readSnapshot("disassemble-x86_64.txt"))

        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm, arch = Arch.X86_64)

        assertEquals(2, result.functions.size)
        assertEquals(9, result.instructions.size)

        val main = result.functions[0]
        assertEquals("main", main.name)
        assertEquals(0x401140uL, main.entry)
        assertEquals(1, main.basicBlocks.size)
        assertEquals(0x401140uL, main.basicBlocks[0].start)
        assertEquals(0x401153uL, main.basicBlocks[0].endInclusive)

        val call = main.instructions[3]
        assertEquals(0x40114buL, call.address)
        assertEquals(0..4, call.byteRange)
        assertEquals(Arch.X86_64, call.arch)
        assertEquals(listOf(0xe8, 0xe0, 0xfe, 0xff, 0xff), call.bytes)
        assertEquals("callq", call.mnemonic)
        assertEquals("0x401030 <puts@plt>", call.operandsText)
        assertEquals("callq 0x401030 <puts@plt>", call.assemblyText)
        assertEquals(listOf(0x401030uL), call.callTargets)
        assertEquals(emptyList<ULong>(), call.branchTargets)
    }

    @Test
    fun `non control-flow instructions with address operands produce no targets`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <loads>:
                1000: 48 8d 3d 00 20 00 00 leaq 0x2000(%rip), %rdi
            """.trimIndent(),
        )
        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm, arch = Arch.X86_64)
        val insn = result.instructions.single()
        assertEquals(emptyList<ULong>(), insn.branchTargets)
        assertEquals(emptyList<ULong>(), insn.callTargets)
    }

    @Test
    fun `indirect control-flow operands do not produce direct targets`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <indirects>:
                1000: ff 15 2a 00 00 00 callq *0x2a(%rip)
                1006: ff 60 40 jmpq *0x40(%rax)
                1009: ff 25 00 20 00 00 jmp qword ptr [rip + 0x2000]
            """.trimIndent(),
        )
        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm, arch = Arch.X86_64)
        for (insn in result.instructions) {
            assertEquals(emptyList<ULong>(), insn.branchTargets)
            assertEquals(emptyList<ULong>(), insn.callTargets)
        }
        // jmpq and jmp are still terminators (block-splitters) even when indirect.
        assertEquals(2, result.functions.single().basicBlocks.size)
    }

    @Test
    fun `extracts direct branch targets for x86_64 AT&T immediate jumps`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <branchy>:
                1000: e9 00 00 00 00 jmp ${'$'}0x1008 <branchy+0x8>
                1005: 90 nop
                1008: c3 retq
            """.trimIndent(),
        )
        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm, arch = Arch.X86_64)
        assertEquals(listOf(0x1008uL), result.instructions.first().branchTargets)
    }

    @Test
    fun `direct call targets keep parenthesized demangled symbols intact`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <caller>:
                1000: e8 00 00 00 00 callq 0x2000 <foo(int)>
            """.trimIndent(),
        )
        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm, arch = Arch.X86_64)
        val insn = result.instructions.single()
        assertEquals(listOf(0x2000uL), insn.callTargets)
        assertEquals(emptyList<ULong>(), insn.branchTargets)
    }

    @Test
    fun `extracts AArch64 direct branch targets and splits blocks`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <branchy>:
                1000: 54000040 b.eq 0x1008 <branchy+0x8>
                1004: 14000002 b 0x100c <branchy+0xc>
                1008: d65f03c0 ret
                100c: d65f03c0 ret
            """.trimIndent(),
        )
        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm, arch = Arch.ARM64)
        assertEquals(listOf(0x1008uL), result.instructions[0].branchTargets)
        assertEquals(listOf(0x100cuL), result.instructions[1].branchTargets)
        assertEquals(4, result.functions.single().basicBlocks.size)
    }

    @Test
    fun `separates ARM Thumb branches from calls`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <arm_branchy>:
                1000: 1a000002 bne 0x1010 <arm_branchy+0x10>
                1004: eb000001 bl 0x100c <callee>
                1010: e12fff1e bx lr
            """.trimIndent(),
        )
        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm, arch = Arch.ARM)
        assertEquals(listOf(0x1010uL), result.instructions[0].branchTargets)
        assertEquals(emptyList<ULong>(), result.instructions[0].callTargets)
        assertEquals(emptyList<ULong>(), result.instructions[1].branchTargets)
        assertEquals(listOf(0x100cuL), result.instructions[1].callTargets)
        assertEquals(3, result.functions.single().basicBlocks.size)
    }

    @Test
    fun `RISC-V jal with non-zero rd is a call, with zero rd is a jump`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <riscv_branchy>:
                1000: 000000ef jal 0x2000 <callee>
                1004: 0000006f jal zero, 0x1010 <riscv_branchy+0x10>
                1008: 00b56a63 bltu a0, a1, 0x1014 <riscv_branchy+0x14>
                1010: 00008067 ret
                1014: 00008067 ret
            """.trimIndent(),
        )
        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm, arch = Arch.RISC_V_64)
        assertEquals(listOf(0x2000uL), result.instructions[0].callTargets)
        assertEquals(emptyList<ULong>(), result.instructions[0].branchTargets)
        assertEquals(listOf(0x1010uL), result.instructions[1].branchTargets)
        assertEquals(listOf(0x1014uL), result.instructions[2].branchTargets)
        assertEquals(4, result.functions.single().basicBlocks.size)
    }

    @Test
    fun `splits basic block candidates at jump targets and terminators`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <branchy>:
                1000: 74 03 je 0x1005 <branchy+0x5>
                1002: 90 nop
                1003: c3 retq
                1005: c3 retq
            """.trimIndent(),
        )
        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm, arch = Arch.X86_64)
        val blocks = result.functions.single().basicBlocks
        assertEquals(3, blocks.size)
        assertEquals(0x1000uL, blocks[0].start)
        assertEquals(0x1000uL, blocks[0].endInclusive)
        assertEquals(listOf(0x1005uL), blocks[0].outgoingTargets)
        assertEquals(0x1002uL, blocks[1].start)
        assertEquals(0x1003uL, blocks[1].endInclusive)
        assertEquals(0x1005uL, blocks[2].start)
        assertEquals(0x1005uL, blocks[2].endInclusive)
    }

    @Test
    fun `byteRange size matches bytes count`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <f>:
                1000: 48 89 d8 movq %rbx, %rax
                1003: c3 retq
            """.trimIndent(),
        )
        val result = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm, arch = Arch.X86_64)
        val movq = result.instructions[0]
        val retq = result.instructions[1]
        assertEquals(3, movq.bytes.size)
        assertEquals("size derived from byteRange should match bytes count", 3, movq.size)
        assertEquals(0..2, movq.byteRange)
        assertEquals(1, retq.bytes.size)
        assertEquals(1, retq.size)
        assertTrue("retq is a terminator", retq.isBlockTerminator)
    }

    private fun readSnapshot(name: String): String =
        Path.of("src/test/data/llvm-objdump", name).toFile().readText()
}
