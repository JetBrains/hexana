package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer

class DwarfAbbrevSection(contentOffset: Int, contentSize: Int, accessor: DwarfAccessor): DwarfSection(contentOffset, contentSize, accessor) {
    fun CommonByteBuffer.scanDwarfAbbrev(): Map<UInt, List<DwarfAbbrevEntry>> {
        position(contentOffset)
        var tableEntryOffset = 0U
        val table = mutableMapOf<UInt, List<DwarfAbbrevEntry>>()
        while (position() < contentOffset + contentSize) {
            val tableEntry = readAbbrevEntry(contentOffset + contentSize)
            if (tableEntry is DwarfAbbrevTableEntry.End) {
                tableEntryOffset = position().toUInt() - contentOffset.toUInt()
                continue
            }
            val entry = table.getOrPut(tableEntryOffset){ mutableListOf() } as MutableList<DwarfAbbrevEntry>
            entry.add(tableEntry as DwarfAbbrevEntry)
        }
        return table
    }
}

private fun CommonByteBuffer.readAbbrevEntry(limit: Int): DwarfAbbrevTableEntry {
    val offset = position()
    val number = getULEB128()
    if (number == 0L)
        return DwarfAbbrevTableEntry.End
    val tag = getULEB128()
    val hasChildren = get() == 1.toByte()
    val attributes = mutableListOf<DwarfAttribute>()
    while (position() < limit) {
        if(!readAttributes(attributes))
            break
    }
    val size = position() - offset
    return DwarfAbbrevEntry(offset, size, number.toInt(), findTag(tag.toUShort()), hasChildren, attributes)
}

private fun CommonByteBuffer.readAttributes(attributes: MutableList<DwarfAttribute>): Boolean {
    val offset = position()
    val name = getULEB128().toULong()
    val rawForm = getULEB128().toUShort()
    if (name == 0UL && rawForm == 0.toUShort())
        return false
    val form = findForm(rawForm) ?: return false
    if (form == Form.DW_FORM_implicit_const) {
        val size = position() - offset
        val value = getSLEB128()
        attributes.add(DwarfAttribute(offset, size, findAttribute(name.toUShort()), form, value))
        return true
    }
    val size = position() - offset
    attributes.add(DwarfAttribute(offset, size, findAttribute(name.toUShort()), form))
    return true
}

sealed class DwarfAbbrevTableEntry {
    object End: DwarfAbbrevTableEntry()
}
class DwarfAbbrevEntry(val offset: Int, val size: Int, val number: Int, val tag: Tag, val hasChildren: Boolean, val attributes: List<DwarfAttribute>): DwarfAbbrevTableEntry()

class DwarfAttribute(val offset: Int, val size: Int, val name: Attribute, val form: Form, val value: Long? = null)