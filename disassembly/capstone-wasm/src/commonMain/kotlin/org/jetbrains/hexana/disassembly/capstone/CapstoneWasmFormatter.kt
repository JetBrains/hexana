package org.jetbrains.hexana.disassembly.capstone

import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.AssemblyFormatter
import org.jetbrains.hexana.disassembly.core.AssemblySyntax
import org.jetbrains.hexana.disassembly.core.DecodedInstruction
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Formatter for instructions produced by [CapstoneWasmDisassembler]. The current
 * Rust backend renders one syntax per arch (Intel-ish for x86, GAS-ish for ARM,
 * debug-format for RISC-V); [syntax] only affects unknown-byte rendering for now.
 * Per-syntax routing on the Rust side is a follow-up.
 */
internal class CapstoneWasmFormatter(
    override val arch: Arch,
    override val syntax: AssemblySyntax,
) : AssemblyFormatter {
    override fun mnemonic(instruction: DecodedInstruction.Valid): String =
        (instruction as CapstoneWasmValidInstruction).renderedMnemonic

    override fun operands(instruction: DecodedInstruction.Valid): String =
        (instruction as CapstoneWasmValidInstruction).renderedOperands

    override fun CommonByteBuffer.formatUnknown(item: DecodedInstruction.Unknown): String {
        val bytes = (item.byteRange.first..item.byteRange.last).joinToString(", ") { i ->
            val b = this[i].toInt() and 0xFF
            "0x" + b.toString(16).padStart(2, '0')
        }
        return when (syntax) {
            AssemblySyntax.INTEL -> "db $bytes"
            else -> ".byte $bytes"
        }
    }
}
