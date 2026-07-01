package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.elf.ElfAddressMapper
import org.jetbrains.hexana.providers.elf.ElfProgramHeader
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.elf.ElfSymbol
import org.jetbrains.hexana.providers.macho.MachOSection
import org.jetbrains.hexana.providers.macho.MachOSymbol
import org.jetbrains.hexana.providers.pe.PeExportDirectory
import org.jetbrains.hexana.providers.pe.PeSection

/**
 * Result of inspecting a native binary for GraalVM Native Image fingerprints.
 *
 * Detection is layered (cheap → less cheap), mirroring how Syft and
 * `native-image-inspect` identify these binaries:
 *  1. the `com.oracle.svm.core.VM=GraalVM …` identity string in read-only data,
 *  2. SubstrateVM section names (`.svm_*` / `__svm*`) or the `__svm_version_info`
 *     marker symbol,
 *  3. the embedded SBOM symbols (`sbom` / `sbom_length`).
 *
 * We deliberately do NOT distinguish shared libraries: the isolate C-API symbols
 * (`graal_create_isolate`, …) are present in *every* native image, not just
 * `--shared` builds, so they are not a usable discriminator. All GraalVM-produced
 * binaries are simply reported as "Native Image".
 *
 * Everything here is derivable from the symbol table + program/section headers
 * + a string scan, with NO decompression — [sbom] is only a *locator*. Actual
 * gunzip + CycloneDX parse happens in the host (JVM) layer on demand.
 *
 * @property isNativeImage true when signal 1 or 2 fired (the "Native Image" badge).
 * @property version the identity string value, e.g. `"GraalVM 23+37.1 Java 23"`, or null.
 * @property edition Community vs Enterprise, inferred from the identity string.
 * @property platform target platform leaf, e.g. `"LINUX_AMD64"`, or null.
 * @property svmSections matched SubstrateVM section names (signal 2).
 * @property sbom locator for the embedded SBOM blob (signal 3), or null when absent.
 */
data class NativeImageInfo(
    val isNativeImage: Boolean,
    val confidence: Confidence,
    val version: String?,
    val edition: Edition,
    val platform: String?,
    val svmSections: List<String>,
    val sbom: SbomLocator?,
) {
    enum class Confidence { HIGH, MEDIUM, NONE }

    enum class Edition { COMMUNITY, ENTERPRISE, UNKNOWN }

    companion object {
        val NONE = NativeImageInfo(
            isNativeImage = false,
            confidence = Confidence.NONE,
            version = null,
            edition = Edition.UNKNOWN,
            platform = null,
            svmSections = emptyList(),
            sbom = null,
        )
    }
}

/**
 * Where the embedded SBOM blob lives in the file, ready for the host layer to
 * read and decompress. [fileOffset] and [length] are absolute file coordinates
 * (not virtual addresses). [isGzip] reflects the gzip magic sniffed at
 * [fileOffset]; GraalVM has always emitted gzip, but the sniff is defensive.
 */
data class SbomLocator(
    val fileOffset: Long,
    val length: Long,
    val isGzip: Boolean,
)

/**
 * Detects GraalVM Native Image fingerprints in already-parsed native binaries.
 *
 * Per-format entry points mirror [NativeFunctionDiscovery]; the parent provider
 * supplies the lists it has already parsed lazily. The reference for the SBOM
 * layout is Anchore Syft's `graalvm_native_image_cataloger` and the GraalVM SBOM
 * docs: the symbols `sbom` / `sbom_length` / `__svm_version_info` live in both
 * `.symtab` and `.dynsym` (so detection survives stripping and works for
 * `--shared`), `sbom`'s value is the blob's virtual address, and `sbom_length`'s
 * value points at an 8-byte little-endian length in `.data`.
 */
object NativeImageDetector {

    private const val VM_IDENTITY_KEY = "com.oracle.svm.core.VM="
    private const val VM_PLATFORM_KEY = "com.oracle.svm.core.VM.Target.Platform="

    // Symbol names (normalized by stripping leading underscores — Mach-O prefixes
    // every symbol with `_`, and the SVM marker already starts with `__`).
    private const val SBOM_SYMBOL = "sbom"
    private const val SBOM_LENGTH_SYMBOL = "sbom_length"
    private const val SVM_VERSION_INFO = "svm_version_info"

