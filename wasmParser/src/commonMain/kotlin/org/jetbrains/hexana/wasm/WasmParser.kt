package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionCode
import org.jetbrains.hexana.wasm.sections.WasmSectionCustom
import org.jetbrains.hexana.wasm.sections.WasmSectionData
import org.jetbrains.hexana.wasm.sections.WasmSectionDataCount
import org.jetbrains.hexana.wasm.sections.WasmSectionElement
import org.jetbrains.hexana.wasm.sections.WasmSectionException
import org.jetbrains.hexana.wasm.sections.WasmSectionExport
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctions
import org.jetbrains.hexana.wasm.sections.WasmSectionGlobal
import org.jetbrains.hexana.wasm.sections.WasmSectionImport
import org.jetbrains.hexana.wasm.sections.WasmSectionMemory
import org.jetbrains.hexana.wasm.sections.WasmSectionStart
import org.jetbrains.hexana.wasm.sections.WasmSectionTable
import org.jetbrains.hexana.wasm.sections.WasmSectionTypes
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved

class WasmParser {

    private companion object {
        val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D)
        val WASM_VERSION = byteArrayOf(0x01, 0x00, 0x00, 0x00)

        fun CommonByteBuffer.scanSection(): WasmSectionUnresolved {
            val sectionOffset = position()
            val sectionId = get()
            val contentSize = getULEB128().toInt()
            val contentOffset = position()
            val sectionSize = contentSize + contentOffset - sectionOffset
            return WasmSectionUnresolved(sectionId, sectionSize, sectionOffset, contentSize, contentOffset)
        }

        fun CommonByteBuffer.resolveSection(): WasmSection? {
            val unresolvedSection = scanSection()
            return when (unresolvedSection.id) {
                WasmSectionCustom.SECTION_ID -> WasmSectionCustom(unresolvedSection)
                WasmSectionTypes.SECTION_ID -> WasmSectionTypes(unresolvedSection)
                WasmSectionImport.SECTION_ID -> WasmSectionImport(unresolvedSection)
                WasmSectionFunctions.SECTION_ID -> WasmSectionFunctions(unresolvedSection)
                WasmSectionTable.SECTION_ID -> WasmSectionTable(unresolvedSection)
                WasmSectionMemory.SECTION_ID -> WasmSectionMemory(unresolvedSection)
                WasmSectionGlobal.SECTION_ID -> WasmSectionGlobal(unresolvedSection)
                WasmSectionExport.SECTION_ID -> WasmSectionExport(unresolvedSection)
                WasmSectionStart.SECTION_ID -> WasmSectionStart(unresolvedSection)
                WasmSectionElement.SECTION_ID -> WasmSectionElement(unresolvedSection)
                WasmSectionCode.SECTION_ID -> WasmSectionCode(unresolvedSection)
                WasmSectionData.SECTION_ID -> WasmSectionData(unresolvedSection)
                WasmSectionDataCount.SECTION_ID -> WasmSectionDataCount(unresolvedSection)
                WasmSectionException.SECTION_ID -> WasmSectionException(unresolvedSection)
                else -> null
            }
        }
    }

    fun parse(buffer: CommonByteBuffer): WasmModule? {
        if (buffer.remaining() < 4) return null

        val header = ByteArray(4)
        buffer.get(header, 0, 4)

        val isWasmHeader = header.contentEquals(WASM_MAGIC)

        val version = ByteArray(4)
        buffer.get(version, 0, 4)

        val isValidVersion = version.contentEquals(WASM_VERSION)

        if (!isWasmHeader || !isValidVersion) return null

        val sections = mutableListOf<WasmSection>()
        while (buffer.hasRemaining()) {
            val wasmSection = buffer.resolveSection() ?: throw Exception("Unprocess section")
            sections.add(wasmSection)
            buffer.position(buffer.position() + wasmSection.contentSize)
        }

        val wasmModule = WasmModule.fromSections(sections)

        return wasmModule
    }
}