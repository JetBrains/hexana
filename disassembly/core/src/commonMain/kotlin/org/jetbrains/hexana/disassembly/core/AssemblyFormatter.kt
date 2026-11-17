package org.jetbrains.hexana.disassembly.core

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Renders [DecodedInstruction]s as assembly text in a specific [syntax].
 *
 * Formatters are obtained from the [Disassembler] that produced the instructions
 * — a formatter only guarantees correct rendering for instructions originating
 * from its own backend.
 */
interface AssemblyFormatter {
    val arch: Arch
    val syntax: AssemblySyntax

    fun mnemonic(instruction: DecodedInstruction.Valid): String

    fun operands(instruction: DecodedInstruction.Valid): String

    /** Full single-line rendering: `"mov %rax, %rbx"` / `"mov rbx, rax"` / … */
    fun format(instruction: DecodedInstruction.Valid): String {
        val ops = operands(instruction)
        val mn = mnemonic(instruction)
        return if (ops.isEmpty()) mn else "$mn $ops"
    }

    /**
     * Rendering for an undecoded byte range — typically `.byte 0xab, 0xcd, …`
     * in AT&T / GAS dialects or `db 0ABh, 0CDh, …` in Intel / MASM. Reads the
     * raw bytes covered by [item].byteRange from the receiver buffer. Call as
     * `with(formatter) { buffer.formatUnknown(item) }`.
     */
    fun CommonByteBuffer.formatUnknown(item: DecodedInstruction.Unknown): String
}
