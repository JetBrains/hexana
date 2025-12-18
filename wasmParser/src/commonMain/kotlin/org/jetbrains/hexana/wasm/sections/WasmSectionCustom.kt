package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer

class WasmSectionCustom(
    /**
     * [rawName] is the name written in the binary
     */
    val rawName: String,
    sectionSize: Int,
    sectionOffset: Int,
    contentSize: Int,
    contentOffset: Int,
): WasmSection(getSectionDisplayName(rawName), sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 0
    }

    constructor(
        rawName: String,
        s: WasmSectionUnresolved,
    ): this(rawName, s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.getCustomRecord(): WasmCustomRecord {
        position(contentOffset)
        val name = consumeString()
        val payload = consumeString()

        return WasmCustomRecord(name, payload)
    }
}


data class WasmCustomRecord(val name: String, val payload: String)

private fun getSectionDisplayName(rawName: String): String {
    if (rawName.isBlank()) {
        return "Custom"
    }
    return rawName
}