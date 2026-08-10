package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation

/**
 * Describes a variable or formal parameter extracted from DWARF `.debug_info`.
 *
 * @property name the variable name (`DW_AT_name`) or `null` if absent
 * @property declFile the source file index (`DW_AT_decl_file`)
 * @property declLine the source line number (`DW_AT_decl_line`)
 * @property typeOffset the DIE offset of the type (`DW_AT_type`)
 * @property location the decoded location description, or `null` if no
 *           `DW_AT_location` attribute was present (variable optimized away).
 *           When the variable uses a location list, this is the first entry's
 *           location (for backward compatibility).
 * @property locationList the full location list when `DW_AT_location` references
 *           a `.debug_loc` entry. Empty when the location is a single expression.
 * @property isParameter `true` when the DIE tag is `DW_TAG_formal_parameter`
 */
data class DwarfVariableInfo(
    val name: String?,
    val declFile: Int?,
    val declLine: Int?,
    val typeOffset: Int?,
    val location: DwarfLocationDescription?,
    val locationList: List<DwarfLocationListEntry> = emptyList(),
    val isParameter: Boolean,
)

/**
 * Describes a subprogram (function) extracted from DWARF `.debug_info`.
 *
 * @property name the function name (`DW_AT_name`) or `null` if absent
 * @property linkageName the linkage name (`DW_AT_linkage_name`) or `null`
 * @property lowPc the start code address (`DW_AT_low_pc`)
 * @property highPc the end code address (`DW_AT_high_pc`)
 * @property frameBase the decoded frame base expression (`DW_AT_frame_base`), or `null`
 * @property frameBaseList the full location list for the frame base, if it uses a
 *           `.debug_loc` reference. Empty when the frame base is a single expression.
 * @property variables the local variables and parameters declared in this subprogram
 */
data class DwarfSubprogramInfo(
    val name: String?,
    val linkageName: String?,
    val lowPc: Int?,
    val highPc: Int?,
    val frameBase: DwarfLocationDescription?,
    val frameBaseList: List<DwarfLocationListEntry> = emptyList(),
    val variables: List<DwarfVariableInfo>,
    /**
     * Absolute buffer offset of the compilation unit header this subprogram
     * belongs to. CU-relative reference values (`DW_FORM_ref4`, e.g.
     * [DwarfVariableInfo.typeOffset]) resolve to `cuOffset + value`.
     */
    val cuOffset: Int? = null,
)

/**
 * Walks the DWARF `.debug_info` DIE tree and extracts variable location
 * metadata from `DW_TAG_subprogram`, `DW_TAG_variable`, and
 * `DW_TAG_formal_parameter` entries.
 *
 * This extractor decodes `DW_AT_location` and `DW_AT_frame_base`
 * attributes using [DwarfExpressionParser], including the Wasm-specific
 * `DW_OP_WASM_location` extension.
 */
class DwarfVariableInfoExtractor(private val dwarfAccessor: DwarfAccessor) {

    /**
     * Extracts all subprograms with their variables from the DWARF info.
     *
     * @return a list of [DwarfSubprogramInfo] entries, one per function
     */
    fun CommonByteBuffer.extractSubprograms(): List<DwarfSubprogramInfo> {
        val debugInfoSection = dwarfAccessor.debugInfoSection() ?: return emptyList()
        val compilationUnits = with(debugInfoSection) { scanDwarfInfo() }

        val result = mutableListOf<DwarfSubprogramInfo>()
        for (cu in compilationUnits) {
            val addressSize = cu.addressSize.toInt()
            for (die in cu.children) {
                collectSubprograms(die, addressSize, cu, result)
            }
        }
        return result
    }

    /**
     * Extracts all variables (from all subprograms) across all compilation units.
     */
    fun CommonByteBuffer.extractAllVariables(): List<DwarfVariableInfo> {
        return extractSubprograms().flatMap { it.variables }
    }

