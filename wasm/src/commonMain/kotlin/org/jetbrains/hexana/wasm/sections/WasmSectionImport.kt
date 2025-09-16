package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.CommonByteBuffer

class WasmSectionImport(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 2
    }

    fun CommonByteBuffer.getImportRecords(): MutableList<WasmImportRecord> {
        position(offset)
        val vecCount = getULEB128().toInt()

        val records = mutableListOf<WasmImportRecord>()

        repeat(vecCount) {
            val prefixNameSize = getULEB128().toInt()
            val prefixBytes = ByteArray(prefixNameSize)
            get(prefixBytes, 0, prefixNameSize)
            val nameSize = getULEB128().toInt()
            val nameBytes = ByteArray(nameSize)
            get(nameBytes, 0, nameSize)
            val type = get()
            position(position() + 1)
            records.add(
                WasmImportRecord(
                    prefixBytes.decodeToString(),
                    nameBytes.decodeToString(),
                    type
                )
            )
        }

        return records
    }
}


data class WasmImportRecord(val prefix: String, val name: String, val type: Byte)