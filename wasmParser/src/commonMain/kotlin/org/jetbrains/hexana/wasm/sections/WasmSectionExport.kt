package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange


class WasmSectionExport(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Exports", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 7
    }

    constructor(s: WasmSectionUnresolved) : this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return with (buffer) {
            scan().mapIndexed { index, entry ->
                val name = when {
                    entry.name.isBlank() -> "export $index"
                    else -> "export $index: ${entry.name}"
                }
                WasmNamedRange(entry, name)
            }
        }
    }

    fun CommonByteBuffer.scan(): MutableList<WasmExportRecordEntry> {
        position(contentOffset)
        val records = mutableListOf<WasmExportRecordEntry>()
        consumeArray {
            val offset = position()
            val name = consumeString()
            val externIndex = get()
            val index = getULEB128().toUInt()
            when (externIndex.toInt()) {
                0 -> records.add(WasmFunctionExportRecordEntry(offset, sizeFromOffset(offset), name, index))
                1 -> records.add(WasmTableExportRecordEntry(offset, sizeFromOffset(offset), name, index))
                2 -> records.add(WasmMemoryExportRecordEntry(offset, sizeFromOffset(offset), name, index))
                3 -> records.add(WasmGlobalExportRecordEntry(offset, sizeFromOffset(offset), name, index))
                4 -> records.add(WasmTagExportRecordEntry(offset, sizeFromOffset(offset), name, index))
                else -> records.add(WasmUnsupportedExportRecordEntry(offset, sizeFromOffset(offset), name, externIndex, index))
            }
        }
        return records
    }
}

sealed interface WasmExportRecordEntry: WasmRange {
    val name: String
    val externType: Byte
}

data class WasmFunctionExportRecordEntry(
    override val offset: Int,
    override val size: Int,
    override val name: String,
    val index: UInt
) : WasmExportRecordEntry {
    override val externType: Byte = 1
}

data class WasmTableExportRecordEntry(
    override val offset: Int,
    override val size: Int,
    override val name: String,
    val index: UInt
) : WasmExportRecordEntry {
    override val externType: Byte = 1
}

data class WasmMemoryExportRecordEntry(
    override val offset: Int,
    override val size: Int,
    override val name: String,
    val index: UInt
) : WasmExportRecordEntry {
    override val externType: Byte = 2
}

data class WasmGlobalExportRecordEntry(
    override val offset: Int,
    override val size: Int,
    override val name: String,
    val index: UInt
) : WasmExportRecordEntry {
    override val externType: Byte = 3
}

data class WasmTagExportRecordEntry(
    override val offset: Int,
    override val size: Int,
    override val name: String,
    val index: UInt
) : WasmExportRecordEntry {
    override val externType: Byte = 4
}

data class WasmUnsupportedExportRecordEntry(
    override val offset: Int,
    override val size: Int,
    override val name: String,
    override val externType: Byte,
    val index: UInt
) : WasmExportRecordEntry