    /**
     * Extracts file-scope (global) variables declared directly under
     * `DW_TAG_compile_unit`, outside any `DW_TAG_subprogram`.
     */
    fun CommonByteBuffer.extractGlobalVariables(): List<DwarfVariableInfo> {
        val debugInfoSection = dwarfAccessor.debugInfoSection() ?: return emptyList()
        val compilationUnits = with(debugInfoSection) { scanDwarfInfo() }

        val result = mutableListOf<DwarfVariableInfo>()
        for (cu in compilationUnits) {
            val addressSize = cu.addressSize.toInt()
            for (die in cu.children) {
                collectTopLevelVariables(die, addressSize, cu, result)
            }
        }
        return result
    }

    private fun CommonByteBuffer.collectTopLevelVariables(
        die: DebugInformationEntry,
        addressSize: Int,
        cu: CompilationUnitHeader<*>,
        result: MutableList<DwarfVariableInfo>,
    ) {
        for (child in die.children) {
            if (child.tag == Tag.DW_TAG_variable) {
                result.add(extractVariable(child, addressSize, cu))
            }
        }
    }

    /**
     * Finds the subprogram containing the given code offset and returns
     * its variables. Returns an empty list if no match is found.
     *
     * @param codeOffset a Code-section-relative instruction offset
     */
    fun CommonByteBuffer.extractVariablesAtOffset(codeOffset: Int): List<DwarfVariableInfo> {
        val subprograms = extractSubprograms()
        return subprograms
            .filter { sp ->
                val low = sp.lowPc ?: return@filter false
                val high = sp.highPc ?: return@filter false
                codeOffset in low until high
            }
            .flatMap { it.variables }
    }

    private fun CommonByteBuffer.collectSubprograms(
        die: DebugInformationEntry,
        addressSize: Int,
        cu: CompilationUnitHeader<*>,
        result: MutableList<DwarfSubprogramInfo>,
    ) {
        if (die.tag == Tag.DW_TAG_subprogram) {
            result.add(extractSubprogram(die, addressSize, cu))
        }
        // Recurse into children (lexical blocks, inlined subroutines, namespaces, etc.)
        for (child in die.children) {
            collectSubprograms(child, addressSize, cu, result)
        }
    }

    private fun CommonByteBuffer.extractSubprogram(
        die: DebugInformationEntry,
        addressSize: Int,
        cu: CompilationUnitHeader<*>,
    ): DwarfSubprogramInfo {
        val name = resolveStringAttribute(die, Attribute.DW_AT_name, cu)
        val linkageName = resolveStringAttribute(die, Attribute.DW_AT_linkage_name, cu)
        val lowPc = die.attributes.find { it.attribute.name == Attribute.DW_AT_low_pc }?.intValue
        val highPcField = die.attributes.find { it.attribute.name == Attribute.DW_AT_high_pc }
        val highPc = if (highPcField != null) {
            when (highPcField.attribute.form) {
                is Form.DW_FORM_addr, is Form.DW_FORM_addrx,
                is Form.DW_FORM_addrx1, is Form.DW_FORM_addrx2,
                is Form.DW_FORM_addrx4, is Form.DW_FORM_addrx8 ->
                    highPcField.intValue

                else -> {
                    // DW_AT_high_pc as a constant offset from low_pc
                    val base = lowPc ?: 0
                    val offset = highPcField.intValue ?: 0
                    base + offset
                }
            }
        } else null

        val (frameBase, frameBaseList) = extractLocationWithList(die, Attribute.DW_AT_frame_base, addressSize)

        val variables = mutableListOf<DwarfVariableInfo>()
        collectVariables(die, addressSize, cu, variables)

        return DwarfSubprogramInfo(
            name = name,
            linkageName = linkageName,
            lowPc = lowPc,
            highPc = highPc,
            frameBase = frameBase,
            frameBaseList = frameBaseList,
            variables = variables,
            cuOffset = cu.offset,
        )
    }

