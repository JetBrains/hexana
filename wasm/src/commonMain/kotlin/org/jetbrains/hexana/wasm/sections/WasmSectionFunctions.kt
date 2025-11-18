package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer


class WasmSectionFunctions(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 3
    }

    fun CommonByteBuffer.parseFunctions(): List<Int> {
        position(offset)
        val typeRefs = mutableListOf<Int>()
        consumeArray {
            typeRefs.add(getULEB128().toInt())
        }

        return typeRefs
    }
}
