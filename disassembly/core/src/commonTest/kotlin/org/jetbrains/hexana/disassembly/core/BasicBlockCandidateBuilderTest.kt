package org.jetbrains.hexana.disassembly.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BasicBlockCandidateBuilderTest {
    @Test
    fun `returns no basic blocks for empty instruction list`() {
        assertEquals(emptyList(), BasicBlockCandidateBuilder.fromInstructions(emptyList()))
    }

    @Test
    fun `keeps non-terminator instructions in the same block`() {
        val instructions = listOf(
            stubValid(address = 0x1000uL, isTerminator = false),  // e.g. callq (not a terminator)
            stubValid(address = 0x1005uL, isTerminator = false),  // movq
            stubValid(address = 0x1008uL, isTerminator = true),   // retq
        )

        val blocks = BasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(1, blocks.size)
        assertEquals(0x1000uL, blocks.single().start)
        assertEquals(0x1008uL, blocks.single().endInclusive)
        assertEquals(emptyList(), blocks.single().outgoingTargets)
    }

    @Test
    fun `splits at branch targets and after terminators`() {
        val instructions = listOf(
            stubValid(address = 0x1000uL, isTerminator = true, branchTargets = listOf(0x1005uL)),  // je 0x1005 (terminator + jump)
            stubValid(address = 0x1002uL, isTerminator = false), // nop (fall-through start)
            stubValid(address = 0x1003uL, isTerminator = true),  // retq
            stubValid(address = 0x1005uL, isTerminator = true),  // retq (jump landing)
        )

        val blocks = BasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(3, blocks.size)
        assertEquals(0x1000uL, blocks[0].start);  assertEquals(0x1000uL, blocks[0].endInclusive)
        assertEquals(0x1002uL, blocks[1].start);  assertEquals(0x1003uL, blocks[1].endInclusive)
        assertEquals(0x1005uL, blocks[2].start);  assertEquals(0x1005uL, blocks[2].endInclusive)
        assertTrue(0x1005uL in blocks[0].outgoingTargets)
    }
}

private fun stubValid(
    address: ULong,
    isTerminator: Boolean,
    branchTargets: List<ULong> = emptyList(),
): DecodedInstruction.Valid = object : DecodedInstruction.Valid {
    override val address: ULong = address
    override val byteRange: IntRange = 0..0
    override val arch: Arch = Arch.X86_64
    override val isBlockTerminator: Boolean = isTerminator
    override val branchTargets: List<ULong> = branchTargets
}
