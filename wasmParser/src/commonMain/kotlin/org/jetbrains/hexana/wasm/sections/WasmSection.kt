package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange


sealed class WasmSection(
    val name: String,
    val sectionSize: Int,
    val sectionOffset: Int,
    val contentSize: Int,
    val contentOffset: Int,
) {


    fun CommonByteBuffer.consumeString(): String {
        val nameSize = getULEB128().toInt()
        val nameBytes = ByteArray(nameSize)
        get(nameBytes, 0, nameSize)
        return nameBytes.decodeToString()
    }

    val sectionRange: IntRange get() = sectionOffset until sectionOffset + sectionSize
    val contentRange: IntRange get() = contentOffset until contentOffset + contentSize

    open fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return emptyList()
    }
}

class WasmSectionUnresolved(
    val id: Byte,
    sectionSize: Int,
    sectionOffset: Int,
    contentSize: Int,
    contentOffset: Int
): WasmSection("Unknown", sectionSize, sectionOffset, contentSize, contentOffset)

internal fun CommonByteBuffer.consumeArray(block: (Int) -> Unit) {
    val vecCount = getULEB128().toInt()
    repeat(vecCount, block)
}

internal fun CommonByteBuffer.sizeFromOffset(offset: Int): Int = position() - offset
