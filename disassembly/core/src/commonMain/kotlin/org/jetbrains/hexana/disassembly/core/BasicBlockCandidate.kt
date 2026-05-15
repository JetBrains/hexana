package org.jetbrains.hexana.disassembly.core

/**
 * A contiguous run of [DecodedInstruction.Valid] entries with a single entry
 * point and a single exit. The builder splits the stream at:
 *  - branch targets (anything reachable via a `branchTargets` entry of any
 *    earlier instruction in the same stream becomes a block start), and
 *  - terminator boundaries (any instruction following one with
 *    `isBlockTerminator == true` becomes a block start).
 *
 * `outgoingTargets` is the set of virtual addresses the block can transfer
 * control to via the last instruction's `branchTargets`. Conditional
 * fall-through is not represented here — it's the implicit "next block in
 * address order" edge.
 */
data class BasicBlockCandidate(
    val id: String,
    val start: ULong,
    val endInclusive: ULong,
    val instructions: List<DecodedInstruction.Valid>,
    val outgoingTargets: List<ULong>,
)

/**
 * Builds basic-block candidates over a [DecodedInstruction.Valid] stream using
 * only the structural facts the decoder publishes (`isBlockTerminator`,
 * `branchTargets`). Arch-specific mnemonic recognition lives in the backend
 * that produced the stream; this builder is arch-agnostic.
 */
object BasicBlockCandidateBuilder {
    fun fromInstructions(instructions: List<DecodedInstruction.Valid>): List<BasicBlockCandidate> {
        if (instructions.isEmpty()) return emptyList()

        val blockStarts = linkedSetOf(instructions.first().address)
        instructions.forEachIndexed { index, instruction ->
            instruction.branchTargets.forEach { blockStarts.add(it) }
            if (instruction.isBlockTerminator) {
                instructions.getOrNull(index + 1)?.let { blockStarts.add(it.address) }
            }
        }

        val blocks = mutableListOf<MutableList<DecodedInstruction.Valid>>()
        var current = mutableListOf<DecodedInstruction.Valid>()

        for (instruction in instructions) {
            if (current.isNotEmpty() && instruction.address in blockStarts) {
                blocks.add(current)
                current = mutableListOf()
            }
            current.add(instruction)
            if (instruction.isBlockTerminator) {
                blocks.add(current)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) blocks.add(current)

        return blocks.mapIndexed { index, block ->
            BasicBlockCandidate(
                id = "b$index",
                start = block.first().address,
                endInclusive = block.last().address,
                instructions = block,
                outgoingTargets = block.last().branchTargets,
            )
        }
    }
}
