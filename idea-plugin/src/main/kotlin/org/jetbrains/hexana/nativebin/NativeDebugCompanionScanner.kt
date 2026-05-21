package org.jetbrains.hexana.nativebin

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

internal data class NativeDebugContext(
    val binaryPath: Path?,
    val embeddedDebugInfoPresent: Boolean,
    val elfBuildId: String?,
    val gnuDebugLink: String?,
    val companions: List<NativeDebugCompanion>,
)

internal data class NativeDebugCompanion(
    val kind: NativeDebugCompanionKind,
    val status: NativeDebugCompanionStatus,
    val name: String,
    val location: String?,
    val description: String,
    val range: NativeByteRange? = null,
)

internal enum class NativeDebugCompanionKind(val label: String) {
    EmbeddedDebugInfo("Embedded debug info"),
    ElfBuildId("ELF build-id"),
    GnuDebugLink("GNU debuglink"),
    MachOUuid("Mach-O UUID"),
    SeparateDebugFile("Separate debug file"),
    DsymBundle("dSYM bundle"),
    PdbFile("PDB symbols"),
    LegacyDbgFile("Legacy DBG symbols"),
    CrashDump("Crash dump"),
}

internal enum class NativeDebugCompanionStatus(val label: String) {
    Found("found"),
    Candidate("candidate"),
    Missing("missing"),
    Unverified("unverified"),
}

internal object NativeDebugCompanionScanner {
    fun scan(provider: NativeBinaryProvider): NativeDebugContext {
        val sections = (provider as? ElfBinaryProvider)?.sections.orEmpty()
        return scan(
            binaryPath = provider.sourcePath?.let { Path.of(it) },
            bytes = provider.bytes,
            sections = sections,
        )
    }

    fun scan(
        binaryPath: Path?,
        bytes: CommonByteBuffer,
        sections: List<ElfSectionHeader>,
    ): NativeDebugContext {
        val embeddedDebugSections = sections.filter { it.isDebugSection() }
        val buildId = parseElfBuildId(bytes, sections.firstOrNull { it.name == ".note.gnu.build-id" })
        val debugLink = parseGnuDebugLink(bytes, sections.firstOrNull { it.name == ".gnu_debuglink" })

        val companions = buildList {
            if (embeddedDebugSections.isNotEmpty()) {
                add(
                    NativeDebugCompanion(
                        kind = NativeDebugCompanionKind.EmbeddedDebugInfo,
                        status = NativeDebugCompanionStatus.Found,
                        name = embeddedDebugSections.joinToString { it.name },
                        location = null,
                        description = "Debug sections are embedded in this binary.",
                        range = embeddedDebugSections.first().headerRange,
                    )
                )
            } else {
                add(
                    NativeDebugCompanion(
                        kind = NativeDebugCompanionKind.EmbeddedDebugInfo,
                        status = NativeDebugCompanionStatus.Missing,
                        name = "DWARF sections",
                        location = null,
                        description = "No .debug* or .zdebug* sections were found in the binary.",
                    )
                )
            }

            if (buildId != null) {
                add(
                    NativeDebugCompanion(
                        kind = NativeDebugCompanionKind.ElfBuildId,
                        status = NativeDebugCompanionStatus.Found,
                        name = buildId,
                        location = localBuildIdDebugPath(buildId)?.toString(),
                        description = "ELF build-id is present. A matching split debug file may exist under a .build-id debug directory.",
                        range = sections.firstOrNull { it.name == ".note.gnu.build-id" }?.headerRange,
                    )
                )
            }

            if (debugLink != null) {
                val resolved = binaryPath?.parent?.let { findLocalDebugLink(it, debugLink) }
                add(
                    NativeDebugCompanion(
                        kind = NativeDebugCompanionKind.GnuDebugLink,
                        status = if (resolved != null) NativeDebugCompanionStatus.Found else NativeDebugCompanionStatus.Missing,
                        name = debugLink,
                        location = resolved?.toString(),
                        description = if (resolved != null) {
                            "The .gnu_debuglink section names a nearby split debug file."
                        } else {
                            "The binary names this split debug file, but Hexana did not find it next to the binary or in a local .debug directory."
                        },
                        range = sections.firstOrNull { it.name == ".gnu_debuglink" }?.headerRange,
                    )
                )
            }

            binaryPath?.let { path ->
                addAll(findNearbyCompanions(path, debugLink))
            } ?: add(
                NativeDebugCompanion(
                    kind = NativeDebugCompanionKind.SeparateDebugFile,
                    status = NativeDebugCompanionStatus.Unverified,
                    name = "nearby files",
                    location = null,
                    description = "Native file path is unavailable, so nearby debug companions cannot be scanned.",
                )
            )
        }

        return NativeDebugContext(
            binaryPath = binaryPath,
            embeddedDebugInfoPresent = embeddedDebugSections.isNotEmpty(),
            elfBuildId = buildId,
            gnuDebugLink = debugLink,
            companions = companions.distinctBy { listOf(it.kind.name, it.status.name, it.name, it.location).joinToString("|") },
        )
    }
}

