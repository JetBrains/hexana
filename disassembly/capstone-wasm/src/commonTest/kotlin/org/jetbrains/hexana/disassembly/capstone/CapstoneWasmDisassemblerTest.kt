package org.jetbrains.hexana.disassembly.capstone

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.AssemblySyntax
import org.jetbrains.hexana.disassembly.core.DecodedInstruction
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapstoneWasmDisassemblerTest {

    @Test
    fun decodesShortX86_64Sequence() = runTest {
        val disasm = loadTestDisassembler()

        // nop ; ret ; mov rax, rbx
        val bytes = byteArrayOf(
            0x90.toByte(),                                // nop
            0xc3.toByte(),                                // ret
            0x48.toByte(), 0x89.toByte(), 0xd8.toByte(),  // mov rax, rbx
        )
        val instructions = with(disasm) {
            bytes.toCommonByteBuffer().disassemble(baseAddress = 0x1000UL, arch = Arch.X86_64)
        }.toList()

        assertEquals(3, instructions.size, "expected nop, ret, mov")

        val nop = instructions[0]
        assertTrue(nop is DecodedInstruction.Valid, "nop should be Valid, got $nop")
        assertEquals(1, nop.size)
        assertEquals(0x1000UL, nop.address)
        assertEquals(0..0, nop.byteRange)
        assertFalse(nop.isBlockTerminator, "nop is not a block terminator")

        val ret = instructions[1]
        assertTrue(ret is DecodedInstruction.Valid, "ret should be Valid")
        assertEquals(1, ret.size)
        assertEquals(0x1001UL, ret.address)
        assertEquals(1..1, ret.byteRange)
        assertTrue(ret.isBlockTerminator, "ret IS a block terminator")

        val mov = instructions[2]
        assertTrue(mov is DecodedInstruction.Valid, "mov should be Valid")
        assertEquals(3, mov.size)
        assertEquals(0x1002UL, mov.address)
        assertEquals(2..4, mov.byteRange)
        assertFalse(mov.isBlockTerminator, "mov is not a terminator")
    }

    @Test
    fun formatterRendersMnemonicAndOperandsOfMov() = runTest {
        val disasm = loadTestDisassembler()
        val bytes = byteArrayOf(0x48.toByte(), 0x89.toByte(), 0xd8.toByte()) // mov rax, rbx

        val insn = with(disasm) {
            bytes.toCommonByteBuffer().disassemble(baseAddress = 0UL, arch = Arch.X86_64)
        }.toList().single() as DecodedInstruction.Valid

        val fmt = disasm.formatter(Arch.X86_64, AssemblySyntax.INTEL)
        val mnemonic = fmt.mnemonic(insn).lowercase()
        val operands = fmt.operands(insn).lowercase()

        assertEquals("mov", mnemonic, "yaxpeax should emit lowercase 'mov'")
        assertTrue("rax" in operands && "rbx" in operands, "operands should reference rax + rbx, got '$operands'")
    }

    @Test
    fun emptyBufferYieldsEmptySequence() = runTest {
        val disasm = loadTestDisassembler()
        val instructions = with(disasm) {
            ByteArray(0).toCommonByteBuffer().disassemble(baseAddress = 0UL, arch = Arch.X86_64)
        }.toList()
        assertEquals(0, instructions.size)
    }

    @Test
    fun retIsTerminatorOnAllArches() = runTest {
        val disasm = loadTestDisassembler()

        // x86_64 ret
        val x86 = byteArrayOf(0xc3.toByte()).toCommonByteBuffer()
        val x86Insn = with(disasm) { x86.disassemble(baseAddress = 0UL, arch = Arch.X86_64) }
            .toList().single() as DecodedInstruction.Valid
        assertTrue(x86Insn.isBlockTerminator, "x86_64 ret should terminate")

        // ARM64 ret (D65F03C0 in little-endian: C0 03 5F D6)
        val arm64 = byteArrayOf(0xC0.toByte(), 0x03.toByte(), 0x5F.toByte(), 0xD6.toByte()).toCommonByteBuffer()
        val arm64Insn = with(disasm) { arm64.disassemble(baseAddress = 0UL, arch = Arch.ARM64) }
            .toList().single() as DecodedInstruction.Valid
        assertEquals(4, arm64Insn.size, "AArch64 instructions are 4 bytes")
        assertTrue(arm64Insn.isBlockTerminator, "AArch64 RET should terminate")
    }
}
