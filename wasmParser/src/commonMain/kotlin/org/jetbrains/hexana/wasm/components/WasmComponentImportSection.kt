package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.ComponentImportSection.importRecord
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.consumeString
import org.jetbrains.hexana.wasm.sections.consumeArrayPartial

class WasmComponentImportSection(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection("ComponentImport", sectionSize,
    sectionOffset,
    contentSize,
    contentOffset
) {
    companion object {
        const val SECTION_ID: Byte = 10
    }
    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.scan(): List<WasmComponentImport> {
        position(contentOffset)
        val list = mutableListOf<WasmComponentImport>()
        consumeArray(contentOffset + contentSize) {
            list.add(importRecord())
        }
        return list
    }

    /** Returns the decoded import prefix and the first unreadable entry offset. */
    fun CommonByteBuffer.scanPartial(): Pair<List<WasmComponentImport>, Int?> = run {
        position(contentOffset)
        consumeArrayPartial(contentOffset + contentSize) { importRecord() }.let { it.values to it.failureOffset }
    }
}

object ComponentImportSection {
    fun CommonByteBuffer.importRecord(): WasmComponentImport {
        val offset = position()
        return when(get()) {
            0x00.toByte() -> {
                val importName = consumeString()
                val externDescription = externDescription()
                WasmComponentImport(offset, position() - offset, importName,null, externDescription)
            }
            0x01.toByte() -> {
                val importName = consumeString()
                val version = consumeString()
                val externDescription = externDescription()
                WasmComponentImport(offset, position() - offset, importName, version, externDescription)
            }
            else -> throw IllegalArgumentException("Invalid import record type")
        }
    }
}

class WasmComponentImport(
    val offset: Int,
    val size: Int,
    val importName: String,
    val version: String? = null,
    val externDescription: ComponentExternDescription
)