    fun fromElf(
        strings: List<BinaryStringRecord>,
        sections: List<ElfSectionHeader>,
        symbols: List<ElfSymbol>,
        programHeaders: List<ElfProgramHeader>,
        bytes: CommonByteBuffer,
    ): NativeImageInfo {
        val identity = parseVmIdentity(strings)
        val svmSections = sections.map { it.name }.filter { it.startsWith(".svm") }
        val symbolNames = symbols.asSequence().map { normalizeSymbol(it.name) }.toSet()
        val hasSentinel = SVM_VERSION_INFO in symbolNames

        val sbom = if (SBOM_SYMBOL in symbolNames && SBOM_LENGTH_SYMBOL in symbolNames) {
            val sbomVa = symbols.firstOrNull { normalizeSymbol(it.name) == SBOM_SYMBOL }?.value
            val lengthVa = symbols.firstOrNull { normalizeSymbol(it.name) == SBOM_LENGTH_SYMBOL }?.value
            if (sbomVa != null && lengthVa != null) {
                val sbomOffset = ElfAddressMapper.virtualAddressToFileOffset(sbomVa, programHeaders, sections)
                val lengthOffset = ElfAddressMapper.virtualAddressToFileOffset(lengthVa, programHeaders, sections)
                buildSbomLocator(bytes, sbomOffset, lengthOffset)
            } else {
                null
            }
        } else {
            null
        }

        return assemble(identity, svmSections, hasSentinel, sbom)
    }

    fun fromMachO(
        strings: List<BinaryStringRecord>,
        sections: List<MachOSection>,
        symbols: List<MachOSymbol>,
        bytes: CommonByteBuffer,
    ): NativeImageInfo {
        val identity = parseVmIdentity(strings)
        val svmSections = sections.map { it.name }.filter { it.startsWith("__svm") }
        val symbolNames = symbols.asSequence().map { normalizeSymbol(it.name) }.toSet()
        val hasSentinel = SVM_VERSION_INFO in symbolNames

        val sbom = if (SBOM_SYMBOL in symbolNames && SBOM_LENGTH_SYMBOL in symbolNames) {
            val sbomVa = symbols.firstOrNull { normalizeSymbol(it.name) == SBOM_SYMBOL }?.value
            val lengthVa = symbols.firstOrNull { normalizeSymbol(it.name) == SBOM_LENGTH_SYMBOL }?.value
            if (sbomVa != null && lengthVa != null) {
                buildSbomLocator(bytes, machOOffsetOf(sbomVa, sections), machOOffsetOf(lengthVa, sections))
            } else {
                null
            }
        } else {
            null
        }

        return assemble(identity, svmSections, hasSentinel, sbom)
    }

    fun fromPe(
        strings: List<BinaryStringRecord>,
        sections: List<PeSection>,
        exports: PeExportDirectory?,
        bytes: CommonByteBuffer,
    ): NativeImageInfo {
        val identity = parseVmIdentity(strings)
        val svmSections = sections.map { it.name }.filter { it.startsWith(".svm") }
        val exportByName = exports?.entries
            ?.mapNotNull { e -> e.name?.let { normalizeSymbol(it) to e.rva } }
            ?.toMap()
            .orEmpty()
        val hasSentinel = SVM_VERSION_INFO in exportByName

        val sbom = run {
            val sbomRva = exportByName[SBOM_SYMBOL] ?: return@run null
            val lengthRva = exportByName[SBOM_LENGTH_SYMBOL] ?: return@run null
            buildSbomLocator(bytes, peOffsetOf(sbomRva, sections), peOffsetOf(lengthRva, sections))
        }

        return assemble(identity, svmSections, hasSentinel, sbom)
    }

    // --- shared assembly -----------------------------------------------------

    private fun assemble(
        identity: VmIdentity?,
        svmSections: List<String>,
        hasSentinel: Boolean,
        sbom: SbomLocator?,
    ): NativeImageInfo {
        val isNativeImage = identity != null || svmSections.isNotEmpty() || hasSentinel
        if (!isNativeImage) return NativeImageInfo.NONE

        // Any path that sets isNativeImage is a definitive marker, so confidence is HIGH.
        return NativeImageInfo(
            isNativeImage = true,
            confidence = NativeImageInfo.Confidence.HIGH,
            version = identity?.version,
            edition = identity?.edition ?: NativeImageInfo.Edition.UNKNOWN,
            platform = identity?.platform,
            svmSections = svmSections,
            sbom = sbom,
        )
    }

