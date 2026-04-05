package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.customSectionByName


open class DwarfStringSection(contentOffset: Int, contentSize: Int): DwarfSection(contentOffset, contentSize) {
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

class DwarfStrSection(contentOffset: Int, contentSize: Int): DwarfStringSection(contentOffset, contentSize)
class DwarfLineStrSection(contentOffset: Int, contentSize: Int): DwarfStringSection(contentOffset, contentSize)

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

fun BaseModule.debugStrSection() = customSectionByName(".debug_str")?.let { DwarfStrSection(it.contentOffset, it.contentSize) }
fun BaseModule.debugLineStrSection() = customSectionByName(".debug_line_str")?.let { DwarfStrSection(it.contentOffset, it.contentSize) }

