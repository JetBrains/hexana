package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.CommonByteBuffer

class WasmSectionFunctions(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 3
    }

    fun CommonByteBuffer.parseFunctions(): List<Int> {
        position(offset)
        val typeRefs = mutableListOf<Int>()
        consumeArray {
            val typeidx = getULEB128().toInt()
            typeRefs.add(typeidx)
        }

        return typeRefs
    }
}
