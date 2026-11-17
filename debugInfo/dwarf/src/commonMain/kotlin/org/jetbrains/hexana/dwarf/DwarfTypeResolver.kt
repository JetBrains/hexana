package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation

/**
 * Resolved DWARF type information for a variable or field.
 *
 * All subclasses are resolved (transparent typedefs, const/volatile qualifiers
 * are stripped before constructing these). Reference cycles (e.g. a struct with
 * a pointer to itself) are broken at the [Pointer] level: the pointee is
 * resolved lazily and depth-limited via the resolving memo.
 */
sealed class DwarfTypeInfo {

    /**
     * A primitive scalar type (`DW_TAG_base_type`).
     *
     * @property name the type name (e.g. `"int"`, `"float"`)
     * @property byteSize the storage size in bytes
     * @property encoding the DWARF ATE encoding code (`DW_AT_encoding` value)
     */
    data class Base(
        val name: String,
        val byteSize: Int,
        val encoding: Int,
    ) : DwarfTypeInfo()

    /**
     * A pointer type (`DW_TAG_pointer_type`).
     *
     * @property pointee the resolved pointee type, or `null` when it is a
     *           `void*` or the pointee could not be resolved
     */
    data class Pointer(
        val pointee: DwarfTypeInfo?,
    ) : DwarfTypeInfo()

    /**
     * A struct or class type (`DW_TAG_structure_type` / `DW_TAG_class_type`).
     *
     * @property name the type name, or `null` for anonymous structs
     * @property byteSize the total storage size in bytes, or `null` if absent
     * @property members the ordered list of named fields
     */
    data class Struct(
        val name: String?,
        val byteSize: Int?,
        val members: List<Member>,
    ) : DwarfTypeInfo() {

        /**
         * A single field in a struct or class.
         *
         * @property name the field name
         * @property offset the byte offset from the start of the struct
         * @property type the resolved field type
         */
        data class Member(
            val name: String?,
            val offset: Int,
            val type: DwarfTypeInfo?,
        )
    }

    /**
     * An array type (`DW_TAG_array_type`).
     *
     * @property elementType the resolved element type
     * @property count the number of elements, or `null` if the bound is absent
     */
    data class Array(
        val elementType: DwarfTypeInfo?,
        val count: Int?,
    ) : DwarfTypeInfo()

    /**
     * An enumeration type (`DW_TAG_enumeration_type`).
     *
     * @property name the typedef-resolved name, or `null` for anonymous enums
     * @property byteSize the storage size in bytes
     * @property enumerators the ordered list of named constants
     */
    data class Enumeration(
        val name: String?,
        val byteSize: Int?,
        val enumerators: List<Enumerator>,
    ) : DwarfTypeInfo() {

        /** A single named constant in an enumeration. */
        data class Enumerator(val name: String, val value: Long)
    }

    /** A type whose DIE could not be decoded (unsupported tag or parse error). */
    data class Unknown(val tag: Tag) : DwarfTypeInfo()
}

/**
 * Resolves DWARF type DIEs into [DwarfTypeInfo] values.
 *
 * Construct one instance per parse session (it holds a memo per-buffer
 * operation). Call [resolveTypeAtOffset] with an ABSOLUTE buffer offset. Raw
 * `DW_FORM_ref4` attribute values are CU-relative: convert with
 * `cuOffset + value` (see [DwarfSubprogramInfo.cuOffset]) before calling.
 *
 * @param allDies a flat index of all DIEs in the compilation unit, keyed by
 *        their **absolute** buffer offset. Build this once per CU from
 *        [CompilationUnitHeader.children] plus all their descendants via
 *        [buildDieIndex].
 * @param accessor the DWARF section accessor for string resolution
 * @param infoContentOffset the `contentOffset` of the `.debug_info` section,
 *        used to convert section-relative `ref4` values to absolute buffer offsets
 */
