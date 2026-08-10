package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation

/**
 * A single frame in an inlined call chain, representing one
 * `DW_TAG_inlined_subroutine` DIE.
 *
 * @property name the resolved function name (`DW_AT_name` from the abstract
 *           origin), or `null` if unavailable
 * @property linkageName the mangled linkage name (`DW_AT_linkage_name`), or `null`
 * @property callFile the source file path at the call site (`DW_AT_call_file`
 *           resolved via the CU line program), or `null` if unresolvable
 * @property callLine the source line at the call site (`DW_AT_call_line`),
 *           or `null` if absent
 * @property callColumn the source column at the call site (`DW_AT_call_column`),
 *           or `null` if absent
 * @property depth the nesting depth within the containing subprogram; 1 means
 *           directly inlined into the subprogram, 2 means inlined inside another
 *           inlined subroutine, and so on
 */
data class DwarfInlineFrame(
    val name: String?,
    val linkageName: String?,
    val callFile: String?,
    val callLine: Int?,
    val callColumn: Int?,
    val depth: Int,
)

/**
 * Walks `.debug_info` DIE trees and resolves `DW_TAG_inlined_subroutine`
 * entries, providing an API to query the inlined call chain covering a given
 * code offset.
 *
 * Only DWARF v4 format with `DW_AT_low_pc`/`DW_AT_high_pc` is supported for
 * PC coverage of inlined subroutines. `DW_AT_ranges` is not yet supported
 * (those inlined subroutines are skipped).
 *
 * @param dwarfAccessor the section accessor for the binary
 */
class DwarfInlineFrameExtractor(private val dwarfAccessor: DwarfAccessor) {

    /**
     * Returns the inlined call chain covering [codeOffset], innermost frame
     * first. Returns an empty list when the offset is not covered by any
     * inlined subroutine, or when the DWARF sections are absent.
     *
     * The [codeOffset] space is the same as the `DW_AT_low_pc` values used in
     * the existing [DwarfVariableInfoExtractor] (Code-section-relative).
     */
    fun CommonByteBuffer.inlinedFramesAt(codeOffset: Int): List<DwarfInlineFrame> {
        val debugInfoSection = dwarfAccessor.debugInfoSection() ?: return emptyList()
        val debugLineSection = dwarfAccessor.debugLineSection()
        val compilationUnits = with(debugInfoSection) { scanDwarfInfo() }

        val infoContentOffset = debugInfoSection.contentOffset
        val result = mutableListOf<DwarfInlineFrame>()
        for (cu in compilationUnits) {
            val addressSize = cu.addressSize.toInt()
            val lineHeaders = resolveLineHeaders(debugLineSection)

            // Build a flat DIE index for this CU (keyed by absolute buffer offset).
            val cuIndex = mutableMapOf<Int, DebugInformationEntry>()
            for (topLevelDie in cu.children) {
                cuIndex.putAll(buildDieIndex(topLevelDie))
            }

            // Resolve call_file index -> path using the CU's line unit header.
            val cuRoot = cu.children.firstOrNull() ?: continue
            val stmtListOffset = cuRoot.attributes
                .find { it.attribute.name == Attribute.DW_AT_stmt_list }?.intValue
            val lineHeader = stmtListOffset?.let { lineHeaders[it] }

            for (topLevelDie in cu.children) {
                collectInlinedFrames(
                    die = topLevelDie,
                    targetOffset = codeOffset,
                    addressSize = addressSize,
                    dieIndex = cuIndex,
                    infoContentOffset = infoContentOffset,
                    lineHeader = lineHeader,
                    depth = 0,
                    result = result,
                )
            }
        }

        // Sort innermost (deepest) first.
        result.sortByDescending { it.depth }
        return result
    }

