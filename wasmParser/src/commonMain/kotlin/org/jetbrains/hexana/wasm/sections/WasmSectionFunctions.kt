package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer


class WasmSectionFunctions(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Functions", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 3
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.parseFunctions(): List<Int> {
        position(contentOffset)
        val typeRefs = mutableListOf<Int>()
        consumeArray {
            typeRefs.add(getULEB128().toInt())
        }

        return typeRefs
    }

    /**
     * Per-entry byte ranges. Each function-section entry is a single ULEB128
     * `typeidx`; this returns the start offset, byte length, and decoded
     * `typeidx` for entry `i` (0-based). Use when you need to display the
     * Function section as one row per entry (offset + hex + description)
     * instead of the single-chunk roll-up that `parseFunctions` powers.
     */
    fun CommonByteBuffer.scanRanges(): List<FunctionEntryRange> {
        position(contentOffset)
        val count = getULEB128().toInt()
        val out = ArrayList<FunctionEntryRange>(count)
        repeat(count) {
            val start = position()
            val typeIdx = getULEB128().toInt()
            val size = position() - start
            out.add(FunctionEntryRange(offset = start, size = size, typeIdx = typeIdx))
        }
        return out
    }
}

data class FunctionEntryRange(val offset: Int, val size: Int, val typeIdx: Int)