class DwarfTypeResolver(
    private val allDies: Map<Int, DebugInformationEntry>,
    private val accessor: DwarfAccessor,
    private val infoContentOffset: Int,
    cuOffsets: List<Int> = emptyList(),
) {
    private val memo = mutableMapOf<Int, DwarfTypeInfo?>()

    /** Sorted absolute buffer offsets of CU headers, for CU-relative ref resolution. */
    private val sortedCuOffsets = cuOffsets.sorted()

    /**
     * Absolute buffer offset of the CU header containing the DIE at
     * [dieOffset] (the largest CU offset not exceeding it). Falls back to
     * [infoContentOffset] when no CU offsets were provided (legacy behavior,
     * correct only for the first CU).
     */
    private fun cuOffsetFor(dieOffset: Int): Int {
        var lo = 0
        var hi = sortedCuOffsets.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (sortedCuOffsets[mid] <= dieOffset) {
                best = sortedCuOffsets[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (best >= 0) best else infoContentOffset
    }

    /**
     * Resolves the type DIE at [absOffset] (the absolute buffer offset as
     * stored in [DebugInformationEntry.offset]) into a [DwarfTypeInfo].
     * Returns `null` when the offset does not map to a known DIE.
     *
     * Transparent wrappers (typedef, const, volatile) are unwrapped
     * automatically. Reference cycles are cut by returning `null` (the cycle
     * sentinel) when a re-entrant resolution is detected.
     */
    fun CommonByteBuffer.resolveTypeAtOffset(absOffset: Int): DwarfTypeInfo? {
        if (absOffset in memo) return memo[absOffset]
        // Insert a sentinel to break cycles before recursing.
        memo[absOffset] = null
        val result = resolveOnce(absOffset)
        memo[absOffset] = result
        return result
    }

    /**
     * Resolves a reference-class `DW_AT_type` attribute of [referringDie] to
     * a [DwarfTypeInfo], honoring the reference form's base: `DW_FORM_ref_addr`
     * values are `.debug_info`-section-relative, all other ref forms
     * (`DW_FORM_ref1/2/4/8/ref_udata`) are relative to the referring DIE's CU
     * header.
     */
    private fun CommonByteBuffer.resolveTypeRef(referringDie: DebugInformationEntry): DwarfTypeInfo? {
        val field = referringDie.attributes.find { it.attribute.name == Attribute.DW_AT_type } ?: return null
        val value = field.intValue ?: return null
        val base = when (field.attribute.form) {
            is Form.DW_FORM_ref_addr -> infoContentOffset
            else -> cuOffsetFor(referringDie.offset)
        }
        return resolveTypeAtOffset(base + value)
    }

    private fun CommonByteBuffer.resolveOnce(absOffset: Int): DwarfTypeInfo? {
        val die = allDies[absOffset] ?: return null
        return when (die.tag) {
            Tag.DW_TAG_typedef,
            Tag.DW_TAG_const_type,
            Tag.DW_TAG_volatile_type -> resolveTypeRef(die)

            Tag.DW_TAG_base_type -> {
                val name = resolveStringAttr(die, Attribute.DW_AT_name) ?: "?"
                val byteSize = die.attributes.find { it.attribute.name == Attribute.DW_AT_byte_size }?.intValue ?: 0
                val encoding = die.attributes.find { it.attribute.name == Attribute.DW_AT_encoding }?.intValue ?: 0
                DwarfTypeInfo.Base(name, byteSize, encoding)
            }

            Tag.DW_TAG_pointer_type -> DwarfTypeInfo.Pointer(resolveTypeRef(die))

            Tag.DW_TAG_structure_type,
            Tag.DW_TAG_class_type -> {
                val name = resolveStringAttr(die, Attribute.DW_AT_name)
                val byteSize = die.attributes.find { it.attribute.name == Attribute.DW_AT_byte_size }?.intValue
                val members = die.children
                    .filter { it.tag == Tag.DW_TAG_member }
                    .map { member ->
                        val memberName = resolveStringAttr(member, Attribute.DW_AT_name)
                        val memberOffset = member.attributes
                            .find { it.attribute.name == Attribute.DW_AT_data_member_location }?.intValue ?: 0
                        val memberType = resolveTypeRef(member)
                        DwarfTypeInfo.Struct.Member(memberName, memberOffset, memberType)
                    }
                DwarfTypeInfo.Struct(name, byteSize, members)
            }

            Tag.DW_TAG_array_type -> {
                val elemType = resolveTypeRef(die)
                val subrange = die.children.firstOrNull { it.tag == Tag.DW_TAG_subrange_type }
                val count = subrange?.attributes?.find { it.attribute.name == Attribute.DW_AT_count }?.intValue
                    ?: subrange?.attributes?.find { it.attribute.name == Attribute.DW_AT_upper_bound }?.intValue
                        ?.let { it + 1 }
                DwarfTypeInfo.Array(elemType, count)
            }

            Tag.DW_TAG_enumeration_type -> {
                // Name may come from a typedef parent; here we read directly.
                val name = resolveStringAttr(die, Attribute.DW_AT_name)
                val byteSize = die.attributes.find { it.attribute.name == Attribute.DW_AT_byte_size }?.intValue
                val enumerators = die.children
                    .filter { it.tag == Tag.DW_TAG_enumerator }
                    .mapNotNull { enumerator ->
                        val eName = resolveStringAttr(enumerator, Attribute.DW_AT_name) ?: return@mapNotNull null
                        val constVal = enumerator.attributes
                            .find { it.attribute.name == Attribute.DW_AT_const_value }
                        val eValue = constVal?.let { field ->
                            when (val v = field.value) {
                                is Long -> v
                                is Int -> v.toLong()
                                is ULong -> v.toLong()
                                is UInt -> v.toLong()
                                is UByte -> v.toLong()
                                is UShort -> v.toLong()
                                is Byte -> v.toLong()
                                is Short -> v.toLong()
                                else -> null
                            }
                        } ?: 0L
                        DwarfTypeInfo.Enumeration.Enumerator(eName, eValue)
                    }
                DwarfTypeInfo.Enumeration(name, byteSize, enumerators)
            }

            else -> DwarfTypeInfo.Unknown(die.tag)
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
                val strSection = accessor.debugStrSection() ?: return null
                val strOffset = field.intValue ?: return null
                crossSectionOperation {
                    position(strSection.contentOffset + strOffset)
                    readStringZ()
                }
            }
        }
    }
}

/**
 * Builds a flat DIE index (offset -> DIE) from all descendants of [root],
 * including [root] itself. This is the input to [DwarfTypeResolver].
 */
fun buildDieIndex(root: DebugInformationEntry): Map<Int, DebugInformationEntry> {
    val index = mutableMapOf<Int, DebugInformationEntry>()
    fun walk(die: DebugInformationEntry) {
        index[die.offset] = die
        for (child in die.children) walk(child)
    }
    walk(root)
    return index
}
