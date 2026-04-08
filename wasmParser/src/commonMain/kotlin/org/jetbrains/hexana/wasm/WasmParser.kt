package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.WasmComponentBase
import org.jetbrains.hexana.wasm.components.componentResolver
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
import org.jetbrains.hexana.wasm.sections.consumeString

class WasmParser {

    private companion object {
        val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D)

        fun CommonByteBuffer.scanSection(): WasmSectionUnresolved {
            val sectionOffset = position()
            val sectionId = get()
            val contentSize = getULEB128().toInt()
            val contentOffset = position()
            val sectionSize = contentSize + contentOffset - sectionOffset
            return WasmSectionUnresolved(sectionId, sectionSize, sectionOffset, contentSize, contentOffset)
        }

        fun CommonByteBuffer.resolveSection(resolver: CommonByteBuffer.(WasmSectionUnresolved) -> WasmSection): WasmSection {
            val unresolvedSection = scanSection()
            return resolver(unresolvedSection)
        }
    }

    fun parse(buffer: CommonByteBuffer): WasmParseResult {
        if (buffer.remaining() < 4) return WasmParseFailure()

        val header = ByteArray(4)
        buffer.get(header, 0, 4)

        val isWasmHeader = header.contentEquals(WASM_MAGIC)

        val versionBytes = ByteArray(4)
        buffer.get(versionBytes, 0, 4)
        val versionRaw = versionBytes[3].toUInt().shl(24) or (versionBytes[2].toUInt().shl(16)) or (versionBytes[1].toUInt().shl( 8)) or versionBytes[0].toUInt()
        val (version, layer) = versionRaw.and(0xffffu).toUShort() to versionRaw.shr(16).and(0xfffffu).toUShort()

        if (!isWasmHeader) return WasmParseFailure()

        val sections = mutableListOf<WasmSection>()
        while (buffer.hasRemaining()) {
            val wasmSection = buffer.resolveSection {
                when {
                    isCoreVersion(version, layer) -> coreResolver(it)
                    isComponentsVersion(version, layer) -> componentResolver(it)
                    else -> it
                }
            }
            sections.add(wasmSection)
            val nextIndex = wasmSection.sectionOffset + wasmSection.sectionSize
            if (nextIndex >= buffer.limit())
                break
            buffer.position(nextIndex)
        }

        val wasmModule = fromSections(version, layer, sections) ?: return WasmParseFailure()

        return WasmParseSuccess(wasmModule)
    }

}

internal fun isComponentsVersion(version: UShort, layer: UShort): Boolean {
    return version == 0x0du.toUShort() && layer >= 1u.toUShort()
}

internal fun isCoreVersion(version: UShort, layer: UShort): Boolean {
    return version == 1u.toUShort() && layer == 0u.toUShort()
}

internal fun CommonByteBuffer.coreResolver(unresolvedSection: WasmSectionUnresolved): WasmSection =
    when (unresolvedSection.id) {
        WasmSectionCustom.SECTION_ID -> {
            val name = with(unresolvedSection) {
                position(unresolvedSection.contentOffset)
                consumeString()
            }
            with(unresolvedSection) {
                // A custom section (unlike other sections) has a name.
                // Don't include it in the section content.
                val offsetAfterName = position()
                val nameSize = offsetAfterName - contentOffset
                val sizeWithoutName = contentSize - nameSize
                WasmSectionCustom(name, sectionSize, sectionOffset, sizeWithoutName, offsetAfterName)
            }
        }

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
        else -> unresolvedSection
    }

sealed interface WasmParseResult
class WasmParseSuccess(val wasmModule: WasmComponentBase<*, *>) : WasmParseResult
class WasmParseFailure : WasmParseResult