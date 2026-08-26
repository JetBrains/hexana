package org.jetbrains.hexana.breakpoints

import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryAnchorResolution
import org.jetbrains.hexana.shared.FlattenedInstr
import org.jetbrains.hexana.shared.WasmInstrOffsets
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodes

/**
 * Computes a new [BinaryAnchor] for a breakpoint when the binary has changed
 * (different build, different address layout). Returns null when rehoming is
 * not possible (name missing, ambiguous, out-of-bounds ordinal, etc.).
 *
 * Callers set [BinaryBreakpoint.rehomed] = true on success.
 */
interface AnchorRehomer {
    fun rehome(anchor: BinaryAnchor): BinaryAnchor?
}

/**
 * Rehomes a Wasm anchor by name + instruction ordinal.
 *
 * Requires [BinaryAnchor.Wasm.funcName] to be non-null. If multiple functions
 * share that name the rehome FAILS (null); a symbolic name is not a unique key.
 */
final class WasmAnchorRehomer(private val provider: WasmBinaryProvider) : AnchorRehomer {

    override fun rehome(anchor: BinaryAnchor): BinaryAnchor? {
        if (anchor !is BinaryAnchor.Wasm) return null
        val funcName = anchor.funcName ?: return null

        val namemap = provider.nameSection?.functionSubsection?.namemap ?: return null

        // Find all global indices that carry this name. Duplicates -> ambiguous -> null.
        val matching = namemap.filter { (_, name) -> name == funcName }
        if (matching.size != 1) return null

        val newFuncIdx = matching.keys.first()
        val codeIndex = newFuncIdx - provider.importFuncCount
        if (codeIndex < 0) return null

        val record = provider.parseCodeRecord(codeIndex) ?: return null

        // Build ascending instruction offsets using the same backward-pass that
        // WasmDisassemblyIndex.disassembleFunction uses.
        val flat = mutableListOf<FlattenedInstr>()
        flattenOps(record.opCodes, flat)
        val entryEnd = record.offset + record.size
        WasmInstrOffsets.deduceOffsets(flat, entryEnd)

        val opOffsets = flat.mapNotNull { f -> if (f.offset >= 0) f.offset.toLong() else null }

        val newOffset = BinaryAnchorResolution.offsetForInstrOrdinal(opOffsets, anchor.instrOrdinal) ?: return null
        return BinaryAnchor.Wasm(
            funcIdx = newFuncIdx,
            byteOffset = newOffset,
            funcName = funcName,
            instrOrdinal = anchor.instrOrdinal,
        )
    }

    companion object {
        /**
         * Flattens the (potentially nested) opcode list into a [FlattenedInstr] sequence
         * suitable for [WasmInstrOffsets.deduceOffsets]. Mirrors the logic in
         * WasmDisassemblyIndex.appendRows.
         */
        internal fun flattenOps(ops: List<OpCode>, flat: MutableList<FlattenedInstr>) {
            for (op in ops) {
                val recordedOffset = (op as? OpCodeWithOffset)?.offset?.takeIf { it > 0 } ?: -1
                when (op) {
                    is OpCodes.Flow.Block -> {
                        flat.add(FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = recordedOffset))
                        flattenOps(op.value, flat)
                        flat.add(FlattenedInstr(isEndMarker = true, byteLen = 1, recordedOffset = -1))
                    }
                    is OpCodes.Flow.TryTable -> {
                        flat.add(FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = recordedOffset))
                        flattenOps(op.code, flat)
                        flat.add(FlattenedInstr(isEndMarker = true, byteLen = 1, recordedOffset = -1))
                    }
                    else -> flat.add(
                        FlattenedInstr(
                            isEndMarker = false,
                            byteLen = WasmInstrOffsets.singletonByteLen(op),
                            recordedOffset = recordedOffset,
                        )
                    )
                }
            }
        }
    }
}

/**
 * Rehomes a native anchor by symbol name + offset within symbol.
 *
 * Requires [BinaryAnchor.Native.symbolName] to be non-null. Ambiguous names or
 * an offset beyond the symbol's size both result in null.
 */
final class ElfAnchorRehomer(private val provider: ElfBinaryProvider) : AnchorRehomer {

    override fun rehome(anchor: BinaryAnchor): BinaryAnchor? {
        if (anchor !is BinaryAnchor.Native) return null
        val symbolName = anchor.symbolName ?: return null

        val symbols = provider.symbols ?: return null
        val matching = symbols.filter { it.name == symbolName }
        if (matching.size != 1) return null

        val symbol = matching.first()
        val effectiveSize = if (symbol.size == 0uL) 1uL else symbol.size
        if (anchor.offsetInSymbol >= effectiveSize) return null

        return BinaryAnchor.Native(
            virtualAddress = symbol.value + anchor.offsetInSymbol,
            symbolName = symbolName,
            offsetInSymbol = anchor.offsetInSymbol,
        )
    }
}
