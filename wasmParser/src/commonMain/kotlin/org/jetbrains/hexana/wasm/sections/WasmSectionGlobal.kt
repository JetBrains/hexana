package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.valueType

class WasmSectionGlobal(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Globals", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 6
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return with (buffer) {
            scan().mapIndexed { index, record ->
                WasmNamedRange(record, "global $index")
            }
        }
    }

    /**
     * Parses as many global entries as possible. On the first undecodable entry,
     * stops and returns the partial list paired with the byte offset of that
     * entry (null second means the section parsed fully).
     */
    fun CommonByteBuffer.scanPartial(): Pair<MutableList<WasmGlobalRecord>, Int?> {
        position(contentOffset)
        val records = mutableListOf<WasmGlobalRecord>()
        var failedOffset: Int? = null

        val count = getULEB128().toInt()
        for (i in 0 until count) {
            val offset = position()
            try {
                val globalType = WasmGlobalType(valueType()!!, get() == 0x01.toByte())
                val expression = parseOpCodes()
                records.add(WasmGlobalRecord(offset, sizeFromOffset(offset), globalType, expression))
            } catch (e: Exception) {
                failedOffset = offset
                break
            }
        }
        return records to failedOffset
    }

    fun CommonByteBuffer.scan(): List<WasmGlobalRecord> = scanPartial().first
}

data class WasmGlobalRecord(
    override val offset: Int,
    override val size: Int,
    val type: WasmGlobalType,
    val expression: List<OpCode>
) : WasmRange
