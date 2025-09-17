package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.CommonByteBuffer

sealed class WasmSection(val size: Int, val offset: Int) {
    fun CommonByteBuffer.consumeArray(block: (Int) -> Unit) {
        val vecCount = getULEB128().toInt()
        repeat(vecCount, block)
    }
}

class WasmSectionUnresolved(val id: Byte, size: Int, offset: Int): WasmSection(size, offset)