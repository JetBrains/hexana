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

    // -----------------------------------------------------------------------
    // branchTargets population — regression tests for the Rust-side fix
    // -----------------------------------------------------------------------

    /**
     * Forward conditional branch: a 5-instruction stream where instruction at
     * index 2 is a conditional branch targeting inst[4]. Expected split:
     *   block0 = [inst0, inst1, inst2]   outgoing = [addr_inst4]   (taken edge)
     *   block1 = [inst3]                 outgoing = []              (fall-through landing)
     *   block2 = [inst4, inst5]          outgoing = []
     * Fall-through from block0 to block1 is implicit (not in outgoingTargets per spec).
     */
    @Test
    fun `forward conditional branch produces three blocks with taken edge on source block`() {
        val instructions = listOf(
            stubValid(address = 0x00uL, isTerminator = false),
            stubValid(address = 0x04uL, isTerminator = false),
            stubValid(address = 0x08uL, isTerminator = true,  branchTargets = listOf(0x10uL)), // je 0x10
            stubValid(address = 0x0CuL, isTerminator = false),
            stubValid(address = 0x10uL, isTerminator = false),
            stubValid(address = 0x14uL, isTerminator = true),
        )

        val blocks = BasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(3, blocks.size, "expected three blocks after conditional branch")

        // block 0: instructions 0, 1, 2
        assertEquals(0x00uL, blocks[0].start)
        assertEquals(0x08uL, blocks[0].endInclusive)
        assertEquals(3, blocks[0].instructions.size)
        assertEquals(listOf(0x10uL), blocks[0].outgoingTargets, "taken edge must point to branch target")

        // block 1: fall-through landing (instruction 3)
        assertEquals(0x0CuL, blocks[1].start)
        assertEquals(0x0CuL, blocks[1].endInclusive)
        assertEquals(1, blocks[1].instructions.size)

        // block 2: branch target landing (instructions 4 and 5)
        assertEquals(0x10uL, blocks[2].start)
        assertEquals(0x14uL, blocks[2].endInclusive)
        assertEquals(2, blocks[2].instructions.size)
    }

    /**
     * Unconditional jump: a single terminator with one branch target.
     * outgoingTargets must contain exactly that target, with no fall-through edge
     * (there is no instruction after the jump so there is nothing to add as fall-through).
     */
    @Test
    fun `unconditional jump produces one block with exactly one outgoing target and no fall-through`() {
        val instructions = listOf(
            stubValid(address = 0x00uL, isTerminator = false),
            stubValid(address = 0x04uL, isTerminator = true, branchTargets = listOf(0x20uL)), // jmp 0x20
        )

        val blocks = BasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(1, blocks.size, "no instruction follows the jump so only one block expected")
        assertEquals(listOf(0x20uL), blocks[0].outgoingTargets)
    }

    /**
     * Backward branch (loop): the branch target points to an earlier address.
     * The builder records the back-edge in `outgoingTargets` and creates a
     * fall-through block for the instruction that follows the terminator.
     *
     * Layout:
     *   0x00 — loop header (the back-edge target — already the first block start)
     *   0x04 — loop body
     *   0x08 — conditional branch back to 0x00 (terminator)
     *   0x0C — loop exit (added as block start by the terminator rule)
     *
     * The builder's two-pass algorithm collects blockStarts = {0x00, 0x0C}.
     * 0x00 is both the stream start and the back-edge target; it does not cause
     * an additional split because the stream-start is already a block boundary.
     * The terminator at 0x08 closes the first block and 0x0C opens the second.
     *
     * Expected blocks:
     *   block0 = [0x00, 0x04, 0x08]   outgoing = [0x00]   (back-edge to own start)
     *   block1 = [0x0C]               outgoing = []        (loop exit / fall-through)
     */
    @Test
    fun `backward branch records back-edge in outgoingTargets and creates fall-through block`() {
        val instructions = listOf(
            stubValid(address = 0x00uL, isTerminator = false),                                        // loop header
            stubValid(address = 0x04uL, isTerminator = false),                                        // body
            stubValid(address = 0x08uL, isTerminator = true, branchTargets = listOf(0x00uL)),         // jle back
            stubValid(address = 0x0CuL, isTerminator = false),                                        // exit
        )

        val blocks = BasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(2, blocks.size, "back-edge to first address does not subdivide further; terminator creates exit block")

        // block 0: the entire loop body ending with the back-edge branch
        assertEquals(0x00uL, blocks[0].start)
        assertEquals(0x08uL, blocks[0].endInclusive)
        assertEquals(listOf(0x00uL), blocks[0].outgoingTargets, "back-edge must appear in outgoingTargets")

        // block 1: loop exit — fall-through from block 0
        assertEquals(0x0CuL, blocks[1].start)
        assertEquals(0x0CuL, blocks[1].endInclusive)
        assertEquals(emptyList(), blocks[1].outgoingTargets)
    }

    /**
     * Backward branch where the back-edge target is NOT the very first instruction.
     * A branch from 0x10 targets 0x04, which lies in the middle of the stream.
     * The builder must split at 0x04 (from branchTargets) AND at 0x14 (after the
     * terminator), producing a proper loop-header block.
     *
     *   block0 = [0x00]
     *   block1 = [0x04, 0x08, 0x0C, 0x10]   outgoing = [0x04]   (loop latch)
     *   block2 = [0x14]
     */
    @Test
    fun `backward branch to mid-stream address splits loop header correctly`() {
        val instructions = listOf(
            stubValid(address = 0x00uL, isTerminator = false),
            stubValid(address = 0x04uL, isTerminator = false),                                         // loop header
            stubValid(address = 0x08uL, isTerminator = false),
            stubValid(address = 0x0CuL, isTerminator = false),
            stubValid(address = 0x10uL, isTerminator = true, branchTargets = listOf(0x04uL)),          // jle 0x04 (loop latch)
            stubValid(address = 0x14uL, isTerminator = false),                                         // exit
        )

        val blocks = BasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(3, blocks.size, "back-edge to mid-stream target must split the stream at that target")

        // block 0: before the loop header
        assertEquals(0x00uL, blocks[0].start)
        assertEquals(0x00uL, blocks[0].endInclusive)

        // block 1: loop body ending with the latch branch
        assertEquals(0x04uL, blocks[1].start)
        assertEquals(0x10uL, blocks[1].endInclusive)
        assertEquals(listOf(0x04uL), blocks[1].outgoingTargets, "latch back-edge must point to loop header")

        // block 2: loop exit
        assertEquals(0x14uL, blocks[2].start)
    }

    /**
     * A call-like instruction (non-terminator, empty branchTargets) must NOT split
     * a block. The instruction is carried in the same block as its neighbours and
     * does not contribute any outgoing branch target.
     *
     * DecodedInstruction.Valid has no callTargets field — a call is simply an
     * instruction with isBlockTerminator=false and branchTargets=[].
     */
    @Test
    fun `call-like instruction with empty branchTargets does not split block`() {
        val instructions = listOf(
            stubValid(address = 0x00uL, isTerminator = false),
            stubValid(address = 0x04uL, isTerminator = false, branchTargets = emptyList()), // call — not a terminator
            stubValid(address = 0x08uL, isTerminator = false),
            stubValid(address = 0x0CuL, isTerminator = true),
        )

        val blocks = BasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(1, blocks.size, "call must not split the block")
        assertEquals(4, blocks[0].instructions.size)
        assertEquals(emptyList(), blocks[0].outgoingTargets, "no branch target means no outgoing static edge")
    }

    /**
     * Indirect branch: terminator with empty branchTargets (target not statically
     * known). The block must still be terminated, but outgoingTargets stays empty.
     * Regression guard: before the Rust fix, all instructions had branchTargets=[],
     * so this case was indistinguishable from a direct jump — now it is explicitly
     * the indirect case.
     */
    @Test
    fun `indirect branch with empty branchTargets ends block but produces no static outgoing edge`() {
        val instructions = listOf(
            stubValid(address = 0x00uL, isTerminator = false),
            stubValid(address = 0x04uL, isTerminator = true, branchTargets = emptyList()), // jmp *rax
            stubValid(address = 0x08uL, isTerminator = true),
        )

        val blocks = BasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(2, blocks.size, "terminator must end the block even with empty branchTargets")
        assertEquals(0x00uL, blocks[0].start)
        assertEquals(0x04uL, blocks[0].endInclusive)
        assertEquals(emptyList(), blocks[0].outgoingTargets, "indirect jump must produce no static outgoing target")

        // The instruction after the terminator starts a new block
        assertEquals(0x08uL, blocks[1].start)
    }

    /**
     * A later instruction's branchTargets point into the middle of an already-seen
     * sequence — the builder must split that earlier run at the target address.
     *
     *   0x00 — first instruction (will be split off at 0x04 because of the branch target)
     *   0x04 — second instruction (branch target — becomes a block start)
     *   0x08 — terminator — conditional branch back to 0x04
     *   0x0C — fourth instruction (fall-through after terminator)
     *
     * The builder's two-pass algorithm collects blockStarts = {0x00, 0x04, 0x0C}.
     * 0x08 is NOT a block start (nothing branches *to* it), so the terminator at
     * 0x08 stays in the same block as 0x04. Expected blocks:
     *   block0 = [0x00]
     *   block1 = [0x04, 0x08]   outgoing = [0x04]
     *   block2 = [0x0C]
     *
     * Note: the two-pass approach lets a branch target that appears *before* the
     * branch in the stream be correctly identified as a block start.
     */
    @Test
    fun `branch into middle of existing sequence splits that sequence at the target address`() {
        val instructions = listOf(
            stubValid(address = 0x00uL, isTerminator = false),
            stubValid(address = 0x04uL, isTerminator = false),
            stubValid(address = 0x08uL, isTerminator = true, branchTargets = listOf(0x04uL)), // je 0x04
            stubValid(address = 0x0CuL, isTerminator = false),
        )

        val blocks = BasicBlockCandidateBuilder.fromInstructions(instructions)

        // 0x04 is split off from 0x00 by the branch-target rule; 0x08 (the
        // terminator) stays attached to 0x04 because nothing branches to 0x08.
        assertEquals(3, blocks.size, "branch target at 0x04 must split the prefix block from [0x04,0x08]")

        assertEquals(0x00uL, blocks[0].start)
        assertEquals(0x00uL, blocks[0].endInclusive)

        assertEquals(0x04uL, blocks[1].start)
        assertEquals(0x08uL, blocks[1].endInclusive)
        assertEquals(listOf(0x04uL), blocks[1].outgoingTargets, "branch target must appear in outgoingTargets")

        assertEquals(0x0CuL, blocks[2].start)
        assertEquals(0x0CuL, blocks[2].endInclusive)
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
