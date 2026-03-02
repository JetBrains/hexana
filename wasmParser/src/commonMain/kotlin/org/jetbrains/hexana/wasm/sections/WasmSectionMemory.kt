package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.limits

class WasmSectionMemory(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Memory", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 5
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return with (buffer) {
            scan().mapIndexed { index, record ->
                WasmNamedRange(record, "memory $index")
            }
        }
    }

    fun CommonByteBuffer.scan():List<WasmMemoryRecord> {
        position(contentOffset)
        val records = mutableListOf<WasmMemoryRecord>()
        consumeArray { memidx ->
            val offset = position()
            val limits = limits()
            records.add(WasmMemoryRecord(offset, sizeFromOffset(offset), memidx, limits))
        }
        return records
    }
}

data class WasmMemoryRecord(
    override val offset: Int,
    override val size: Int,
    val memidx:Int,
    val limits: WasmLimits<*>
) : WasmRange
