package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer


class WasmSectionFunctions(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Functions", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 3
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.parseFunctions(): List<Int> {
        position(contentOffset)
        val typeRefs = mutableListOf<Int>()
        consumeArray {
            typeRefs.add(getULEB128().toInt())
        }

        return typeRefs
    }
}
