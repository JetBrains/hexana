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
 * operation). Call [resolveTypeAtOffset] with a section-relative `.debug_info`
 * offset (e.g. the value of `DW_AT_type` / `DW_AT_abstract_origin`, which is
 * a `DW_FORM_ref4` section-relative integer).
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
) {
    private val memo = mutableMapOf<Int, DwarfTypeInfo?>()

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
     * Resolves a section-relative `DW_FORM_ref4` attribute value (e.g. from
     * `DW_AT_type`) to an absolute buffer offset by adding [infoContentOffset],
     * then delegates to [resolveTypeAtOffset].
     */
    private fun CommonByteBuffer.resolveRefAtOffset(sectionRelativeOffset: Int): DwarfTypeInfo? =
        resolveTypeAtOffset(infoContentOffset + sectionRelativeOffset)

    private fun CommonByteBuffer.resolveOnce(absOffset: Int): DwarfTypeInfo? {
        val die = allDies[absOffset] ?: return null
        return when (die.tag) {
            Tag.DW_TAG_typedef,
            Tag.DW_TAG_const_type,
            Tag.DW_TAG_volatile_type -> {
                val typeOffset = die.attributes.find { it.attribute.name == Attribute.DW_AT_type }?.intValue
                    ?: return null
                resolveRefAtOffset(typeOffset)
            }

            Tag.DW_TAG_base_type -> {
                val name = resolveStringAttr(die, Attribute.DW_AT_name) ?: "?"
                val byteSize = die.attributes.find { it.attribute.name == Attribute.DW_AT_byte_size }?.intValue ?: 0
                val encoding = die.attributes.find { it.attribute.name == Attribute.DW_AT_encoding }?.intValue ?: 0
                DwarfTypeInfo.Base(name, byteSize, encoding)
            }

            Tag.DW_TAG_pointer_type -> {
                val typeOffset = die.attributes.find { it.attribute.name == Attribute.DW_AT_type }?.intValue
                val pointee = typeOffset?.let { resolveRefAtOffset(it) }
                DwarfTypeInfo.Pointer(pointee)
            }

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
                        val memberTypeOffset = member.attributes
                            .find { it.attribute.name == Attribute.DW_AT_type }?.intValue
                        val memberType = memberTypeOffset?.let { resolveRefAtOffset(it) }
                        DwarfTypeInfo.Struct.Member(memberName, memberOffset, memberType)
                    }
                DwarfTypeInfo.Struct(name, byteSize, members)
            }

            Tag.DW_TAG_array_type -> {
                val elemTypeOffset = die.attributes.find { it.attribute.name == Attribute.DW_AT_type }?.intValue
                val elemType = elemTypeOffset?.let { resolveRefAtOffset(it) }
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
