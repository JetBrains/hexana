package org.jetbrains.hexana.disassembly.capstone

import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.DecodedInstruction

/**
 * Concrete [DecodedInstruction.Valid] produced by [CapstoneWasmDisassembler]. The
 * `renderedMnemonic` and `renderedOperands` strings are read out of the WASM
 * decoder once and cached here; [CapstoneWasmFormatter] reads them back via a
 * `as` cast — only formatters from the same disassembler are guaranteed to handle
 * these instances (see [org.jetbrains.hexana.disassembly.core.Disassembler]).
 */
internal class CapstoneWasmValidInstruction(
    override val address: ULong,
    override val byteRange: IntRange,
    override val arch: Arch,
    override val isBlockTerminator: Boolean,
    override val branchTargets: List<ULong>,
    internal val renderedMnemonic: String,
    internal val renderedOperands: String,
) : DecodedInstruction.Valid

internal class CapstoneWasmUnknownInstruction(
    override val address: ULong,
    override val byteRange: IntRange,
) : DecodedInstruction.Unknown
