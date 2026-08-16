package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer


class WasmSectionFunctions(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Functions", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 3
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    /**
     * Parses as many function type-index entries as possible. On the first undecodable entry,
     * stops and returns the partial list paired with the byte offset of that
     * entry (null second means the section parsed fully).
     */
    fun CommonByteBuffer.parseFunctionsPartial(): Pair<MutableList<Int>, Int?> {
        position(contentOffset)
        val typeRefs = mutableListOf<Int>()
        var failedOffset: Int? = null

        val count = getULEB128().toInt()
        for (i in 0 until count) {
            val offset = position()
            try {
                typeRefs.add(getULEB128().toInt())
            } catch (e: Exception) {
                failedOffset = offset
                break
            }
        }
        return typeRefs to failedOffset
    }

    fun CommonByteBuffer.parseFunctions(): List<Int> = parseFunctionsPartial().first

    /**
     * Per-entry byte ranges. Each function-section entry is a single ULEB128
     * `typeidx`; this returns the start offset, byte length, and decoded
     * `typeidx` for entry `i` (0-based). Use when you need to display the
     * Function section as one row per entry (offset + hex + description)
     * instead of the single-chunk roll-up that `parseFunctions` powers.
     */
    fun CommonByteBuffer.scanRangesPartial(): Pair<MutableList<FunctionEntryRange>, Int?> {
        position(contentOffset)
        val count = getULEB128().toInt()
        val out = ArrayList<FunctionEntryRange>(count)
        var failedOffset: Int? = null
        for (i in 0 until count) {
            val start = position()
            try {
                val typeIdx = getULEB128().toInt()
                val size = position() - start
                out.add(FunctionEntryRange(offset = start, size = size, typeIdx = typeIdx))
            } catch (e: Exception) {
                failedOffset = start
                break
            }
        }
        return out to failedOffset
    }

    fun CommonByteBuffer.scanRanges(): List<FunctionEntryRange> = scanRangesPartial().first
}

data class FunctionEntryRange(val offset: Int, val size: Int, val typeIdx: Int)
