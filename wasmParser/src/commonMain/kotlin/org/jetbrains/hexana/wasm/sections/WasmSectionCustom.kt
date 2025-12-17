package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer

class WasmSectionCustom(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int):
    WasmSection("Custom", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 0
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.getCustomRecord(): WasmCustomRecord {
        position(contentOffset)
        val name = consumeString()
        val payload = consumeString()

        return WasmCustomRecord(name, payload)
    }
}


data class WasmCustomRecord(val name: String, val payload: String)
