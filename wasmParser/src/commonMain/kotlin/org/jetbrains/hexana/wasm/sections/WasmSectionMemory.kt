package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer

class WasmSectionMemory(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection(sectionSize, sectionOffset, contentSize, contentOffset) {
    companion object {
        const val SECTION_ID: Byte = 5
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.scan():List<WasmMemoryRecord> {
        position(contentOffset)
        val records = mutableListOf<WasmMemoryRecord>()
        consumeArray { memidx ->
            records.add(WasmMemoryRecord(memidx, limits()))
        }
        return records
    }
}

data class WasmMemoryRecord(val memidx:Int, val limits: WasmLimits<*>)
