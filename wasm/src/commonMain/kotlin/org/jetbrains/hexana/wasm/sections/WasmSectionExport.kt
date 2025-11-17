package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer


class WasmSectionExport(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 7
    }

    fun CommonByteBuffer.scan(): MutableList<WasmExportRecordEntry> {
        position(offset)
        val records = mutableListOf<WasmExportRecordEntry>()
        consumeArray {
            val name = consumeString()
            val externIndex = get()
            when (externIndex) {
                0.toByte() -> records.add(WasmFunctionExportRecordEntry(name,getULEB128().toUInt()))
                1.toByte() -> records.add(WasmTableExportRecordEntry(name,getULEB128().toUInt()))
                2.toByte() -> records.add(WasmMemoryExportRecordEntry(name,getULEB128().toUInt()))
                3.toByte() -> records.add(WasmGlobalExportRecordEntry(name,getULEB128().toUInt()))
                4.toByte() -> records.add(WasmTagExportRecordEntry(name,getULEB128().toUInt()))
                else -> records.add(WasmUnsupportedExportRecordEntry(name, externIndex, getULEB128().toUInt()))
            }
        }
        return records
    }
}

sealed class WasmExportRecordEntry(open val name:String, open val externType: Byte)
data class WasmFunctionExportRecordEntry(override val name: String, val index: UInt): WasmExportRecordEntry(name, 0)
data class WasmTableExportRecordEntry(override val name: String, val index: UInt): WasmExportRecordEntry(name,1)
data class WasmMemoryExportRecordEntry(override val name: String, val index: UInt): WasmExportRecordEntry(name,2)
data class WasmGlobalExportRecordEntry(override val name: String, val index: UInt): WasmExportRecordEntry(name, 3)
data class WasmTagExportRecordEntry(override val name: String, val index: UInt): WasmExportRecordEntry(name,4)
data class WasmUnsupportedExportRecordEntry(override val name: String, override val externType: Byte, val index: UInt): WasmExportRecordEntry(name, externType)
