package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver.AddressSourceLocation
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver.SourceLocation
import org.jetbrains.hexana.dwarf.DwarfSubprogramInfo
import org.jetbrains.hexana.dwarf.DwarfVariableInfoExtractor
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor

/**
 * Resolves source file + line ↔ Wasm byte offset mappings using
 * DWARF line number program data embedded in a Wasm binary.
 *
 * Used by debug adapters to:
 * - translate breakpoint positions (source file + line) into
 *   Wasm byte offsets for CDP `Debugger.setBreakpoint` calls
 * - translate Wasm byte offsets from CDP pause events back into
 *   source file positions for stack frame display
 */
class CdpSourceLocationResolver private constructor(
    private val locations: List<AddressSourceLocation>,
    private val codeSectionOffset: Int,
    private val subprograms: List<DwarfSubprogramInfo> = emptyList(),
    /**
     * Reverse index built from CU-filtered entries only.
     * These entries use compDir for accurate file paths and produce
     * offsets that V8 reliably stops at.
     */
    private val cuSourceToOffsets: Map<String, List<LineOffset>> = emptyMap(),
    /**
     * Reverse index built from unfiltered line-table entries.
     * These catch functions that fall outside the CU address range
     * (e.g. `add` in wasm-bindgen binaries).
     */
    private val lineTableSourceToOffsets: Map<String, List<LineOffset>> = emptyMap(),
) {

    /**
     * Reverse index: normalized source file name → list of (line → offset) entries.
     * File names are normalized to just the file name component for
     * flexible matching against IDE breakpoint file paths.
     */
    private val sourceToOffsets: Map<String, List<LineOffset>> = buildReverseIndex(locations)

    data class LineOffset(val line: Int, val offset: ULong)

    /**
     * Finds the Wasm byte offset for a breakpoint at the given
     * source file and line number.
     *
     * @param fileName the source file name (can be a full path or just the file name)
     * @param line 0-based line number from the IDE breakpoint
     * @return the Wasm byte offset, or `null` if no mapping exists
     */
    fun sourceToWasmOffset(fileName: String, line: Int): ULong? {
        return sourceToAllWasmOffsets(fileName, line).firstOrNull()
    }

    /**
     * Returns all Wasm byte offsets that map to the given source
     * file and line number. Multiple offsets occur when the same
     * source line is compiled into several wasm functions (e.g.
     * wasm-bindgen wrappers alongside the real function body).
     *
     * Setting breakpoints at all returned offsets ensures the
     * debugger pauses in the correct function.
     */
    fun sourceToAllWasmOffsets(fileName: String, line: Int): List<ULong> {
        val normalizedName = normalizeFileName(fileName)

        // DWARF lines are 1-based, IDE lines are 0-based
        val dwarfLine = line + 1

        // Query both CU-filtered and line-table indexes independently.
        // CU-filtered entries have accurate file paths (via compDir)
        // and produce offsets V8 reliably stops at, but may miss
        // functions outside the CU address range. Line-table entries
        // catch those missing functions but may snap to different
        // lines. Querying both and merging ensures we get breakpoint
        // offsets from both resolution strategies.
        val cuOffsets = resolveOffsetsFromEntries(
            cuSourceToOffsets[normalizedName], dwarfLine
        )
        val ltOffsets = resolveOffsetsFromEntries(
            lineTableSourceToOffsets[normalizedName], dwarfLine
        )
        return (cuOffsets + ltOffsets).distinct()
    }

    /**
     * Resolves breakpoint offsets from a single set of entries using
     * exact match, snap-forward, or snap-backward strategies.
     */
    private fun resolveOffsetsFromEntries(
        entries: List<LineOffset>?,
        dwarfLine: Int,
    ): List<ULong> {
        if (entries.isNullOrEmpty()) return emptyList()

        // Find all exact matches first.
        val exact = entries.filter { it.line == dwarfLine }
        if (exact.isNotEmpty()) {
            return minOffsetPerFunction(exact)
        }

        // Snap forward to the next executable statement
        val forwardLine = entries.filter { it.line >= dwarfLine }.minByOrNull { it.line }?.line
        if (forwardLine != null) {
            return minOffsetPerFunction(entries.filter { it.line == forwardLine })
        }

        // Fall back to the closest preceding line
        val backwardLine = entries.filter { it.line <= dwarfLine }.maxByOrNull { it.line }?.line
        if (backwardLine != null) {
            return minOffsetPerFunction(entries.filter { it.line == backwardLine })
        }
        return emptyList()
    }

    /**
     * Groups matching DWARF line entries by function and returns one
     * offset per group, converted to module offsets.
     *
     * Multiple DWARF entries for the same source line within a single
     * function can appear at different points: the first occurrence is
     * typically the actual statement (often marked `prologue_end`),
     * while later occurrences may be epilogue/cleanup code or return
     * sequences that reference the same line. We keep the first
     * (lowest) offset per function — the point where the statement
     * actually begins executing.
     *
     * When DWARF subprogram info is available, entries are grouped by
     * function address ranges ([lowPc, highPc)). Otherwise, all
     * distinct offsets are returned as a fallback.
     */
    private fun minOffsetPerFunction(entries: List<LineOffset>): List<ULong> {
        if (entries.isEmpty()) return emptyList()
        if (subprograms.isNotEmpty()) {
            return minOffsetPerFunctionViaDwarf(entries)
        }
        // Fallback: return all distinct offsets when no subprogram
        // info is available (each offset becomes its own breakpoint).
        return entries.map { toModuleOffset(it.offset) }.distinct()
    }

    /**
     * Groups entries by DWARF subprogram address ranges and returns
     * the min offset per function. Each subprogram has [lowPc, highPc)
     * defining its code range; entries falling within the same range
     * belong to the same function.
     */
    private fun minOffsetPerFunctionViaDwarf(entries: List<LineOffset>): List<ULong> {
        // Group entries by the subprogram they belong to.
        // Key: subprogram index (or -1 for unmatched entries).
        val groups = mutableMapOf<Int, MutableList<LineOffset>>()
        for (entry in entries) {
            val spIndex = subprograms.indexOfFirst { sp ->
                val low = sp.lowPc ?: return@indexOfFirst false
                val high = sp.highPc ?: return@indexOfFirst false
                entry.offset.toLong() >= low && entry.offset.toLong() < high
            }
            groups.getOrPut(spIndex) { mutableListOf() }.add(entry)
        }
        return groups.values.map { group -> toModuleOffset(group.minOf { it.offset }) }
    }

    /**
     * Converts a DWARF code-section-relative offset to an absolute
     * module offset that V8 CDP expects in `columnNumber`.
     */
    private fun toModuleOffset(dwarfOffset: ULong): ULong {
        return dwarfOffset + codeSectionOffset.toULong()
    }

    /**
     * Resolves a Wasm byte offset to a source location.
     *
     * @param offset the Wasm byte offset (from CDP columnNumber in wasm scripts)
     * @return the source location, or `null` if no mapping exists
     */
    fun wasmOffsetToSource(offset: ULong): SourceLocation? {
        // Convert module-absolute offset back to code-section-relative
        val codeRelOffset = if (offset >= codeSectionOffset.toULong()) {
            offset - codeSectionOffset.toULong()
        } else {
            offset
        }
        // Find the entry with the largest offset that is <= the given offset
        val entry = locations.findLast { it.offset <= codeRelOffset }
        return entry?.sourceLocation
    }

    /**
     * Returns all known source file names (normalized) that have
     * DWARF line mappings.
     */
    fun knownSourceFiles(): Set<String> = sourceToOffsets.keys

    companion object {

        /**
         * Creates a resolver from raw Wasm binary bytes by parsing
         * the DWARF line number program sections.
         *
         * @return a [CdpSourceLocationResolver], or `null` if the
         *         binary has no DWARF line info or parsing fails
         */
        fun create(wasmBytes: ByteArray): CdpSourceLocationResolver? {
            return try {
                val buffer = wasmBytes.toCommonByteBuffer()
                val parseResult = WasmParser().parse(buffer) as? WasmParseSuccess
                    ?: return null
                val module = parseResult.wasmModule as? BaseModule ?: return null
                val accessor = WasmDwarfAccessor(module)

                if (accessor.debugLineSection() == null) return null

                val resolver = DwarfSourceLocationResolver(accessor)
                // Merge entries from both resolution strategies:
                // - resolveLocations() filters by CU [low_pc, high_pc) and
                //   uses compDir for accurate file paths — these entries
                //   produce offsets that V8 reliably stops at.
                // - resolveLineTableLocations() reads the line table without
                //   CU filtering — this catches functions (e.g. `add` in
                //   wasm-bindgen binaries) that fall outside the CU range.
                // Merging both ensures we get breakpoint offsets for all
                // functions while keeping the reliable CU-filtered entries.
                val cuLocations = with(resolver) { buffer.resolveLocations() }
                val lineTableLocations = with(resolver) { buffer.resolveLineTableLocations() }
                val cuOffsets = cuLocations.map { it.offset }.toSet()
                val locations = (cuLocations + lineTableLocations.filter { it.offset !in cuOffsets })
                    .sortedBy { it.offset }

                if (locations.isEmpty()) return null

                val codeSectionOffset = (module as? WasmModule)?.codeSection?.contentOffset ?: 0

                val extractor = DwarfVariableInfoExtractor(accessor)
                val subprograms = try {
                    with(extractor) { buffer.extractSubprograms() }
                } catch (_: Exception) {
                    emptyList()
                }

                CdpSourceLocationResolver(
                    locations, codeSectionOffset, subprograms,
                    cuSourceToOffsets = buildReverseIndex(cuLocations),
                    lineTableSourceToOffsets = buildReverseIndex(lineTableLocations),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun buildReverseIndex(locations: List<AddressSourceLocation>): Map<String, List<LineOffset>> {
            val result = mutableMapOf<String, MutableList<LineOffset>>()
            for (loc in locations) {
                val normalized = normalizeFileName(loc.sourceLocation.file)
                result.getOrPut(normalized) { mutableListOf() }
                    .add(LineOffset(loc.sourceLocation.line, loc.offset))
            }
            // Sort each list by line for efficient lookup
            return result.mapValues { (_, v) -> v.sortedBy { it.line } }
        }

        private fun normalizeFileName(path: String): String {
            // Extract just the file name from the path for flexible matching.
            // Handle both Unix (/) and Windows (\) path separators.
            val lastSep = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            return if (lastSep >= 0) path.substring(lastSep + 1) else path
        }
    }
}
