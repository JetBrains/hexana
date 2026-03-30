package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.ComponentCoreInstance.componentSortWithIndex
import org.jetbrains.hexana.wasm.components.ComponentExportSection.exportRecord
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.consumeString

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
        val list = mutableListOf<WasmComponentExport>()
        position(contentOffset)
        consumeArray {
            list.add(exportRecord())
        }
        return list
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

