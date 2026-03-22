package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.customSectionByName


class DwarfStrSection(contentOffset: Int, contentSize: Int): DwarfSection(contentOffset, contentSize) {
    fun CommonByteBuffer.scanDwarfStr():List<StringTableEntry> {
        position(contentOffset)
        val entries = mutableListOf<StringTableEntry>()
        var entryOffset = contentOffset
        while (position() < contentOffset + contentSize) {
           val v = get()
           if (v == 0.toByte()) {
               entries.add(StringTableEntry(entryOffset, position() - entryOffset - 1))
               entryOffset = position()
           }
        }
        return entries
    }

}

class StringTableEntry(val offset: Int, val length: Int) {
    fun CommonByteBuffer.str(): String {
        position(offset)
        val buf = ByteArray(length)
        get(buf, 0, length)
        return buf.decodeToString()
    }
}

fun BaseModule.debugStrSection() = customSectionByName(".debug_str")?.let { DwarfStrSection(it.contentOffset, it.contentSize) }

