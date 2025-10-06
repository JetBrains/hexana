package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer

class WasmSectionImport(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 2
    }

    fun CommonByteBuffer.getImportRecords(): MutableList<WasmImportRecord> {
        position(offset)

        val records = mutableListOf<WasmImportRecord>()
        consumeArray {
            val prefix = consumeString()
            val name = consumeString()
            position(position() + 1)
            val type = getULEB128().toUInt()

            records.add(
                WasmImportRecord(prefix, name, type)
            )
        }
        return records
    }
}


data class WasmImportRecord(val prefix: String, val name: String, val type: UInt)