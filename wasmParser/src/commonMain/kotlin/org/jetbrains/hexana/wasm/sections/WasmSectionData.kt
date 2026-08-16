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

    /**
     * Parses as many data segments as possible. On the first undecodable entry,
     * stops and returns the partial list paired with the byte offset of that
     * entry (null second means the section parsed fully).
     */
    fun CommonByteBuffer.scanPartial(): Pair<MutableList<WasmDataRecord>, Int?> {
        position(contentOffset)
        val records = mutableListOf<WasmDataRecord>()
        var failedOffset: Int? = null

        val count = getULEB128().toInt()
        for (i in 0 until count) {
            val off = position()
            try {
                when (val recordType = getULEB128().toUInt()) {
                    0u -> {
                        val opCodes = parseOpCodes(instructionReadingLimit())
                        val data = fetchDataSlice()
                        records.add(WasmDataRecordActive(off, sizeFromOffset(off), opCodes, data))
                    }
                    1u -> {
                        val data = fetchDataSlice()
                        records.add(WasmDataRecordPassive(off, sizeFromOffset(off), data))
                    }
                    2u -> {
                        val index = getULEB128().toUInt()
                        val opCodes = parseOpCodes(instructionReadingLimit())
                        val data = fetchDataSlice()
                        records.add(WasmDataRecordActiveWithMemoryIndex(off, sizeFromOffset(off), index, opCodes, data))
                    }
                    else -> throw IllegalArgumentException("Unknown data record type: $recordType")
                }
            } catch (e: Exception) {
                failedOffset = off
                break
            }
        }
        return records to failedOffset
    }

    fun CommonByteBuffer.scan(): List<WasmDataRecord> = scanPartial().first

    /**
     * Reads `vec(byte)` — a ULEB128 length followed by `length` raw bytes — and
     * returns a [CommonByteBuffer] view over the bytes. The view shares the
     * underlying storage with the parent buffer; no copy is made. The parent
     * cursor is advanced past the data so the next record starts at the right
     * offset.
     */
    private fun CommonByteBuffer.fetchDataSlice(): CommonByteBuffer {
        val length = getULEB128().toInt()
        val start = position()
        val view = slice(start, length)
        position(start + length)
        return view
    }
}

/** Compares two buffer views by length then byte-by-byte. */
private fun bufferContentEquals(a: CommonByteBuffer, b: CommonByteBuffer): Boolean {
    if (a === b) return true
    val n = a.size
    if (n != b.size) return false
    for (i in 0 until n) if (a[i] != b[i]) return false
    return true
}

/**
 * Content hash matching [ByteArray.contentHashCode] semantics so records with
 * identical bytes hash equally regardless of the underlying buffer instance.
 */
private fun bufferContentHashCode(buf: CommonByteBuffer): Int {
    var result = 1
    val n = buf.size
    for (i in 0 until n) result = 31 * result + buf[i].toInt()
    return result
}

/**
 * 5.5.14
 */
sealed class WasmDataRecord(
    val type: UInt,
    override val offset: Int,
    override val size: Int,
    open val data: CommonByteBuffer,
) : WasmRange {

    open val recordLength: Int = size

}

data class WasmDataRecordActive(override val offset: Int, override val recordLength: Int, val expression: List<OpCode>, override val data: CommonByteBuffer): WasmDataRecord(0x0u, offset, recordLength, data) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as WasmDataRecordActive

        if (offset != other.offset) return false
        if (recordLength != other.recordLength) return false
        if (expression != other.expression) return false
        if (!bufferContentEquals(data, other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offset.hashCode()
        result = 31 * result + recordLength
        result = 31 * result + expression.hashCode()
        result = 31 * result + bufferContentHashCode(data)
        return result
    }

}

data class WasmDataRecordPassive(override val offset: Int, override val recordLength: Int, override val data: CommonByteBuffer): WasmDataRecord(0x1u, offset, recordLength, data) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as WasmDataRecordPassive

        if (offset != other.offset) return false
        if (recordLength != other.recordLength) return false
        if (!bufferContentEquals(data, other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offset.hashCode()
        result = 31 * result + recordLength
        result = 31 * result + bufferContentHashCode(data)
        return result
    }

}

data class WasmDataRecordActiveWithMemoryIndex(override val offset: Int, override val recordLength: Int, val memoryIndex: UInt, val expression: List<OpCode>, override val data: CommonByteBuffer): WasmDataRecord(0x2u, offset, recordLength, data) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as WasmDataRecordActiveWithMemoryIndex

        if (offset != other.offset) return false
        if (recordLength != other.recordLength) return false
        if (memoryIndex != other.memoryIndex) return false
        if (expression != other.expression) return false
        if (!bufferContentEquals(data, other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offset.hashCode()
        result = 31 * result + recordLength
        result = 31 * result + memoryIndex.hashCode()
        result = 31 * result + expression.hashCode()
        result = 31 * result + bufferContentHashCode(data)
        return result
    }


}
