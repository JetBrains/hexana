package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver.AddressSourceLocation
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver.SourceLocation
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
 * Used by [CdpDebugProcess] to:
 * - translate IDE breakpoint positions (source file + line) into
 *   Wasm byte offsets for CDP `Debugger.setBreakpoint` calls
 * - translate Wasm byte offsets from CDP pause events back into
 *   source file positions for the IDE stack frame display
 */
internal class CdpSourceLocationResolver private constructor(
    private val locations: List<AddressSourceLocation>,
    private val codeSectionOffset: Int,
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
        val normalizedName = normalizeFileName(fileName)
        val entries = sourceToOffsets[normalizedName] ?: return null

        // DWARF lines are 1-based, IDE lines are 0-based
        val dwarfLine = line + 1

        // Find exact match first
        val exact = entries.find { it.line == dwarfLine }
        if (exact != null) return toModuleOffset(exact.offset)

        // Find the closest line that is >= the requested line
        // (snap forward to the next executable statement, which is
        // standard debugger behavior when a line has no direct mapping)
        val forward = entries.filter { it.line >= dwarfLine }.minByOrNull { it.line }
        if (forward != null) return toModuleOffset(forward.offset)

        // Fall back to the closest preceding line if no forward match
        val backward = entries.filter { it.line <= dwarfLine }.maxByOrNull { it.line }
        return backward?.let { toModuleOffset(it.offset) }
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
        private val LOG = Logger.getInstance(CdpSourceLocationResolver::class.java)

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
                val locations = with(resolver) { buffer.resolveLocations() }

                if (locations.isEmpty()) return null

                val codeSectionOffset = (module as? WasmModule)?.codeSection?.contentOffset ?: 0

                CdpSourceLocationResolver(locations, codeSectionOffset)
            } catch (e: Exception) {
                LOG.warn("Failed to parse DWARF line info from Wasm binary", e)
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
            // Extract just the file name from the path for flexible matching
            val lastSlash = path.lastIndexOf('/')
            return if (lastSlash >= 0) path.substring(lastSlash + 1) else path
        }
    }
}