private fun findNearbyCompanions(binaryPath: Path, gnuDebugLink: String?): List<NativeDebugCompanion> {
    val directory = binaryPath.parent ?: return emptyList()
    val fileName = binaryPath.fileName.name
    val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
    val exactDebugLink = gnuDebugLink?.takeIf { it.isNotBlank() }

    val expected = listOfNotNull(
        exactDebugLink?.let { it to NativeDebugCompanionKind.SeparateDebugFile },
        "$fileName.debug" to NativeDebugCompanionKind.SeparateDebugFile,
        "$stem.debug" to NativeDebugCompanionKind.SeparateDebugFile,
        "$stem.dbg" to NativeDebugCompanionKind.LegacyDbgFile,
        "$fileName.dbg" to NativeDebugCompanionKind.LegacyDbgFile,
    ).distinct()

    val candidates = mutableListOf<NativeDebugCompanion>()
    expected.forEach { (name, kind) ->
        listOf(directory.resolve(name), directory.resolve(".debug").resolve(name)).firstOrNull { it.exists() }?.let { path ->
            candidates.add(
                NativeDebugCompanion(
                    kind = kind,
                    status = if (name == exactDebugLink) NativeDebugCompanionStatus.Found else NativeDebugCompanionStatus.Candidate,
                    name = name,
                    location = path.toString(),
                    description = if (name == exactDebugLink) {
                        "This file matches the .gnu_debuglink name."
                    } else {
                        "Nearby file name matches a common debug companion naming pattern. Identity is not verified yet."
                    },
                )
            )
        }
    }

    directory.toFile().listFiles()
        .orEmpty()
        .asSequence()
        .take(NEARBY_FILE_SCAN_LIMIT)
        .filter { it.isFile }
        .map { it.toPath() }
        .filter { it.isCrashArtifact() }
        .forEach { path ->
            candidates.add(
                NativeDebugCompanion(
                    kind = NativeDebugCompanionKind.CrashDump,
                    status = NativeDebugCompanionStatus.Candidate,
                    name = path.fileName.name,
                    location = path.toString(),
                    description = "Nearby crash artifact. Hexana surfaces it as context; full debugger sessions belong to CLion.",
                )
            )
        }

    return candidates.sortedWith(compareBy<NativeDebugCompanion> { it.kind.ordinal }.thenBy { it.name })
}

private fun findLocalDebugLink(directory: Path, fileName: String): Path? =
    listOf(directory.resolve(fileName), directory.resolve(".debug").resolve(fileName)).firstOrNull { it.exists() }

private fun localBuildIdDebugPath(buildId: String): Path? {
    if (buildId.length < 3) return null
    return Path.of(".build-id", buildId.take(2), "${buildId.drop(2)}.debug")
}

private fun Path.isCrashArtifact(): Boolean {
    val lower = fileName.name.lowercase()
    return lower == "core" ||
            lower.startsWith("core.") ||
            lower.endsWith(".core") ||
            lower.endsWith(".dmp") ||
            lower.endsWith(".mdmp")
}

private fun ElfSectionHeader.isDebugSection(): Boolean =
    name.startsWith(".debug") || name.startsWith(".zdebug")

private fun parseGnuDebugLink(bytes: CommonByteBuffer, section: ElfSectionHeader?): String? {
    val range = section?.contentRange ?: return null
    if (range.size <= 0) return null
    val end = range.offset + range.size
    val builder = StringBuilder()
    for (index in range.offset until end) {
        val value = bytes[index].toInt() and 0xFF
        if (value == 0) break
        if (value < 0x20 || value > 0x7E) return null
        builder.append(value.toChar())
    }
    return builder.toString().takeIf { it.isNotBlank() }
}

private fun parseElfBuildId(bytes: CommonByteBuffer, section: ElfSectionHeader?): String? {
    val range = section?.contentRange ?: return null
    val endian = inferEndian(bytes) ?: return null
    var offset = range.offset
    val end = range.offset + range.size

    while (offset + NOTE_HEADER_SIZE <= end) {
        val nameSize = bytes.u32(offset, endian).toIntOrNull() ?: return null
        val descriptorSize = bytes.u32(offset + 4, endian).toIntOrNull() ?: return null
        val type = bytes.u32(offset + 8, endian)
        offset += NOTE_HEADER_SIZE

        val nameStart = offset
        val nameEnd = nameStart + nameSize
        if (nameEnd > end) return null
        val name = bytes.readAscii(nameStart, nameSize).trimEnd(' ')
        offset = align4(nameEnd)

        val descriptorStart = offset
        val descriptorEnd = descriptorStart + descriptorSize
        if (descriptorEnd > end) return null

        if (name == "GNU" && type == NT_GNU_BUILD_ID && descriptorSize > 0) {
            return (descriptorStart until descriptorEnd).joinToString("") { index ->
                (bytes[index].toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        }
        offset = align4(descriptorEnd)
    }
    return null
}

private fun inferEndian(bytes: CommonByteBuffer): NativeBinaryEndian? =
    if (bytes.size <= 5) null else when (bytes[5].toInt() and 0xFF) {
        1 -> NativeBinaryEndian.Little
        2 -> NativeBinaryEndian.Big
        else -> null
    }

private fun CommonByteBuffer.u32(offset: Int, endian: NativeBinaryEndian): UInt {
    val b0 = this[offset].toInt() and 0xFF
    val b1 = this[offset + 1].toInt() and 0xFF
    val b2 = this[offset + 2].toInt() and 0xFF
    val b3 = this[offset + 3].toInt() and 0xFF
    return when (endian) {
        NativeBinaryEndian.Little -> ((b0) or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toUInt()
        NativeBinaryEndian.Big -> ((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3).toUInt()
        NativeBinaryEndian.Unknown -> error("Unsupported endian")
    }
}

private fun CommonByteBuffer.readAscii(offset: Int, size: Int): String =
    buildString(size) {
        for (index in offset until offset + size) {
            append((this@readAscii[index].toInt() and 0xFF).toChar())
        }
    }

private fun UInt.toIntOrNull(): Int? =
    if (this <= Int.MAX_VALUE.toUInt()) toInt() else null

private fun align4(value: Int): Int =
    (value + 3) and -4

private const val NOTE_HEADER_SIZE = 12
private const val NT_GNU_BUILD_ID = 3u
private const val NEARBY_FILE_SCAN_LIMIT = 512
