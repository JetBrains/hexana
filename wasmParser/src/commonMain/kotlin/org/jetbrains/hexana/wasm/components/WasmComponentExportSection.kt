package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.ComponentCoreInstance.componentSortWithIndex
import org.jetbrains.hexana.wasm.components.ComponentExportSection.exportRecord
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.consumeString
import org.jetbrains.hexana.wasm.sections.consumeArrayPartial

class WasmComponentExportSection(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection("ComponentExport", sectionSize,
    sectionOffset,
    contentSize,
    contentOffset
) {
    companion object {
        const val SECTION_ID: Byte = 11
    }
    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.scan(): List<WasmComponentExport> {
        position(contentOffset)
        val list = mutableListOf<WasmComponentExport>()
        consumeArray(contentOffset + contentSize) {
            list.add(exportRecord())
        }
        return list
    }

    /** Returns the decoded export prefix and the first unreadable entry offset. */
    fun CommonByteBuffer.scanPartial(): Pair<List<WasmComponentExport>, Int?> = run {
        position(contentOffset)
        consumeArrayPartial(contentOffset + contentSize) { exportRecord() }.let { it.values to it.failureOffset }
    }
}

object ComponentExportSection {
    fun CommonByteBuffer.exportRecord(): WasmComponentExport {
        val offset = position()
        return when(get()) {
            0x00.toByte() -> {
                val exportName = consumeString()
                val sortIndex = componentSortWithIndex()
                val externDescription = if (get() != 0x00.toByte()) externDescription() else null
                WasmComponentExport(offset, position() - offset, exportName, null, sortIndex, externDescription)
            }
            0x01.toByte() -> {
                val exportName = consumeString()
                val version = consumeString()
                val sortIndex = componentSortWithIndex()
                val externDescription = if (get() != 0x00.toByte()) externDescription() else null
                WasmComponentExport(
                    offset,
                    position() - offset,
                    exportName,
                    version,
                    sortIndex,
                    externDescription
                )
            }
            else -> throw IllegalArgumentException("Invalid export record type")
        }
    }
}

class WasmComponentExport(
    val offset: Int,
    val size: Int,
    val exportName: String,
    val version: String?,
    val sortIndex: ComponentSortIndex,
    val externDescription: ComponentExternDescription? = null
)