    private fun CommonByteBuffer.collectVariables(
        die: DebugInformationEntry,
        addressSize: Int,
        cu: CompilationUnitHeader<*>,
        result: MutableList<DwarfVariableInfo>,
    ) {
        for (child in die.children) {
            when (child.tag) {
                Tag.DW_TAG_variable, Tag.DW_TAG_formal_parameter -> {
                    result.add(extractVariable(child, addressSize, cu))
                }
                Tag.DW_TAG_lexical_block, Tag.DW_TAG_inlined_subroutine -> {
                    // Recurse into lexical blocks and inlined calls
                    collectVariables(child, addressSize, cu, result)
                }
                else -> {}
            }
        }
    }

    private fun CommonByteBuffer.extractVariable(
        die: DebugInformationEntry,
        addressSize: Int,
        cu: CompilationUnitHeader<*>,
    ): DwarfVariableInfo {
        val name = resolveStringAttribute(die, Attribute.DW_AT_name, cu)
        val declFile = die.attributes.find { it.attribute.name == Attribute.DW_AT_decl_file }?.intValue
        val declLine = die.attributes.find { it.attribute.name == Attribute.DW_AT_decl_line }?.intValue
        val typeOffset = die.attributes.find { it.attribute.name == Attribute.DW_AT_type }?.intValue
        val (location, locationList) = extractLocationWithList(die, Attribute.DW_AT_location, addressSize)

        return DwarfVariableInfo(
            name = name,
            declFile = declFile,
            declLine = declLine,
            typeOffset = typeOffset,
            location = location,
            locationList = locationList,
            isParameter = die.tag == Tag.DW_TAG_formal_parameter,
        )
    }

    private fun extractLocation(
        die: DebugInformationEntry,
        attr: Attribute,
        addressSize: Int,
    ): DwarfLocationDescription? {
        val field = die.attributes.find { it.attribute.name == attr } ?: return null
        val bytes = field.value as? ByteArray ?: return null
        return DwarfExpressionParser.parse(bytes, addressSize)
    }

    /**
     * Extracts a location attribute that may be either an inline expression
     * (`DW_FORM_exprloc` / `DW_FORM_block*`) or a reference into the
     * `.debug_loc` section (`DW_FORM_sec_offset` / `DW_FORM_data4`).
     *
     * @return a pair of (single location description, location list).
     *         For inline expressions, the list is empty.
     *         For location list references, the single location is the
     *         first entry's location (or `null` if the list is empty).
     */
    private fun CommonByteBuffer.extractLocationWithList(
        die: DebugInformationEntry,
        attr: Attribute,
        addressSize: Int,
    ): Pair<DwarfLocationDescription?, List<DwarfLocationListEntry>> {
        val field = die.attributes.find { it.attribute.name == attr }
            ?: return Pair(null, emptyList())

        // Inline expression (DW_FORM_exprloc, DW_FORM_block*)
        val bytes = field.value as? ByteArray
        if (bytes != null) {
            return Pair(DwarfExpressionParser.parse(bytes, addressSize), emptyList())
        }

        // Location list reference (DW_FORM_sec_offset, DW_FORM_data4, etc.)
        val form = field.attribute.form
        if (form is Form.DW_FORM_sec_offset ||
            form is Form.DW_FORM_data4 ||
            form is Form.DW_FORM_data8
        ) {
            val locListOffset = field.intValue ?: return Pair(null, emptyList())
            val locSection = dwarfAccessor.debugLocSection()
                ?: return Pair(null, emptyList())
            val entries = crossSectionOperation {
                readLocationList(locSection, locListOffset, addressSize)
            }
            val firstLocation = entries.firstOrNull()?.location
            return Pair(firstLocation, entries)
        }

        return Pair(null, emptyList())
    }

    private fun CommonByteBuffer.resolveStringAttribute(
        die: DebugInformationEntry,
        attr: Attribute,
        cu: CompilationUnitHeader<*>,
    ): String? {
        val field = die.attributes.find { it.attribute.name == attr } ?: return null
        return when (field.value) {
            is String -> field.value
            else -> {
                val debugStrSection = dwarfAccessor.debugStrSection() ?: return null
                val strOffset = field.intValue ?: return null
                crossSectionOperation {
                    position(debugStrSection.contentOffset + strOffset)
                    readStringZ()
                }
            }
        }
    }
}
