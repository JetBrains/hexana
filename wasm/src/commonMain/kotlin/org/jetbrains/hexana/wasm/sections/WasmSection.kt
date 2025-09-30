package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer


sealed class WasmSection(val size: Int, val offset: Int) {
    fun CommonByteBuffer.consumeArray(block: (Int) -> Unit) {
        val vecCount = getULEB128().toInt()
        repeat(vecCount, block)
    }

    fun CommonByteBuffer.consumeString(): String {
        val nameSize = getULEB128().toInt()
        val nameBytes = ByteArray(nameSize)
        get(nameBytes, 0, nameSize)
        return nameBytes.decodeToString()
    }
}

class WasmSectionUnresolved(val id: Byte, size: Int, offset: Int): WasmSection(size, offset)