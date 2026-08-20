package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.format.WatSymbolNames
import org.jetbrains.hexana.providers.format.watFor
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.CodeEntryOffset
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.parseCodeEntry

/**
 * One disassembly row: the instruction's module-absolute byte [offset]
 * (-1 when it could not be deduced) and its rendered [instruction] text.
 */
data class WasmDisasmRow(val offset: Int, val instruction: String)

/**
 * Platform-neutral core of the debugger wasm disassembly, shared between the
 * IntelliJ "Wasm" tab ([WasmDisassemblySource]) and the VS Code Disassembly
 * View (extension-lib).
 *
 * The module is indexed once at [create]: code-entry offsets, the imported
 * function count, and the name section's function namemap. Function bodies
 * are parsed per [disassembleFunction] call — caching (and its thread-safety)
 * is the caller's concern, so this class holds no mutable state.
 */
class WasmDisassemblyIndex private constructor(
    private val buffer: CommonByteBuffer,
    /** Sorted list of (codeSection-local) function entries, index = local func idx (0-based). */
    val codeEntries: List<CodeEntryOffset>,
    /**
     * Number of imported functions (code-section indices are shifted by this value to get
     * the module-level function index; for offset look-ups only local indices are needed).
     */
    val importFuncCount: Int,
    /**
     * Function names from the name custom section, keyed by GLOBAL function
     * index (imports first, then defined functions). Null when the module has
     * no name section (stripped builds) — calls then render numerically.
     */
    private val funcNames: Map<Int, String>?,
) {

    /** Symbolic operand resolver passed to [watFor], same contract as the WAT view. */
    private val watSymbols = WatSymbolNames { funcIdx -> funcNames?.get(funcIdx) }

    companion object {
        /** Indexes the module, or returns null when [wasmBuffer] is absent or not parseable. */
        fun create(wasmBuffer: CommonByteBuffer?): WasmDisassemblyIndex? {
            if (wasmBuffer == null) return null
            return try {
                val buf = wasmBuffer.duplicate()
                val result = WasmParser().parse(buf.duplicate()) as? WasmParseSuccess ?: return null
                val module = result.wasmModule as? WasmModule ?: return null
                val codeSection = module.codeSection ?: return null
                val offsets = with(codeSection) { buf.duplicate().scanOffsets() }
                val importSection = module.importSection
                val importCount = if (importSection != null) {
                    with(importSection) { buf.duplicate().getImportRecords() }
                        .count { it.type is WasmTypeIndexExternalType }
                } else 0
                val names = with(module) {
                    getCustomSectionByName("name")?.let { customSection ->
                        with(customSection) { buf.duplicate().parseAsNameSection() }
                    }
                }?.functionSubsection?.namemap
                WasmDisassemblyIndex(buf, offsets, importCount, names)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Code-section-local index of the function whose entry range covers
     * [moduleAbsoluteOffset], or -1 when the offset is not in the code section.
     */
    fun functionIndexForOffset(moduleAbsoluteOffset: Int): Int {
        if (codeEntries.isEmpty()) return -1
        // Each CodeEntryOffset.offset is the start of the LEB128-size prefix (the full entry).
        var lo = 0
        var hi = codeEntries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val entry = codeEntries[mid]
            val entryEnd = entry.offset + entry.size
            when {
                moduleAbsoluteOffset < entry.offset -> hi = mid - 1
                moduleAbsoluteOffset >= entryEnd -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    /**
     * Disassembles the function at code-section-local [localIdx] into rows,
     * or returns an empty list when the body cannot be parsed.
     *
     * Rows carry deduced module-absolute offsets for singleton opcodes (see
     * [WasmInstrOffsets.deduceOffsets]); the function's terminal `end` byte —
     * consumed by the parser as its stop marker and absent from opCodes, yet a
     * legitimate pause target — is surfaced as the last row.
     */
    fun disassembleFunction(localIdx: Int): List<WasmDisasmRow> {
        val entry = codeEntries.getOrNull(localIdx) ?: return emptyList()
        return try {
            val buf = buffer.duplicate()
            val record = buf.parseCodeEntry(entry.offset)
            val rows = mutableListOf<WasmDisasmRow>()
            val flat = mutableListOf<FlattenedInstr>()
            appendRows(record.opCodes, 0, rows, flat)
            // Backward pass: deduce offsets for singleton opcodes (shared instances with no
            // per-instance offset). entryEnd = entry.offset + entry.size (exclusive boundary).
            if (WasmInstrOffsets.deduceOffsets(flat, entry.offset + entry.size)) {
                for (i in rows.indices) {
                    val deduced = flat[i].offset
                    if (deduced >= 0 && rows[i].offset < 0) {
                        rows[i] = rows[i].copy(offset = deduced)
                    }
                }
            }
            rows.add(WasmDisasmRow(offset = entry.offset + entry.size - 1, instruction = "end"))
            rows
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Emits one row per instruction using the same [watFor] mnemonic renderer as
     * the virtualized WAT view, recursing into block bodies (the parser nests
     * them inside Block/Loop/If/Else and TryTable rather than keeping the list
     * flat) with two-space indentation and a synthetic `end` row per block.
     *
     * [flat] receives one [FlattenedInstr] per row in the same order so that
     * [WasmInstrOffsets.deduceOffsets] can fill in offsets for singleton opcodes
     * (zero-operand shared instances whose inherited offset field is meaningless).
     */
    private fun appendRows(
        ops: List<OpCode>,
        depth: Int,
        rows: MutableList<WasmDisasmRow>,
        flat: MutableList<FlattenedInstr>,
    ) {
        val indent = "  ".repeat(depth)
        for (op in ops) {
            val recordedOffset = (op as? OpCodeWithOffset)?.offset?.takeIf { it > 0 } ?: -1
            val text = if (op is UnparsedOpCode) "<unparsed: ${op.e.message}>" else watFor(op, watSymbols)
            rows.add(WasmDisasmRow(recordedOffset, indent + text))
            when (op) {
                is OpCodes.Flow.Block -> { // also Loop, If, Else (subclasses)
                    // Block opcode: block-start entry (byteLen=null because recorded offset
                    // is the block's start and carried by the op), then body, then END.
                    flat.add(FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = recordedOffset))
                    appendRows(op.value, depth + 1, rows, flat)
                    rows.add(WasmDisasmRow(-1, indent + "end"))
                    flat.add(FlattenedInstr(isEndMarker = true, byteLen = 1, recordedOffset = -1))
                }
                is OpCodes.Flow.TryTable -> {
                    flat.add(FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = recordedOffset))
                    appendRows(op.code, depth + 1, rows, flat)
                    rows.add(WasmDisasmRow(-1, indent + "end"))
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
