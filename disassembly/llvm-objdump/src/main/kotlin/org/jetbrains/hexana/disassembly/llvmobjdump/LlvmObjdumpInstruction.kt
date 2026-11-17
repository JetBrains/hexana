package org.jetbrains.hexana.disassembly.llvmobjdump

import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.BasicBlockCandidate
import org.jetbrains.hexana.disassembly.core.DecodedInstruction

/**
 * A successfully-decoded instruction from llvm-objdump's text output.
 *
 * Implements [DecodedInstruction.Valid] (address, byteRange, arch, terminator
 * flag, branchTargets — i.e. jump-only targets for CFG consumers) and adds
 * the bits that come straight out of llvm-objdump's own parsing: the rendered
 * `mnemonic`, `operandsText`, full one-line `assemblyText`, raw `bytes`, and
 * `callTargets` (call-site target VAs separated from CFG branches because
 * calls return).
 *
 * `byteRange` is instruction-local (`0..size - 1`); llvm-objdump's text
 * parsing isn't backed by a single
 * [org.jetbrains.hexana.io.CommonByteBuffer], so the original bytes live on
 * [bytes] directly.
 *
 * Section names, symbol tables, file offsets, relocations, and source-line
 * info are intentionally NOT part of this type. Sections / symbols /
 * relocations come from the binary-format parsers (`parsing/elf|macho|pe`);
 * source lines come from DWARF (`debugInfo/dwarf`); file offsets are a
 * caller-side mapping from VA via the parsed section layout.
 */
data class LlvmObjdumpValidInstruction(
    override val address: ULong,
    override val byteRange: IntRange,
    override val arch: Arch,
    override val isBlockTerminator: Boolean,
    override val branchTargets: List<ULong>,
    val mnemonic: String,
    val operandsText: String,
    val assemblyText: String,
    val bytes: List<Int>,
    val callTargets: List<ULong>,
) : DecodedInstruction.Valid

/**
 * A byte range llvm-objdump could not decode. The builder doesn't currently
 * emit these (llvm elides bad-decode runs); kept for symmetry with the core
 * sealed hierarchy.
 */
data class LlvmObjdumpUnknownInstruction(
    override val address: ULong,
    override val byteRange: IntRange,
    val bytes: List<Int>,
) : DecodedInstruction.Unknown

/**
 * A function as llvm-objdump labels it (a contiguous instruction run under
 * one symbol heading). The `name` is the label llvm-objdump prints — not a
 * symbol-table lookup; that's a binary-format-parser concern.
 */
data class LlvmObjdumpDecodedFunction(
    val name: String,
    val entry: ULong,
    val instructions: List<LlvmObjdumpValidInstruction>,
    val basicBlocks: List<BasicBlockCandidate>,
)

/**
 * Result of running [LlvmObjdumpNativeDisassemblyBuilder] over a parsed
 * llvm-objdump text dump.
 */
data class LlvmObjdumpDisassemblyResult(
    val functions: List<LlvmObjdumpDecodedFunction>,
) {
    val instructions: List<LlvmObjdumpValidInstruction>
        get() = functions.flatMap { it.instructions }
}