    private fun CommonByteBuffer.collectInlinedFrames(
        die: DebugInformationEntry,
        targetOffset: Int,
        addressSize: Int,
        dieIndex: Map<Int, DebugInformationEntry>,
        infoContentOffset: Int,
        lineHeader: DwarfLineUnitHeader<*>?,
        depth: Int,
        result: MutableList<DwarfInlineFrame>,
    ) {
        when (die.tag) {
            Tag.DW_TAG_subprogram -> {
                // Only recurse into subprograms whose PC range contains the target.
                if (!subprogramContains(die, targetOffset)) return
                for (child in die.children) {
                    collectInlinedFrames(child, targetOffset, addressSize, dieIndex, infoContentOffset, lineHeader, depth, result)
                }
            }

            Tag.DW_TAG_inlined_subroutine -> {
                val (lowPc, highPc) = extractPcRange(die) ?: return
                if (targetOffset < lowPc || targetOffset >= highPc) return

                val frame = buildFrame(die, dieIndex, infoContentOffset, lineHeader, depth + 1)
                result.add(frame)

                // Recurse into nested inlined subroutines.
                for (child in die.children) {
                    collectInlinedFrames(child, targetOffset, addressSize, dieIndex, infoContentOffset, lineHeader, depth + 1, result)
                }
            }

            Tag.DW_TAG_lexical_block,
            Tag.DW_TAG_compile_unit,
            Tag.DW_TAG_namespace -> {
                // Descend into these containers without incrementing depth.
                for (child in die.children) {
                    collectInlinedFrames(child, targetOffset, addressSize, dieIndex, infoContentOffset, lineHeader, depth, result)
                }
            }

            else -> {}
        }
    }

    /** Returns true when the subprogram's PC range covers [offset]. */
    private fun subprogramContains(die: DebugInformationEntry, offset: Int): Boolean {
        val lowPc = die.attributes.find { it.attribute.name == Attribute.DW_AT_low_pc }?.intValue
            ?: return false
        val highPcField = die.attributes.find { it.attribute.name == Attribute.DW_AT_high_pc }
            ?: return false
        val highPc = when (highPcField.attribute.form) {
            is Form.DW_FORM_addr, is Form.DW_FORM_addrx,
            is Form.DW_FORM_addrx1, is Form.DW_FORM_addrx2,
            is Form.DW_FORM_addrx4, is Form.DW_FORM_addrx8 ->
                highPcField.intValue ?: return false
            else -> lowPc + (highPcField.intValue ?: return false)
        }
        return offset in lowPc until highPc
    }

    /**
     * Extracts the `[lowPc, highPc)` pair from an inlined subroutine DIE.
     * Returns `null` when either attribute is absent or ranges are used
     * (not yet supported).
     */
    private fun extractPcRange(die: DebugInformationEntry): Pair<Int, Int>? {
        val lowPcField = die.attributes.find { it.attribute.name == Attribute.DW_AT_low_pc }
            ?: return null
        val lowPc = lowPcField.intValue ?: return null
        val highPcField = die.attributes.find { it.attribute.name == Attribute.DW_AT_high_pc }
            ?: return null
        val highPc = when (highPcField.attribute.form) {
            is Form.DW_FORM_addr, is Form.DW_FORM_addrx,
            is Form.DW_FORM_addrx1, is Form.DW_FORM_addrx2,
            is Form.DW_FORM_addrx4, is Form.DW_FORM_addrx8 ->
                highPcField.intValue ?: return null
            else -> lowPc + (highPcField.intValue ?: return null)
        }
        return lowPc to highPc
    }

    private fun CommonByteBuffer.buildFrame(
        die: DebugInformationEntry,
        dieIndex: Map<Int, DebugInformationEntry>,
        infoContentOffset: Int,
        lineHeader: DwarfLineUnitHeader<*>?,
        depth: Int,
    ): DwarfInlineFrame {
        val origin = resolveAbstractOrigin(die, dieIndex, infoContentOffset)

        val name = resolveStringAttr(origin ?: die, Attribute.DW_AT_name)
        val linkageName = resolveStringAttr(origin ?: die, Attribute.DW_AT_linkage_name)

        val callFileIndex = die.attributes.find { it.attribute.name == Attribute.DW_AT_call_file }?.intValue
        val callFile = callFileIndex?.let { lineHeader?.let { h -> resolveFileName(h, it) } }
        val callLine = die.attributes.find { it.attribute.name == Attribute.DW_AT_call_line }?.intValue
        val callColumn = die.attributes.find { it.attribute.name == Attribute.DW_AT_call_column }?.intValue

        return DwarfInlineFrame(
            name = name,
            linkageName = linkageName,
            callFile = callFile,
            callLine = callLine,
            callColumn = callColumn,
            depth = depth,
        )
    }

