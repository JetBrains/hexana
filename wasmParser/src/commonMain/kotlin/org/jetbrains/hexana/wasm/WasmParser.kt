package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.WasmComponentBase
import org.jetbrains.hexana.wasm.components.WasmComponentModuleSection
import org.jetbrains.hexana.wasm.components.WasmComponentSection
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
import org.jetbrains.hexana.wasm.sections.WasmParseException
import org.jetbrains.hexana.wasm.sections.WasmSectionStart
import org.jetbrains.hexana.wasm.sections.WasmSectionTable
import org.jetbrains.hexana.wasm.sections.WasmSectionTypes
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved
import org.jetbrains.hexana.wasm.sections.consumeStringWithin
import org.jetbrains.hexana.wasm.sections.consumeUnsignedInt

class WasmParser {

    private companion object {
        val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D)

        data class ScannedSection(
            val section: WasmSectionUnresolved,
            val payloadTruncated: Boolean,
        )

        fun CommonByteBuffer.scanSection(allowTruncatedPayload: (Byte) -> Boolean): ScannedSection {
            val sectionOffset = position()
            val sectionId = get()
            val contentSize = consumeUnsignedInt()
            val contentOffset = position()
            val declaredContentEnd = contentOffset.toLong() + contentSize.toLong()
            if (declaredContentEnd > Int.MAX_VALUE) {
                throw WasmParseException("Section end exceeds supported range at $sectionOffset")
            }
            val payloadTruncated = declaredContentEnd > limit().toLong()
            if (payloadTruncated && !allowTruncatedPayload(sectionId)) {
                throw WasmParseException("Section payload exceeds available bytes at $sectionOffset")
            }
            val sectionSize = (declaredContentEnd - sectionOffset).toInt()
            return ScannedSection(
                WasmSectionUnresolved(sectionId, sectionSize, sectionOffset, contentSize, contentOffset),
                payloadTruncated,
            )
        }
    }

    fun parse(buffer: CommonByteBuffer): WasmParseResult {
        if (buffer.remaining() < 4) return WasmParseFailure(WasmParseFailureKind.TRUNCATED_HEADER)

        val header = ByteArray(4)
        buffer.get(header, 0, 4)

        val isWasmHeader = header.contentEquals(WASM_MAGIC)

        if (!isWasmHeader) return WasmParseFailure(WasmParseFailureKind.MALFORMED_MAGIC)
        if (buffer.remaining() < 4) return WasmParseFailure(WasmParseFailureKind.TRUNCATED_HEADER)

        val versionBytes = ByteArray(4)
        buffer.get(versionBytes, 0, 4)
        val versionRaw = versionBytes[3].toUByte().toUInt().shl(24) or
            versionBytes[2].toUByte().toUInt().shl(16) or
            versionBytes[1].toUByte().toUInt().shl(8) or
            versionBytes[0].toUByte().toUInt()
        val (version, layer) = versionRaw.and(0xffffu).toUShort() to versionRaw.shr(16).and(0xfffffu).toUShort()
        val wasmHeader = WasmHeader(version, layer)
        val isCoreModule = isCoreVersion(version, layer)
        val isComponent = isComponentsVersion(version, layer)

        val sections = mutableListOf<WasmSection>()
        val scanFailures = mutableListOf<SectionScanFailure>()
        while (buffer.hasRemaining()) {
            val sectionOffset = buffer.position()
            val scannedSection = try {
                buffer.scanSection(
                    allowTruncatedPayload = { sectionId ->
                        val isRetainedCoreType = isCoreModule && sectionId == WasmSectionTypes.SECTION_ID
                        val isRetainedComponentSubmodule = isComponent &&
                            (sectionId == WasmComponentModuleSection.SECTION_ID ||
                                sectionId == WasmComponentSection.SECTION_ID)
                        isRetainedCoreType || isRetainedComponentSubmodule
                    },
                )
            } catch (e: Exception) {
                // The header or its declared payload could not be read safely.
                scanFailures.add(SectionScanFailure(sectionOffset, e.message ?: "section scan failed"))
                break
            }
            val wasmSection = try {
                when {
                    isCoreModule -> with(buffer) { coreResolver(scannedSection.section) }
                    isComponent -> with(buffer) { componentResolver(scannedSection.section) }
                    else -> scannedSection.section
                }
            } catch (e: Exception) {
                // Custom-section name decode or resolver failure: record and
                // return the unresolved section so we can still advance past it.
                scanFailures.add(SectionScanFailure(
                    sectionOffset,
                    e.message ?: "section resolve failed",
                    SectionScanFailureKind.RESOLVER_FAILURE,
                ))
                scannedSection.section
            }
            sections.add(wasmSection)
            if (scannedSection.payloadTruncated) {
                val hasEntryParseFailure = when (wasmSection) {
                    is WasmSectionTypes -> try {
                        with(wasmSection) { buffer.parseTypesPartial().second != null }
                    } catch (_: Exception) {
                        false
                    }
                    else -> false
                }
                if (!hasEntryParseFailure) {
                    scanFailures.add(SectionScanFailure(sectionOffset, "Section payload exceeds available bytes at $sectionOffset"))
                }
                break
            }
            val nextIndex = wasmSection.sectionOffset.toLong() + wasmSection.sectionSize.toLong()
            if (nextIndex >= buffer.limit().toLong())
                break
            buffer.position(nextIndex.toInt())
        }

        val wasmModule = fromSections(version, layer, sections)
            ?: return WasmParseFailure(WasmParseFailureKind.UNSUPPORTED_VERSION_LAYER, wasmHeader)

        return WasmParseSuccess(wasmModule, scanFailures, wasmHeader)
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
                consumeStringWithin(unresolvedSection.contentOffset + unresolvedSection.contentSize)
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

/** Header identity decoded by [WasmParser] after a complete Wasm header. */
data class WasmHeader(val version: UShort, val layer: UShort)

/** Why [WasmParser] could not identify a supported Wasm binary. */
enum class WasmParseFailureKind {
    MALFORMED_MAGIC,
    TRUNCATED_HEADER,
    UNSUPPORTED_VERSION_LAYER,
}

/** A section-header or section-resolve failure recorded during the scan loop. */
data class SectionScanFailure(
    val byteOffset: Int,
    val message: String,
    val kind: SectionScanFailureKind = SectionScanFailureKind.TRUNCATED,
)
enum class SectionScanFailureKind { TRUNCATED, RESOLVER_FAILURE }

class WasmParseSuccess(
    val wasmModule: WasmComponentBase<*, *>,
    val sectionScanFailures: List<SectionScanFailure> = emptyList(),
) : WasmParseResult {
    /** Present for all results created by [WasmParser]; nullable for source compatibility with old callers. */
    var header: WasmHeader? = null
        private set

    constructor(
        wasmModule: WasmComponentBase<*, *>,
        sectionScanFailures: List<SectionScanFailure>,
        header: WasmHeader?,
    ) : this(wasmModule, sectionScanFailures) {
        this.header = header
    }
}

class WasmParseFailure(
    val kind: WasmParseFailureKind = WasmParseFailureKind.MALFORMED_MAGIC,
    /** Available when magic and all four version bytes were decoded. */
    val header: WasmHeader? = null,
) : WasmParseResult
