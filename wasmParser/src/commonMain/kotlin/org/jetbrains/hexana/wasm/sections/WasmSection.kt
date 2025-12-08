package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer


sealed class WasmSection(
    val sectionSize: Int,
    val sectionOffset: Int,
    val contentSize: Int,
    val contentOffset: Int
) {


    fun CommonByteBuffer.consumeString(): String {
        val nameSize = getULEB128().toInt()
        val nameBytes = ByteArray(nameSize)
        get(nameBytes, 0, nameSize)
        return nameBytes.decodeToString()
    }
}

class WasmSectionUnresolved(
    val id: Byte,
    sectionSize: Int,
    sectionOffset: Int,
    contentSize: Int,
    contentOffset: Int
): WasmSection(sectionSize, sectionOffset, contentSize, contentOffset)

internal fun CommonByteBuffer.consumeArray(block: (Int) -> Unit) {
    val vecCount = getULEB128().toInt()
    repeat(vecCount, block)
}