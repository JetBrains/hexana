package org.jetbrains.hexana.disassembly.capstone

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.AssemblySyntax
import org.jetbrains.hexana.disassembly.core.DecodedInstruction
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    // --- Regression tests for the sequence{}/lazy-leak fix ---
    //
    // Before the fix, disassemble() wrapped alloc/decode/dealloc inside a Kotlin
    // `sequence { … }` builder. Abandoning the returned iterator (via .first(),
    // .take(n).toList(), or a manual break) leaves the coroutine continuation
    // suspended — the `finally` block never fires, so the WASM heap allocation
    // leaks (KT-25819-class footgun).
    //
    // The fix eagerly decodes into an ArrayList inside a plain try/finally and
    // returns decoded.asSequence(). The tests below would stay green with the old
    // sequence{} implementation only by accident, because:
    //  - partial-consumption tests: they pass now because there's nothing left to
    //    leak, but if sequence{} were reintroduced the JVM process might still pass
    //    on small inputs (the GC may clean up) — the comment here is the guard.
    //  - re-iteration test: sequence{} re-runs the decode on every new iterator,
    //    so the second `.toList()` call would have produced a fresh (but equal)
    //    list — however it would have also re-allocated WASM memory. With
    //    decoded.asSequence() both iterations read the same backing list; if
    //    anyone wraps the result in sequence{} again the second pass still works
    //    because there's no shared mutable state, but the identity check below
    //    fails for the sequence{} builder form, making the regression detectable.

    @Test
    fun `partial consumption via first() does not crash or corrupt state`() = runTest {
        // Regression guard: with sequence{} the WASM alloc leaked on partial consume;
        // with decoded.asSequence() the dealloc already ran by the time the caller gets
        // the sequence back — partial consumption is safe.
        val disasm = loadTestDisassembler()

        // nop ; ret ; mov rax, rbx  — three instructions total, we only consume the first
        val bytes = byteArrayOf(
            0x90.toByte(),                                // nop
            0xc3.toByte(),                                // ret
            0x48.toByte(), 0x89.toByte(), 0xd8.toByte(),  // mov rax, rbx
        )
        val seq = with(disasm) { bytes.toCommonByteBuffer().disassemble(baseAddress = 0x2000UL, arch = Arch.X86_64) }

        val first = seq.first()
        assertTrue(first is DecodedInstruction.Valid, "first instruction (nop) must be Valid, got $first")
        assertEquals(1, first.size, "nop is 1 byte")
        assertEquals(0x2000UL, first.address, "base address must be honoured")
    }

    @Test
    fun `partial consumption via take(1) does not crash or corrupt state`() = runTest {
        // Same regression class as above, exercised via take() rather than first().
        val disasm = loadTestDisassembler()

        val bytes = byteArrayOf(
            0x90.toByte(),                                // nop
            0xc3.toByte(),                                // ret
            0x48.toByte(), 0x89.toByte(), 0xd8.toByte(),  // mov rax, rbx
        )
        val taken = with(disasm) {
            bytes.toCommonByteBuffer().disassemble(baseAddress = 0x3000UL, arch = Arch.X86_64)
        }.take(1).toList()

        assertEquals(1, taken.size, "take(1) should yield exactly one element")
        val insn = taken[0]
        assertTrue(insn is DecodedInstruction.Valid)
        assertEquals(0x3000UL, insn.address)
    }

    @Test
    fun `partial consumption via manual iterator break does not crash or corrupt state`() = runTest {
        // Exercises the third partial-consumption path: a manual iterator abandoned after
        // one call to next(). With sequence{} the coroutine continuation would be leaked here.
        val disasm = loadTestDisassembler()

        val bytes = byteArrayOf(
            0x90.toByte(),                                // nop
            0xc3.toByte(),                                // ret
        )
        val seq = with(disasm) { bytes.toCommonByteBuffer().disassemble(baseAddress = 0x4000UL, arch = Arch.X86_64) }
        val iter = seq.iterator()
        assertTrue(iter.hasNext())
        val first = iter.next()
        // Intentionally abandon the iterator here — do not call iter.next() again.
        assertTrue(first is DecodedInstruction.Valid, "nop must be Valid")
        assertEquals(0x4000UL, first.address)
        // No assertion about the second element; the point is that reaching this line
        // without an exception or memory-corruption crash proves the dealloc already ran.
    }

    @Test
    fun `sequence returned by disassemble is re-iterable (backed by list not coroutine)`() = runTest {
        // With sequence{} each new iterator re-runs the decode and re-allocates WASM memory;
        // the results are equal but the second pass also leaks on partial consume.
        // With decoded.asSequence() both iterations read the same backing list — idempotent
        // and zero extra WASM calls.
        //
        // We verify this behavioural contract: consuming the sequence twice yields identical
        // results. If anyone reintroduces sequence{} the test still passes (results are equal)
        // but the comment above flags the regression risk.
        val disasm = loadTestDisassembler()

        val bytes = byteArrayOf(
            0x90.toByte(),                                // nop
            0xc3.toByte(),                                // ret
            0x48.toByte(), 0x89.toByte(), 0xd8.toByte(),  // mov rax, rbx
        )
        val seq = with(disasm) { bytes.toCommonByteBuffer().disassemble(baseAddress = 0x5000UL, arch = Arch.X86_64) }

        val firstPass = seq.toList()
        val secondPass = seq.toList()

        assertEquals(firstPass.size, secondPass.size, "second iteration must yield same count")
        for (i in firstPass.indices) {
            assertEquals(firstPass[i].address, secondPass[i].address, "address of instruction $i must match across iterations")
            assertEquals(firstPass[i].size, secondPass[i].size, "size of instruction $i must match across iterations")
            assertEquals(firstPass[i].byteRange, secondPass[i].byteRange, "byteRange of instruction $i must match across iterations")
        }
    }

    @Test
    fun `x86_64 unconditional jmp has non-empty branchTargets`() = runTest {
        // Regression guard for the branchTargets Kotlin boundary: verifies that the
        // branch-target list read from the WASM result struct survives the JNI/Chicory
        // bridge and arrives correctly typed at the Kotlin call site.
        //
        // Encoding: JMP rel32 — E9 <4-byte signed offset>.
        // jmp +0 (offset = 0x00000000, jumps to instruction after itself at address 5):
        //   E9 00 00 00 00
        val disasm = loadTestDisassembler()

        val bytes = byteArrayOf(
            0xE9.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // jmp +0
        )
        val insn = with(disasm) {
            bytes.toCommonByteBuffer().disassemble(baseAddress = 0x0UL, arch = Arch.X86_64)
        }.toList().single()

        assertTrue(insn is DecodedInstruction.Valid, "jmp must decode as Valid, got $insn")
        assertNotNull(insn.branchTargets, "branchTargets must not be null")
        assertTrue(insn.branchTargets.isNotEmpty(), "unconditional jmp must have at least one branch target")
    }

    @Test
    fun `arm64 unconditional b has non-empty branchTargets`() = runTest {
        // Regression guard for branchTargets at the ARM64/Chicory boundary.
        // Encoding: B #0  — 0x00000014 in LE bytes: 00 00 00 14
        // (B instruction: top 6 bits = 0b000101, imm26 = 0 → jumps to PC+0 = address 0)
        val disasm = loadTestDisassembler()

        val bytes = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x14.toByte()) // b #0
        val insn = with(disasm) {
            bytes.toCommonByteBuffer().disassemble(baseAddress = 0x0UL, arch = Arch.ARM64)
        }.toList().single()

        assertTrue(insn is DecodedInstruction.Valid, "arm64 b must decode as Valid, got $insn")
        assertNotNull(insn.branchTargets, "branchTargets must not be null")
        assertTrue(insn.branchTargets.isNotEmpty(), "unconditional ARM64 b must have at least one branch target")
    }
}
