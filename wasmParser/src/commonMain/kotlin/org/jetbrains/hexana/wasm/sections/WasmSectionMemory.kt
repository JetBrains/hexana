package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer

class WasmSectionMemory(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 5
    }

    fun CommonByteBuffer.scan():List<WasmMemoryRecord> {
        position(offset)
        val records = mutableListOf<WasmMemoryRecord>()
        consumeArray { memidx ->
            records.add(WasmMemoryRecord(memidx, limits()))
        }
        return records
    }
}

data class WasmMemoryRecord(val memidx:Int, val limits: WasmLimits<*>)
