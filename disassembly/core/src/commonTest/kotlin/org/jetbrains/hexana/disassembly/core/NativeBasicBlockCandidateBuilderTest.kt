package org.jetbrains.hexana.disassembly.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeBasicBlockCandidateBuilderTest {
    @Test
    fun `returns no basic blocks for empty instruction list`() {
        val blocks = NativeBasicBlockCandidateBuilder.fromInstructions(emptyList())

        assertEquals(emptyList(), blocks)
    }

    @Test
    fun `does not split basic block at calls`() {
        val instructions = listOf(
            instruction(
                address = 0x1000uL,
                mnemonic = "callq",
                xrefs = listOf(
                    NativeXref(
                        kind = NativeXrefKind.Call,
                        target = NativeAddress(0x2000uL, null),
                        symbolName = "callee",
                    )
                ),
            ),
            instruction(address = 0x1005uL, mnemonic = "movq"),
            instruction(address = 0x1008uL, mnemonic = "retq"),
        )

        val blocks = NativeBasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(1, blocks.size)
        assertEquals(0x1000uL, blocks.single().start.virtualAddress)
        assertEquals(0x1008uL, blocks.single().endInclusive.virtualAddress)
        assertEquals(emptyList(), blocks.single().outgoingXrefs)
    }

    @Test
    fun `splits basic block candidates at jump targets and terminators`() {
        val instructions = listOf(
            instruction(
                address = 0x1000uL,
                mnemonic = "je",
                xrefs = listOf(
                    NativeXref(
                        kind = NativeXrefKind.Jump,
                        target = NativeAddress(0x1005uL, null),
                        symbolName = "branchy+0x5",
                    )
                ),
            ),
            instruction(address = 0x1002uL, mnemonic = "nop"),
            instruction(address = 0x1003uL, mnemonic = "retq"),
            instruction(address = 0x1005uL, mnemonic = "retq"),
        )

        val blocks = NativeBasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(3, blocks.size)
        assertEquals(0x1000uL, blocks[0].start.virtualAddress)
        assertEquals(0x1000uL, blocks[0].endInclusive.virtualAddress)
        assertEquals(NativeXrefKind.Jump, blocks[0].outgoingXrefs.single().kind)
        assertEquals(0x1005uL, blocks[0].outgoingXrefs.single().target?.virtualAddress)
        assertEquals(0x1002uL, blocks[1].start.virtualAddress)
        assertEquals(0x1003uL, blocks[1].endInclusive.virtualAddress)
        assertEquals(0x1005uL, blocks[2].start.virtualAddress)
        assertEquals(0x1005uL, blocks[2].endInclusive.virtualAddress)
    }

    @Test
    fun `does not treat arbitrary j-prefixed mnemonics as terminators`() {
        val instructions = listOf(
            instruction(address = 0x1000uL, mnemonic = "junk"),
            instruction(address = 0x1001uL, mnemonic = "nop"),
        )

        val blocks = NativeBasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(1, blocks.size)
        assertEquals(0x1000uL, blocks.single().start.virtualAddress)
        assertEquals(0x1001uL, blocks.single().endInclusive.virtualAddress)
    }

    @Test
    fun `splits AArch64 branch candidates`() {
        val instructions = listOf(
            instruction(
                address = 0x1000uL,
                mnemonic = "b.eq",
                xrefs = listOf(
                    NativeXref(
                        kind = NativeXrefKind.Jump,
                        target = NativeAddress(0x1010uL, null),
                        symbolName = null,
                    )
                ),
            ),
            instruction(address = 0x1004uL, mnemonic = "mov"),
            instruction(address = 0x1010uL, mnemonic = "ret"),
        )

        val blocks = NativeBasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(3, blocks.size)
        assertEquals(0x1000uL, blocks[0].start.virtualAddress)
        assertEquals(0x1004uL, blocks[1].start.virtualAddress)
        assertEquals(0x1010uL, blocks[2].start.virtualAddress)
    }

    @Test
    fun `splits ARM Thumb conditional branch candidates`() {
        val instructions = listOf(
            instruction(
                address = 0x1000uL,
                mnemonic = "bne",
                xrefs = listOf(
                    NativeXref(
                        kind = NativeXrefKind.Jump,
                        target = NativeAddress(0x1010uL, null),
                        symbolName = null,
                    )
                ),
            ),
            instruction(address = 0x1004uL, mnemonic = "mov"),
            instruction(address = 0x1010uL, mnemonic = "bx"),
            instruction(address = 0x1014uL, mnemonic = "nop"),
        )

        val blocks = NativeBasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(4, blocks.size)
        assertEquals(0x1000uL, blocks[0].start.virtualAddress)
        assertEquals(0x1000uL, blocks[0].endInclusive.virtualAddress)
        assertEquals(NativeXrefKind.Jump, blocks[0].outgoingXrefs.single().kind)
        assertEquals(0x1004uL, blocks[1].start.virtualAddress)
        assertEquals(0x1010uL, blocks[2].start.virtualAddress)
        assertEquals(0x1014uL, blocks[3].start.virtualAddress)
    }

    @Test
    fun `splits RISC-V branch and zero-register jalr candidates`() {
        val instructions = listOf(
            instruction(
                address = 0x1000uL,
                mnemonic = "bltu",
                operandsText = "a0, a1, 0x1010",
                xrefs = listOf(
                    NativeXref(
                        kind = NativeXrefKind.Jump,
                        target = NativeAddress(0x1010uL, null),
                        symbolName = null,
                    )
                ),
            ),
            instruction(address = 0x1004uL, mnemonic = "addi", operandsText = "a0, a0, 1"),
            instruction(address = 0x1010uL, mnemonic = "jalr", operandsText = "zero, 0(ra)"),
            instruction(address = 0x1014uL, mnemonic = "nop"),
        )

        val blocks = NativeBasicBlockCandidateBuilder.fromInstructions(instructions)

        assertEquals(4, blocks.size)
        assertEquals(0x1000uL, blocks[0].start.virtualAddress)
        assertEquals(0x1004uL, blocks[1].start.virtualAddress)
        assertEquals(0x1010uL, blocks[2].start.virtualAddress)
        assertEquals(0x1014uL, blocks[3].start.virtualAddress)
    }

    private fun instruction(
        address: ULong,
        mnemonic: String,
        operandsText: String = "",
        xrefs: List<NativeXref> = emptyList(),
    ): NativeInstruction =
        NativeInstruction(
            address = NativeAddress(address, null),
            sectionName = ".text",
            bytes = emptyList(),
            mnemonic = mnemonic,
            operandsText = operandsText,
            assemblyText = mnemonic,
            symbol = "branchy",
            sourceLocation = null,
            relocation = null,
            xrefs = xrefs,
            confidence = NativeDisassemblyConfidence.BackendProvided,
        )
}
