package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation


open class DwarfStringSection(contentOffset: Int, contentSize: Int, accessor: DwarfAccessor) : DwarfSection(
    contentOffset,
    contentSize,
    accessor
) {
    fun CommonByteBuffer.scanDwarfStr():List<StringTableEntry> {
        position(contentOffset)
        val entries = mutableListOf<StringTableEntry>()
        var entryOffset = contentOffset
        while (position() < contentOffset + contentSize) {
           val v = get()
           if (v == 0.toByte()) {
               entries.add(StringTableEntry((entryOffset - contentOffset).toUInt(), entryOffset, position() - entryOffset - 1))
               entryOffset = position()
           }
        }
        return entries
    }
}

class DwarfStrSection(contentOffset: Int, contentSize: Int, accessor: DwarfAccessor):
    DwarfStringSection(contentOffset, contentSize, accessor)
class DwarfLineStrSection(contentOffset: Int, contentSize: Int, accessor: DwarfAccessor):
    DwarfStringSection(contentOffset, contentSize, accessor)

class StringTableEntry(val originalOffset: UInt, val offset: Int, val length: Int) {
    fun CommonByteBuffer.str(): String {
        return crossSectionOperation {
            position(offset)
            val buf = ByteArray(length)
            get(buf, 0, length)
            buf.decodeToString()
        }
    }
}

