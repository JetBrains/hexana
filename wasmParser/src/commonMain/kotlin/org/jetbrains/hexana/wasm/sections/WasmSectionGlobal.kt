package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes

class WasmSectionGlobal(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection(sectionSize, sectionOffset, contentSize, contentOffset) {
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

    fun CommonByteBuffer.scan(): List<WasmGlobalRecord> {
        position(contentOffset)
        val records = mutableListOf<WasmGlobalRecord>()
        consumeArray {
            val offset = position()
            val globalType = WasmGlobalType(valueType(), get() == 0x01.toByte())
            val expression = parseOpCodes()
            records.add(WasmGlobalRecord(offset, sizeFromOffset(offset), globalType, expression))
        }
        return records
    }
}

data class WasmGlobalRecord(
    override val offset: Int,
    override val size: Int,
    val type: WasmGlobalType,
    val expression: List<OpCode>
) : WasmRange
