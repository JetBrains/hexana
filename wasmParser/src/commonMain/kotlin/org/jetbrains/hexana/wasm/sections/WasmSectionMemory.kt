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

    /**
     * Parses as many memory entries as possible. On the first undecodable entry,
     * stops and returns the partial list paired with the byte offset of that
     * entry (null second means the section parsed fully).
     */
    fun CommonByteBuffer.scanPartial(): Pair<MutableList<WasmMemoryRecord>, Int?> = run {
        position(contentOffset)
        consumeArrayPartial(contentOffset + contentSize) { memidx ->
            val offset = position()
            val limits = limits()
            WasmMemoryRecord(offset, sizeFromOffset(offset), memidx, limits)
        }.let { it.values to it.failureOffset }
    }

    fun CommonByteBuffer.scan(): List<WasmMemoryRecord> = scanPartial().first
}

data class WasmMemoryRecord(
    override val offset: Int,
    override val size: Int,
    val memidx:Int,
    val limits: WasmLimits<*>
) : WasmRange
