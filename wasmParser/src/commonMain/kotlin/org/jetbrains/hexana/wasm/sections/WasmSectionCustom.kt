package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange

class WasmSectionCustom(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int):
    WasmSection("Custom", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 0
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return with (buffer) {
            val record = getCustomRecord()
            listOf(WasmNamedRange(record, record.name))
        }
    }

    fun CommonByteBuffer.getCustomRecord(): WasmCustomRecord {
        val offset = contentOffset
        position(offset)
        val name = consumeString()
        val payload = consumeString()

        return WasmCustomRecord(offset, sizeFromOffset(offset), name, payload)
    }
}


data class WasmCustomRecord(
    override val offset: Int,
    override val size: Int,
    val name: String,
    val payload: String
) : WasmRange
