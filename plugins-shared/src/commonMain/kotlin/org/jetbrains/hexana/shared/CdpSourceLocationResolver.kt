package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dwarf.DwarfInlineFrame
import org.jetbrains.hexana.dwarf.DwarfInlineFrameExtractor
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver.AddressSourceLocation
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver.SourceLocation
import org.jetbrains.hexana.dwarf.DwarfExprOp
import org.jetbrains.hexana.dwarf.DwarfInfoSection
import org.jetbrains.hexana.dwarf.DwarfLocationDescription
import org.jetbrains.hexana.dwarf.DwarfSubprogramInfo
import org.jetbrains.hexana.dwarf.DwarfTypeResolver
import org.jetbrains.hexana.dwarf.DwarfVariableInfo
import org.jetbrains.hexana.dwarf.DwarfVariableInfoExtractor
import org.jetbrains.hexana.dwarf.buildDieIndex
import org.jetbrains.hexana.io.CommonByteBuffer
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
    /** Lazily-invoked inline frame extractor; null when DWARF info is absent. */
    private val inlineExtractorFactory: (() -> DwarfInlineFrameExtractor?)? = null,
    /** Buffer held alongside the extractor; both are initialized once on first use. */
    private val bufferForInline: CommonByteBuffer? = null,
    /**
     * Reverse index built from CU-filtered entries only.
     * These entries use compDir for accurate file paths and produce
     * offsets that V8 reliably stops at.
     * Keyed by normalized file name (basename).
     */
    private val cuSourceToOffsets: Map<String, List<LineOffset>> = emptyMap(),
    /**
     * Reverse index built from unfiltered line-table entries.
     * These catch functions that fall outside the CU address range
     * (e.g. `add` in wasm-bindgen binaries).
     * Keyed by normalized file name (basename).
     */
    private val lineTableSourceToOffsets: Map<String, List<LineOffset>> = emptyMap(),
    /**
     * Full-path reverse index from CU-filtered entries.
     * Keyed by the full DWARF path (compDir-joined, forward slashes).
     */
    private val cuPathToOffsets: Map<String, List<LineOffset>> = emptyMap(),
    /**
     * Full-path reverse index from unfiltered line-table entries.
     * Keyed by the full DWARF path (compDir-joined, forward slashes).
     */
    private val lineTablePathToOffsets: Map<String, List<LineOffset>> = emptyMap(),
    /**
     * Type resolver for decoding DWARF type DIEs; non-null when DWARF info is present.
     * Shared with [memoryVariablesAt] and built once from the same accessor.
     */
    private val typeResolver: DwarfTypeResolver? = null,
    /**
     * Buffer used by [memoryVariablesAt] for type resolution.
     * Same buffer instance as [bufferForInline].
     */
    private val bufferForMemVar: CommonByteBuffer? = null,
) {

    /**
     * Reverse index: normalized source file name → list of (line → offset) entries.
     * File names are normalized to just the file name component for
     * flexible matching against IDE breakpoint file paths.
     */
    private val sourceToOffsets: Map<String, List<LineOffset>> = buildReverseIndex(locations)

    /**
     * All known full DWARF paths (forward-slash-normalized), from both
     * CU-filtered and line-table entries. Used for path-suffix matching.
     */
    private val allDwarfPaths: Set<String> = buildAllDwarfPaths(locations)

    /**
     * Cached inline-frame extractor. Initialized once on first call to
     * [inlinedFramesAt]; null when DWARF info is absent or factory is unset.
     */
    private val inlineExtractor: DwarfInlineFrameExtractor? by lazy {
        inlineExtractorFactory?.invoke()
    }

    data class LineOffset(val line: Int, val offset: ULong)

    /**
     * Returns the inlined call chain covering [moduleOffset] (module-absolute,
     * as reported by CDP `columnNumber` for wasm:// scripts), innermost first.
     *
     * The conversion from module-absolute to code-section-relative follows the
     * same arithmetic as [wasmOffsetToSource]:
     *   codeRelOffset = moduleOffset - codeSectionOffset  (clamped to 0)
     *
     * An empty list is returned when no inlined subroutines cover the offset,
     * when DWARF info is absent, or when the offset lies before the code section.
     *
     * @param moduleOffset the wasm byte offset from CDP (columnNumber)
     */
    fun inlinedFramesAt(moduleOffset: ULong): List<DwarfInlineFrame> {
        val ext = inlineExtractor ?: return emptyList()
        val buf = bufferForInline ?: return emptyList()
        val codeRelOffset = if (moduleOffset >= codeSectionOffset.toULong()) {
            (moduleOffset - codeSectionOffset.toULong()).toInt()
        } else {
            return emptyList()
        }
        return with(ext) { buf.inlinedFramesAt(codeRelOffset) }
    }

    /**
     * Returns DWARF-described variables for the subprogram covering [moduleOffset]
     * whose values live in Wasm linear memory (frame-relative or absolute-address).
     *
     * Only variables with `DW_OP_fbreg` or `DW_OP_addr` location descriptions are
     * returned; wasm-local-backed variables (already visible as V8 `wasmvalue` scope
     * entries) are omitted. The frame-base local index is extracted from the
     * subprogram's `DW_AT_frame_base` attribute; if it uses a wasm global instead of
     * a local (rare), that is recorded in [CdpMemoryVariable.frameBaseLocalIndex] as
     * negative to signal "use global N instead".
     *
     * Returns an empty list when DWARF info is absent, when no subprogram covers the
     * offset, or when parsing fails.
     *
     * @param moduleOffset module-absolute wasm byte offset (CDP `columnNumber` for wasm:// scripts)
     */
    fun memoryVariablesAt(moduleOffset: ULong): List<CdpMemoryVariable> {
        val resolver = typeResolver ?: return emptyList()
        val buf = bufferForMemVar ?: return emptyList()
        val codeRelOffset = if (moduleOffset >= codeSectionOffset.toULong()) {
            (moduleOffset - codeSectionOffset.toULong()).toInt()
        } else {
            return emptyList()
        }

        // Find subprogram covering codeRelOffset. subprograms are code-section-relative.
        val subprogram = subprograms.firstOrNull { sp ->
            val low = sp.lowPc ?: return@firstOrNull false
            val high = sp.highPc ?: return@firstOrNull false
            codeRelOffset in low until high
        } ?: return emptyList()

        // Determine which wasm local carries the frame base (shadow stack pointer).
        // DW_AT_frame_base is typically DW_OP_WASM_location local N + DW_OP_stack_value.
        val frameBaseOp = subprogram.frameBase?.operations?.firstOrNull()
        val frameBaseLocalIndex: Long? = when (frameBaseOp) {
            is DwarfExprOp.WasmLocal -> frameBaseOp.index
            // Wasm globals used as frame base: encode as -(index+1) to distinguish from locals
            is DwarfExprOp.WasmGlobal -> -(frameBaseOp.index + 1)
            is DwarfExprOp.WasmGlobalU32 -> -(frameBaseOp.index + 1)
            else -> null
        }

        // Raw DW_AT_type values are CU-relative (DW_FORM_ref4); the type
        // resolver's public API takes absolute buffer offsets.
        val cuBase = subprogram.cuOffset ?: return emptyList()

        val result = mutableListOf<CdpMemoryVariable>()
        for (v in subprogram.variables) {
            val name = v.name ?: continue
            val typeOffset = v.typeOffset?.let { cuBase + it } ?: continue
            val loc = effectiveLocation(v) ?: continue

            val primaryOp = loc.operations.firstOrNull() ?: continue
            when (primaryOp) {
                is DwarfExprOp.FrameBaseOffset -> {
                    // Variable in linear memory at frame_base + offset.
                    // Only emit when we know the frame base local.
                    if (frameBaseLocalIndex == null) continue
                    val typeInfo = with(resolver) { buf.resolveTypeAtOffset(typeOffset) } ?: continue
                    val byteSize = DwarfMemoryDecoder.typeByteSize(typeInfo)
                        ?.coerceAtMost(CdpMemoryVariable.MAX_SLAB_BYTES) ?: continue
                    result.add(
                        CdpMemoryVariable(
                            name = name,
                            type = typeInfo,
                            fbregOffset = primaryOp.offset,
                            frameBaseLocalIndex = frameBaseLocalIndex,
                            absoluteAddress = null,
                            byteSize = byteSize,
                        )
                    )
                }
                is DwarfExprOp.Address -> {
                    // Variable at a fixed linear-memory address (static/global).
                    val typeInfo = with(resolver) { buf.resolveTypeAtOffset(typeOffset) } ?: continue
                    val byteSize = DwarfMemoryDecoder.typeByteSize(typeInfo)
                        ?.coerceAtMost(CdpMemoryVariable.MAX_SLAB_BYTES) ?: continue
                    result.add(
                        CdpMemoryVariable(
                            name = name,
                            type = typeInfo,
                            fbregOffset = null,
                            frameBaseLocalIndex = null,
                            absoluteAddress = primaryOp.address,
                            byteSize = byteSize,
                        )
                    )
                }
                else -> {
                    // WasmLocal / WasmGlobal / StackValue etc. — already visible via
                    // V8 scope properties or not readable from linear memory; skip.
                }
            }
        }
        return result
    }

    /**
     * Finds the Wasm byte offset for a breakpoint at the given
     * source file and line number.
     *
     * @param fileName source file name (can be a full path or just the file name)
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
     *
     * When [fileName] contains path separators, path-suffix matching
     * is applied against known DWARF paths so that e.g.
     * `"mycrate/src/lib.rs"` resolves to the correct CU even when
     * another CU has a file with the same basename.
     */
    fun sourceToAllWasmOffsets(fileName: String, line: Int): List<ULong> {
        // DWARF lines are 1-based, IDE lines are 0-based
        val dwarfLine = line + 1

        // If the caller supplies a path (contains a separator), try
        // path-suffix matching first against the full-path indexes.
        val normalizedInput = fileName.replace('\\', '/')
        if ('/' in normalizedInput) {
            val pathOffsets = resolveByPathSuffix(normalizedInput, dwarfLine)
            if (pathOffsets.isNotEmpty()) return pathOffsets
        }

        val normalizedName = normalizeFileName(fileName)

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
     * Resolves offsets using the longest-suffix match of [inputPath]
     * against the full-path DWARF indexes. Tries progressively shorter
     * suffixes of the normalized input path until a match is found.
     *
     * For example, `"my_crate/src/lib.rs"` is tried before `"src/lib.rs"`
     * before `"lib.rs"`, preventing a bare-name collision with another
     * crate's `lib.rs`.
     */
    fun resolveByPathSuffix(inputPath: String, dwarfLine: Int): List<ULong> {
        val normalized = inputPath.replace('\\', '/')
        // Build suffix candidates from longest to shortest by iterating
        // the path segments. Start at index 0 (full path), then 1, etc.
        val segments = normalized.split('/')
        for (startIndex in 0 until segments.size) {
            val suffix = segments.drop(startIndex).joinToString("/")
            // Check CU index first (more reliable), then line-table
            val cuEntry = cuPathToOffsets.entries.firstOrNull { (k, _) ->
                k.endsWith(suffix) && (k.length == suffix.length || k[k.length - suffix.length - 1] == '/')
            }
            val ltEntry = lineTablePathToOffsets.entries.firstOrNull { (k, _) ->
                k.endsWith(suffix) && (k.length == suffix.length || k[k.length - suffix.length - 1] == '/')
            }
            val cuOffsets = resolveOffsetsFromEntries(cuEntry?.value, dwarfLine)
            val ltOffsets = resolveOffsetsFromEntries(ltEntry?.value, dwarfLine)
            val merged = (cuOffsets + ltOffsets).distinct()
            if (merged.isNotEmpty()) return merged
        }
        return emptyList()
    }

    /**
     * Returns all known full DWARF paths that share the given
     * basename. Used by callers that need to select among candidates.
     */
    fun dwarfPathsForBasename(basename: String): List<String> {
        return allDwarfPaths.filter { normalizeFileName(it) == basename }
    }

    /**
     * Returns all known full DWARF paths from the line table.
     */
    fun knownDwarfPaths(): Set<String> = allDwarfPaths

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
     * The returned [SourceLocation.file] is the full DWARF path
     * (compDir-joined, forward-slash-normalized) as recorded in the
     * DWARF line table. Callers that need only the basename can use
     * [normalizeFileName] or `substringAfterLast('/')`.
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

    /**
     * Returns `true` when the given function name matches a DWARF
     * subprogram entry by exact source or linkage name.
     */
    fun hasDwarfSubprogram(functionName: String?): Boolean {
        if (functionName == null) return false
        val normalizedName = functionName.removePrefix("$")
        return subprograms.any { sp ->
            sp.name == functionName || sp.name == normalizedName ||
                sp.linkageName == functionName || sp.linkageName == normalizedName
        }
    }

    /**
     * Builds a mapping from Wasm local indices to DWARF variable names
     * for the given function. V8 exposes Wasm locals to CDP as `$varN`
     * or name-section-derived properties; debug adapters can use this
     * map to present source-level parameter/local names.
     */
    fun wasmLocalNames(functionName: String?): Map<Long, String> {
        val matchingSubprograms = findMatchingSubprogramsByName(functionName)
        if (matchingSubprograms.isEmpty()) return emptyMap()

        val result = mutableMapOf<Long, String>()
        for (sp in matchingSubprograms) {
            for (v in sp.variables) {
                val name = v.name ?: continue
                val loc = effectiveLocation(v) ?: continue
                val primaryOp = loc.operations.firstOrNull() ?: continue
                if (primaryOp is DwarfExprOp.WasmLocal) {
                    if (primaryOp.index !in result) result[primaryOp.index] = name
                }
            }

            if (result.isEmpty()) {
                var index = 0L
                for (v in sp.variables.filter { it.isParameter }) {
                    val name = v.name ?: continue
                    if (index !in result) result[index] = name
                    index++
                }
                for (v in sp.variables.filter { !it.isParameter }) {
                    val name = v.name ?: continue
                    if (index !in result) result[index] = name
                    index++
                }
            }
        }
        return result
    }

    private fun findMatchingSubprogramsByName(functionName: String?): List<DwarfSubprogramInfo> {
        if (functionName == null) return emptyList()
        val normalizedName = functionName.removePrefix("$")

        val byName = subprograms.filter { sp ->
            sp.name == functionName || sp.name == normalizedName
        }
        if (byName.isNotEmpty()) return byName

        val byLinkage = subprograms.filter { sp ->
            sp.linkageName == functionName || sp.linkageName == normalizedName
        }
        if (byLinkage.isNotEmpty()) return byLinkage

        return subprograms.filter { sp ->
            sp.name?.let { functionName.startsWith(it) } == true ||
                sp.name?.let { normalizedName.startsWith(it) } == true ||
                sp.linkageName?.let { normalizedName.startsWith(it) } == true ||
                sp.linkageName?.let { functionName.startsWith(it) } == true
        }
    }

    private fun effectiveLocation(v: DwarfVariableInfo): DwarfLocationDescription? {
        v.location?.let { return it }
        return v.locationList.firstOrNull()?.location
    }

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

                val accessor2 = accessor
                val buffer2 = buffer
                val typeResolver = run {
                    val infoSection = accessor.debugInfoSection() ?: return@run null
                    val cus = with(infoSection) { buffer2.scanDwarfInfo() }
                    val allDies = buildMap {
                        for (cu in cus) {
                            for (child in cu.children) putAll(buildDieIndex(child))
                        }
                    }
                    DwarfTypeResolver(allDies, accessor, infoSection.contentOffset, cus.map { it.offset })
                }
                CdpSourceLocationResolver(
                    locations, codeSectionOffset, subprograms,
                    inlineExtractorFactory = {
                        if (accessor2.debugInfoSection() != null) DwarfInlineFrameExtractor(accessor2) else null
                    },
                    bufferForInline = buffer2,
                    cuSourceToOffsets = buildReverseIndex(cuLocations),
                    lineTableSourceToOffsets = buildReverseIndex(lineTableLocations),
                    cuPathToOffsets = buildFullPathIndex(cuLocations),
                    lineTablePathToOffsets = buildFullPathIndex(lineTableLocations),
                    typeResolver = typeResolver,
                    bufferForMemVar = buffer2,
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

        /**
         * Builds a reverse index keyed by the full DWARF path
         * (forward-slash-normalized). Used for path-suffix matching.
         */
        private fun buildFullPathIndex(locations: List<AddressSourceLocation>): Map<String, List<LineOffset>> {
            val result = mutableMapOf<String, MutableList<LineOffset>>()
            for (loc in locations) {
                val fullPath = loc.sourceLocation.file.replace('\\', '/')
                result.getOrPut(fullPath) { mutableListOf() }
                    .add(LineOffset(loc.sourceLocation.line, loc.offset))
            }
            return result.mapValues { (_, v) -> v.sortedBy { it.line } }
        }

        /**
         * Collects all distinct full DWARF paths from the location list
         * (forward-slash-normalized).
         */
        private fun buildAllDwarfPaths(locations: List<AddressSourceLocation>): Set<String> {
            return locations.map { it.sourceLocation.file.replace('\\', '/') }.toSet()
        }

        fun normalizeFileName(path: String): String {
            // Extract just the file name from the path for flexible matching.
            // Handle both Unix (/) and Windows (\) path separators.
            val lastSep = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            return if (lastSep >= 0) path.substring(lastSep + 1) else path
        }
    }
}
