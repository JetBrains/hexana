package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes

class WasmSectionData(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Data", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 11
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return with (buffer) {
            scan().mapIndexed { index, record ->
                WasmNamedRange(record, "data $index")
            }
        }
    }

    fun CommonByteBuffer.scan(): List<WasmDataRecord> {
        position(contentOffset)
        return buildList {
            consumeArray {
                val off = position()
                when (val recordType = getULEB128().toUInt()) {
                    0u -> {
                        val opCodes = this@scan.parseOpCodes(instructionReadingLimit())
                        val data = this@scan.fetchDataArray()
                        add(WasmDataRecordActive(off, sizeFromOffset(off), opCodes, data))
                    }
                    1u -> {
                        val data = this@scan.fetchDataArray()
                        add(WasmDataRecordPassive(off, sizeFromOffset(off), data))
                    }
                    2u -> {
                        val index = getULEB128().toUInt()
                        val opCodes = this@scan.parseOpCodes(instructionReadingLimit())
                        val data = this@scan.fetchDataArray()
                        add(WasmDataRecordActiveWithMemoryIndex(off, sizeFromOffset(off), index, opCodes, data))
                    }
                    else -> throw IllegalArgumentException("Unknown data record type: $recordType")
                }
            }
        }
    }

    private fun CommonByteBuffer.fetchDataArray(): ByteArray = buildList<Byte> {
        // TODO: 🐛 without this@fetchDataArray this will fail in JS (and it shouldn't)
        consumeArray { add(this@fetchDataArray.get()) }
    }.toByteArray()
}

/**
 * 5.5.14
 */
sealed class WasmDataRecord(
    val type: UInt,
    override val offset: Int,
    override val size: Int,
    open val data: ByteArray
) : WasmRange {

    open val recordLength: Int = size

}

data class WasmDataRecordActive(override val offset: Int, override val recordLength: Int, val expression: List<OpCode>, override val data: ByteArray): WasmDataRecord(0x0u, offset, recordLength, data) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as WasmDataRecordActive

        if (offset != other.offset) return false
        if (recordLength != other.recordLength) return false
        if (expression != other.expression) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offset.hashCode()
        result = 31 * result + recordLength
        result = 31 * result + expression.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

}

data class WasmDataRecordPassive(override val offset: Int, override val recordLength: Int, override val data: ByteArray): WasmDataRecord(0x1u, offset, recordLength, data) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as WasmDataRecordPassive

        if (offset != other.offset) return false
        if (recordLength != other.recordLength) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offset.hashCode()
        result = 31 * result + recordLength
        result = 31 * result + data.contentHashCode()
        return result
    }

}

data class WasmDataRecordActiveWithMemoryIndex(override val offset: Int, override val recordLength: Int, val memoryIndex: UInt, val expression: List<OpCode>, override val data: ByteArray): WasmDataRecord(0x2u, offset, recordLength, data) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as WasmDataRecordActiveWithMemoryIndex

        if (offset != other.offset) return false
        if (recordLength != other.recordLength) return false
        if (memoryIndex != other.memoryIndex) return false
        if (expression != other.expression) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offset.hashCode()
        result = 31 * result + recordLength
        result = 31 * result + memoryIndex.hashCode()
        result = 31 * result + expression.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }


}