    /**
     * Follows `DW_AT_abstract_origin` and optionally `DW_AT_specification` to
     * find the origin DIE carrying the function name. Returns `null` when the
     * reference cannot be resolved.
     *
     * [infoContentOffset] is the `.debug_info` section's `contentOffset`,
     * used to convert section-relative `DW_FORM_ref4` values to absolute
     * buffer offsets for DIE index lookups.
     */
    private fun resolveAbstractOrigin(
        die: DebugInformationEntry,
        dieIndex: Map<Int, DebugInformationEntry>,
        infoContentOffset: Int,
    ): DebugInformationEntry? {
        val originSectionOffset = die.attributes
            .find { it.attribute.name == Attribute.DW_AT_abstract_origin }?.intValue
            ?: return null
        val origin = dieIndex[infoContentOffset + originSectionOffset] ?: return null
        // Follow DW_AT_specification if present (for out-of-line definitions).
        val specSectionOffset = origin.attributes
            .find { it.attribute.name == Attribute.DW_AT_specification }?.intValue
        return if (specSectionOffset != null) {
            dieIndex[infoContentOffset + specSectionOffset] ?: origin
        } else {
            origin
        }
    }

    private fun CommonByteBuffer.resolveStringAttr(
        die: DebugInformationEntry,
        attr: Attribute,
    ): String? {
        val field = die.attributes.find { it.attribute.name == attr } ?: return null
        return when (field.value) {
            is String -> field.value
            else -> {
                val strSection = dwarfAccessor.debugStrSection() ?: return null
                val strOffset = field.intValue ?: return null
                crossSectionOperation {
                    position(strSection.contentOffset + strOffset)
                    readStringZ()
                }
            }
        }
    }

    /**
     * Resolves a DWARF v4 line-program file index to a source file path.
     * In DWARF v4 the file table is 1-based; index 0 means the primary source file.
     */
    private fun resolveFileName(header: DwarfLineUnitHeader<*>, fileIndex: Int): String? {
        return when (header) {
            is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V4 -> {
                val idx = if (fileIndex < 1) return null else fileIndex - 1
                val entry = header.includeFiles.getOrNull(idx) ?: return null
                val directory = when (val d = entry.dirIndex) {
                    0 -> ""
                    else -> header.includeDirectories.getOrNull(d - 1) ?: ""
                }
                dwarfPath(directory, entry.fileName)
            }
            is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V4 -> {
                val idx = if (fileIndex < 1) return null else fileIndex - 1
                val entry = header.includeFiles.getOrNull(idx) ?: return null
                val directory = when (val d = entry.dirIndex) {
                    0 -> ""
                    else -> header.includeDirectories.getOrNull(d - 1) ?: ""
                }
                dwarfPath(directory, entry.fileName)
            }
            // DWARF v5 file name resolution requires reading .debug_line_str;
            // not yet supported here.
            else -> null
        }
    }

    private fun CommonByteBuffer.resolveLineHeaders(
        lineSection: DwarfLineSection?,
    ): Map<Int, DwarfLineUnitHeader<*>> {
        lineSection ?: return emptyMap()
        return with(lineSection) { scanLineTable() }
            .associateBy { it.contentOffset - lineSection.contentOffset }
    }
}

private fun dwarfPath(directory: String, fileName: String): String {
    if (fileName.isEmpty()) return directory
    // Windows absolute path: single ASCII letter followed by ':'
    if (fileName.length >= 2 && fileName[0].code in 'A'.code..'z'.code && fileName[1] == ':') {
        return fileName.replace('\\', '/')
    }
    if (directory.isEmpty()) return fileName
    val sep = if (directory.endsWith("/") || directory.endsWith("\\")) "" else "/"
    return (directory + sep + fileName).replace('\\', '/')
}
