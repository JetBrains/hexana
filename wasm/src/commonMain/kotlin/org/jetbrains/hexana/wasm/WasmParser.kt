package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.CommonByteBuffer

class WasmParser {

    private companion object {
        val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D)
        val WASM_VERSION = byteArrayOf(0x01, 0x00, 0x00, 0x00)

        fun CommonByteBuffer.scanSection(): WasmSectionUnresolved {
            val sectionId = get()
            val sectionSize = getULEB128().toInt()

            return WasmSectionUnresolved(sectionId, sectionSize, position())
        }

        fun CommonByteBuffer.resolveSection(): WasmSection? {
            val unresolvedSection = scanSection()
            return when (unresolvedSection.id) {
                WasmSectionCustom.SECTION_ID -> WasmSectionCustom(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionTypes.SECTION_ID -> WasmSectionTypes(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionImport.SECTION_ID -> WasmSectionImport(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionFunctions.SECTION_ID -> WasmSectionFunctions(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionTable.SECTION_ID -> WasmSectionTable(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionMemory.SECTION_ID -> WasmSectionMemory(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionGlobal.SECTION_ID -> WasmSectionGlobal(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionExport.SECTION_ID -> WasmSectionExport(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionStart.SECTION_ID -> WasmSectionStart(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionElement.SECTION_ID -> WasmSectionElement(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionCode.SECTION_ID -> WasmSectionCode(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionData.SECTION_ID -> WasmSectionData(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionDataCount.SECTION_ID -> WasmSectionDataCount(unresolvedSection.size, unresolvedSection.offset)
                WasmSectionException.SECTION_ID -> WasmSectionException(unresolvedSection.size, unresolvedSection.offset)
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
            buffer.position(buffer.position() + wasmSection.size)
        }

        val wasmModule = WasmModule.fromSections(sections)

        return wasmModule
    }
}