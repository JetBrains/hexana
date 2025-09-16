package org.jetbrains.hexana.wasm

import java.io.File
import java.nio.ByteBuffer

class WasmParser {

    private companion object {
        val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D)
        val WASM_VERSION = byteArrayOf(0x01, 0x00, 0x00, 0x00)

        private fun ByteBuffer.getUInt8(): UInt = get().toUInt()

        private fun ByteBuffer.getULEB128(): Long {
            var result = 0L
            var shift = 0

            while (true) {
                val byte = getUInt8()
                result = result or ((byte and 0x7f.toUInt()) shl shift).toLong()
                if (byte and 0x80.toUInt() == 0.toUInt()) {
                    break
                }
                shift +=7
            }

            return result
        }

        fun ByteBuffer.scanSection(): WasmSectionUnresolved {
            val sectionId = get()
            val sectionSize = getULEB128().toInt()

            return WasmSectionUnresolved(sectionId, sectionSize)
        }

        fun ByteBuffer.resolveSection(): WasmSection? {
            val unresolvedSection = scanSection()
            return when (unresolvedSection.id) {
                WasmSectionCustom.SECTION_ID -> WasmSectionCustom(unresolvedSection.size)
                WasmSectionTypes.SECTION_ID -> WasmSectionTypes(unresolvedSection.size)
                WasmSectionImport.SECTION_ID -> WasmSectionImport(unresolvedSection.size)
                WasmSectionFunctions.SECTION_ID -> WasmSectionFunctions(unresolvedSection.size)
                WasmSectionTable.SECTION_ID -> WasmSectionTable(unresolvedSection.size)
                WasmSectionMemory.SECTION_ID -> WasmSectionMemory(unresolvedSection.size)
                WasmSectionGlobal.SECTION_ID -> WasmSectionGlobal(unresolvedSection.size)
                WasmSectionExport.SECTION_ID -> WasmSectionExport(unresolvedSection.size)
                WasmSectionStart.SECTION_ID -> WasmSectionStart(unresolvedSection.size)
                WasmSectionElement.SECTION_ID -> WasmSectionElement(unresolvedSection.size)
                WasmSectionCode.SECTION_ID -> WasmSectionCode(unresolvedSection.size)
                WasmSectionData.SECTION_ID -> WasmSectionData(unresolvedSection.size)
                WasmSectionDataCount.SECTION_ID -> WasmSectionDataCount(unresolvedSection.size)
                WasmSectionException.SECTION_ID -> WasmSectionException(unresolvedSection.size)
                else -> null
            }
        }
    }

    fun parse(buffer: ByteBuffer): WasmModule? {
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

    fun parseFile(path: String): WasmModule? = parse(ByteBuffer.wrap(File(path).readBytes()))
}