    // --- identity string -----------------------------------------------------

    private data class VmIdentity(
        val version: String,
        val edition: NativeImageInfo.Edition,
        val platform: String?,
    )

    private fun parseVmIdentity(strings: List<BinaryStringRecord>): VmIdentity? {
        // The value is "GraalVM 23+37.1 Java 23", "GraalVM CE 21.0.1 Java 21",
        // or — for the commercial distribution — "Oracle GraalVM 24.0.2+11.1".
        val versionValue = strings.firstNotNullOfOrNull { record ->
            valueAfter(record.value, VM_IDENTITY_KEY)?.takeIf { it.contains("GraalVM") }
        } ?: return null
        val platform = strings.firstNotNullOfOrNull { record ->
            valueAfter(record.value, VM_PLATFORM_KEY)
        }?.let(::platformLeaf)

        val edition = when {
            // "Oracle GraalVM" is the commercial distribution (the former "EE").
            versionValue.contains("Oracle GraalVM") -> NativeImageInfo.Edition.ENTERPRISE
            versionValue.endsWith(" EE") || versionValue.contains(" EE ") -> NativeImageInfo.Edition.ENTERPRISE
            versionValue.contains("CE") -> NativeImageInfo.Edition.COMMUNITY
            else -> NativeImageInfo.Edition.UNKNOWN
        }
        return VmIdentity(versionValue, edition, platform)
    }

    /** Returns the substring after [key] up to the next control/`=` boundary, or null. */
    private fun valueAfter(haystack: String, key: String): String? {
        val at = haystack.indexOf(key)
        if (at < 0) return null
        val start = at + key.length
        // The scanned record is already a single printable run; stop at a space
        // only for the platform property where a trailing token may follow.
        return haystack.substring(start).trim().ifEmpty { null }
    }

    /** `org.graalvm.nativeimage.Platform$LINUX_AMD64` → `LINUX_AMD64`. */
    private fun platformLeaf(value: String): String {
        val token = value.substringBefore(' ')
        return token.substringAfterLast('$').substringAfterLast('.')
    }

    // --- symbol helpers ------------------------------------------------------

    private fun normalizeSymbol(name: String): String = name.trimStart('_')

    // --- SBOM locator --------------------------------------------------------

    private fun buildSbomLocator(
        bytes: CommonByteBuffer,
        sbomOffset: ULong?,
        lengthFieldOffset: ULong?,
    ): SbomLocator? {
        val sbomFileOffset = sbomOffset?.toLongOrNull() ?: return null
        val lengthOffset = lengthFieldOffset?.toLongOrNull() ?: return null
        if (lengthOffset < 0 || lengthOffset + 8 > bytes.size) return null

        val length = readUint64LE(bytes, lengthOffset.toInt())
        if (length <= 0 || sbomFileOffset < 0 || sbomFileOffset + length > bytes.size) return null

        val isGzip = sbomFileOffset + 2 <= bytes.size &&
            bytes[sbomFileOffset.toInt()] == GZIP_MAGIC_0 &&
            bytes[sbomFileOffset.toInt() + 1] == GZIP_MAGIC_1
        return SbomLocator(sbomFileOffset, length, isGzip)
    }

    /** Reads an 8-byte little-endian unsigned length at [offset]; clamps to Long. */
    private fun readUint64LE(bytes: CommonByteBuffer, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return result
    }

    private fun machOOffsetOf(virtualAddress: ULong, sections: List<MachOSection>): ULong? {
        val section = sections.firstOrNull { sec ->
            sec.contentRange != null && virtualAddress >= sec.address && virtualAddress < sec.address + sec.size
        } ?: return null
        return section.fileOffset + (virtualAddress - section.address)
    }

    private fun peOffsetOf(rva: UInt, sections: List<PeSection>): ULong? {
        val section = sections.firstOrNull { sec ->
            sec.contentRange != null &&
                rva >= sec.virtualAddress &&
                rva < sec.virtualAddress + maxOf(sec.virtualSize, sec.rawDataSize)
        } ?: return null
        return (section.rawDataPointer + (rva - section.virtualAddress)).toULong()
    }

    private const val GZIP_MAGIC_0: Byte = 0x1F
    private const val GZIP_MAGIC_1: Byte = 0x8B.toByte()
}

private fun ULong.toLongOrNull(): Long? = if (this <= Long.MAX_VALUE.toULong()) toLong() else null