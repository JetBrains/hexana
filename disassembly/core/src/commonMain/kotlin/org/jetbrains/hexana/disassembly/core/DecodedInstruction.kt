package org.jetbrains.hexana.disassembly.core

/**
 * One item in a decoded instruction stream. Implementations are typically lazy
 * proxies over a backend disassembler (e.g. Capstone-WASM): property reads may
 * trigger a decode call across the WASM boundary on first access.
 *
 * Carries ISA-level structural facts only. The bytes themselves are referenced
 * as a range into the source [org.jetbrains.hexana.io.CommonByteBuffer] passed
 * to the [Disassembler] — no per-instruction copies. Textual rendering
 * (mnemonic, operands) is produced by an [AssemblyFormatter], not by the
 * instruction itself, because the same opcode has multiple syntactic
 * projections (AT&T vs Intel, ARM-GAS vs ARM-vendor, …).
 *
 * Relocations, symbols, source lines, and xrefs are joined separately by the
 * listing layer — they are not part of an instruction.
 */
sealed interface DecodedInstruction {
    val address: ULong

    /**
     * End-inclusive byte range within the source buffer, in the buffer's local
     * coordinates (offset 0 = `baseAddress` passed to the disassembler).
     */
    val byteRange: IntRange

    val size: Int
        get() = byteRange.last - byteRange.first + 1

    /** Successfully decoded ISA instruction. */
    interface Valid : DecodedInstruction {
        val arch: Arch
        val isBlockTerminator: Boolean
        val branchTargets: List<ULong>
    }

    /**
     * A contiguous run of bytes the decoder did not turn into instructions —
     * data interleaved with code, ARM/Thumb mode mismatch, padding, or
     * mid-instruction garbage. Implementations may coalesce adjacent
     * undecodable bytes into a single [Unknown].
     */
    interface Unknown : DecodedInstruction
}
