package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.macho.MachOSection
import org.jetbrains.hexana.providers.pe.PeDebugEntry

data class NativeDebugContext(
    val binaryPath: String?,
    val embeddedDebugInfoPresent: Boolean,
    val elfBuildId: String?,
    val gnuDebugLink: String?,
    val machOUuid: String?,
    val pePdbPath: String?,
    val peCodeViewId: String?,
    val companions: List<NativeDebugCompanion>,
)

data class NativeDebugCompanion(
    val kind: NativeDebugCompanionKind,
    val status: NativeDebugCompanionStatus,
    val name: String,
    val location: String?,
    val description: String,
    val range: NativeByteRange? = null,
)

enum class NativeDebugCompanionKind(val label: String) {
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

enum class NativeDebugCompanionStatus(val label: String) {
    Found("found"),
    Candidate("candidate"),
    Missing("missing"),
    Unverified("unverified"),
}


object NativeDebugCompanionScanner {
    fun scan(provider: NativeBinaryProvider, fs: FileSystem? = null): NativeDebugContext {
        val elfSections = (provider as? ElfBinaryProvider)?.sections.orEmpty()
        val machO = provider as? MachOBinaryProvider
        val pe = provider as? PeBinaryProvider
        return scan(
            binaryPath = provider.sourcePath,
            bytes = provider.bytes,
            sections = elfSections,
            machOSections = machO?.sections.orEmpty(),
            machOUuid = machO?.uuid?.value,
            machOUuidRange = machO?.uuid?.uuidRange,
            peDebugEntries = pe?.debugEntries.orEmpty(),
            fs = fs,
        )
    }

    fun scan(
        binaryPath: String?,
        bytes: CommonByteBuffer,
        sections: List<ElfSectionHeader>,
        machOSections: List<MachOSection> = emptyList(),
        machOUuid: String? = null,
        machOUuidRange: NativeByteRange? = null,
        peDebugEntries: List<PeDebugEntry> = emptyList(),
        fs: FileSystem? = null,
    ): NativeDebugContext {
        val embeddedElfDebug = sections.filter { it.isDebugSection() }
        val embeddedMachODebug = machOSections.filter { it.isMachOdebugSection() }
        val embeddedDebugInfoPresent = embeddedElfDebug.isNotEmpty() || embeddedMachODebug.isNotEmpty()
        val buildId = parseElfBuildId(bytes, sections.firstOrNull { it.name == ".note.gnu.build-id" })
        val debugLink = parseGnuDebugLink(bytes, sections.firstOrNull { it.name == ".gnu_debuglink" })
        val codeView = peDebugEntries.firstNotNullOfOrNull { it.codeView }
        val pePdbPath = codeView?.pdbPath?.takeIf { it.isNotBlank() }
        val peCodeViewId = codeView?.let { info ->
            listOfNotNull(info.guid, info.age?.let { "age $it" }).joinToString(", ").takeIf { it.isNotBlank() }
        }

        val companions = buildList {
            if (embeddedDebugInfoPresent) {
                val names = (embeddedElfDebug.map { it.name } + embeddedMachODebug.map { it.name }).joinToString()
                val range = embeddedElfDebug.firstOrNull()?.headerRange ?: embeddedMachODebug.firstOrNull()?.headerRange
                add(
                    NativeDebugCompanion(
                        kind = NativeDebugCompanionKind.EmbeddedDebugInfo,
                        status = NativeDebugCompanionStatus.Found,
                        name = names,
                        location = null,
                        description = "Debug sections are embedded in this binary.",
                        range = range,
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
                        location = localBuildIdDebugPath(buildId),
                        description = "ELF build-id is present. A matching split debug file may exist under a .build-id debug directory.",
                        range = sections.firstOrNull { it.name == ".note.gnu.build-id" }?.headerRange,
                    )
                )
            }

            if (machOUuid != null) {
                add(
                    NativeDebugCompanion(
                        kind = NativeDebugCompanionKind.MachOUuid,
                        status = NativeDebugCompanionStatus.Found,
                        name = machOUuid,
                        location = null,
                        description = "Mach-O UUID is present. A matching dSYM bundle can be verified against this identity later.",
                        range = machOUuidRange,
                    )
                )
            }

            if (pePdbPath != null || peCodeViewId != null) {
                add(
                    NativeDebugCompanion(
                        kind = NativeDebugCompanionKind.PdbFile,
                        status = NativeDebugCompanionStatus.Unverified,
                        name = pePdbPath ?: "CodeView debug record",
                        location = pePdbPath?.let { resolvePdbPath(it, binaryPath, fs) },
                        description = buildString {
                            append("PE CodeView debug identity is embedded")
                            peCodeViewId?.let { append(": $it") }
                            append(". Matching PDB contents are not verified yet.")
                        },
                        range = peDebugEntries.firstOrNull { it.codeView != null }?.dataRange,
                    )
                )
            }

            if (debugLink != null) {
                val resolvedLocation = resolveNearby(debugLink, binaryPath, fs)
                    ?: resolveNearby(".debug/$debugLink", binaryPath, fs)
                add(
                    NativeDebugCompanion(
                        kind = NativeDebugCompanionKind.GnuDebugLink,
                        status = if (resolvedLocation != null) NativeDebugCompanionStatus.Found else NativeDebugCompanionStatus.Missing,
                        name = debugLink,
                        location = resolvedLocation,
                        description = if (resolvedLocation != null) {
                            "The .gnu_debuglink section names a nearby split debug file."
                        } else {
                            "The binary names this split debug file, but Hexana did not find it next to the binary or in a local .debug directory."
                        },
                        range = sections.firstOrNull { it.name == ".gnu_debuglink" }?.headerRange,
                    )
                )
            }

            if (fs != null && binaryPath != null) {
                addAll(findNearbyCompanions(binaryPath, debugLink, fs))
            } else if (binaryPath == null) {
                add(
                    NativeDebugCompanion(
                        kind = NativeDebugCompanionKind.SeparateDebugFile,
                        status = NativeDebugCompanionStatus.Unverified,
                        name = "nearby files",
                        location = null,
                        description = "Native file path is unavailable, so nearby debug companions cannot be scanned.",
                    )
                )
            }
        }

        return NativeDebugContext(
            binaryPath = binaryPath,
            embeddedDebugInfoPresent = embeddedDebugInfoPresent,
            elfBuildId = buildId,
            gnuDebugLink = debugLink,
            machOUuid = machOUuid,
            pePdbPath = pePdbPath,
            peCodeViewId = peCodeViewId,
            companions = companions.distinctBy { listOf(it.kind.name, it.status.name, it.name, it.location).joinToString("|") },
        )
    }
}

fun buildDebugContextPrologueText(context: NativeDebugContext): String = buildString {
    append("Debug context: ")
    append(if (context.embeddedDebugInfoPresent) "embedded debug info present" else "embedded debug info absent")
    context.elfBuildId?.let { append(", build-id $it") }
    context.gnuDebugLink?.let { append(", debuglink $it") }
    context.machOUuid?.let { append(", Mach-O UUID $it") }
    context.pePdbPath?.let { append(", PDB $it") }
    context.peCodeViewId?.let { append(", CodeView $it") }
    if (context.binaryPath == null) append(". Nearby companion scan unavailable for in-memory artifacts.")
}

private fun ElfSectionHeader.isDebugSection(): Boolean =
    name.startsWith(".debug") || name.startsWith(".zdebug")

private fun MachOSection.isMachOdebugSection(): Boolean =
    name.startsWith("__debug") || name.startsWith("__zdebug")

private fun localBuildIdDebugPath(buildId: String): String? {
    if (buildId.length < 3) return null
    return ".build-id/${buildId.take(2)}/${buildId.drop(2)}.debug"
}

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
        val name = bytes.readAscii(nameStart, nameSize).trimEnd('\u0000', ' ')
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

private fun resolveNearby(relativePath: String, binaryPath: String?, fs: FileSystem?): String? {
    if (fs == null || binaryPath == null) return null
    val dir = fs.parentDir(binaryPath)
    val full = fs.joinPath(dir, relativePath)
    return if (fs.fileExists(full)) full else null
}

private fun resolvePdbPath(pdbPath: String, binaryPath: String?, fs: FileSystem?): String? {
    if (fs == null || binaryPath == null) return null
    val dir = fs.parentDir(binaryPath)
    // Extract just the filename from the PDB path (which may be an absolute Windows path)
    val pdbFileName = pdbPath.substringAfterLast('/').substringAfterLast('\\')
    val full = fs.joinPath(dir, pdbFileName)
    return if (fs.fileExists(full)) full else null
}

private fun findNearbyCompanions(
    binaryPath: String,
    gnuDebugLink: String?,
    fs: FileSystem,
): List<NativeDebugCompanion> {
    val dir = fs.parentDir(binaryPath)
    val fileName = binaryPath.substringAfterLast('/').substringAfterLast('\\')
    val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
    val exactDebugLink = gnuDebugLink?.takeIf { it.isNotBlank() }

    val expected = listOfNotNull(
        exactDebugLink?.let { it to NativeDebugCompanionKind.SeparateDebugFile },
        "$fileName.debug" to NativeDebugCompanionKind.SeparateDebugFile,
        "$stem.debug" to NativeDebugCompanionKind.SeparateDebugFile,
        "$fileName.dSYM" to NativeDebugCompanionKind.DsymBundle,
        "$stem.dSYM" to NativeDebugCompanionKind.DsymBundle,
        "$stem.pdb" to NativeDebugCompanionKind.PdbFile,
        "$fileName.pdb" to NativeDebugCompanionKind.PdbFile,
        "$stem.dbg" to NativeDebugCompanionKind.LegacyDbgFile,
        "$fileName.dbg" to NativeDebugCompanionKind.LegacyDbgFile,
    ).distinct()

    val candidates = mutableListOf<NativeDebugCompanion>()
    expected.forEach { (name, kind) ->
        val direct = fs.joinPath(dir, name)
        val inDebugDir = fs.joinPath(fs.joinPath(dir, ".debug"), name)
        val found = when {
            fs.fileExists(direct) -> direct
            fs.fileExists(inDebugDir) -> inDebugDir
            else -> null
        }
        if (found != null) {
            candidates.add(
                NativeDebugCompanion(
                    kind = kind,
                    status = if (name == exactDebugLink) NativeDebugCompanionStatus.Found else NativeDebugCompanionStatus.Candidate,
                    name = name,
                    location = found,
                    description = if (name == exactDebugLink) {
                        "This file matches the .gnu_debuglink name."
                    } else {
                        "Nearby file name matches a common debug companion naming pattern. Identity is not verified yet."
                    },
                )
            )
        }
    }

    // Scan for crash artifacts
    fs.listEntries(dir)
        .asSequence()
        .take(NEARBY_FILE_SCAN_LIMIT)
        .filter { it.isFile && isCrashArtifact(it.name) }
        .forEach { entry ->
            candidates.add(
                NativeDebugCompanion(
                    kind = NativeDebugCompanionKind.CrashDump,
                    status = NativeDebugCompanionStatus.Candidate,
                    name = entry.name,
                    location = fs.joinPath(dir, entry.name),
                    description = "Nearby crash artifact. Hexana surfaces it as context; full debugger sessions belong to CLion.",
                )
            )
        }

    return candidates.sortedWith(compareBy<NativeDebugCompanion> { it.kind.ordinal }.thenBy { it.name })
}

private fun isCrashArtifact(name: String): Boolean {
    val lower = name.lowercase()
    return lower == "core" ||
            lower.startsWith("core.") ||
            lower.endsWith(".core") ||
            lower.endsWith(".dmp") ||
            lower.endsWith(".mdmp")
}

private const val NEARBY_FILE_SCAN_LIMIT = 512

private const val NOTE_HEADER_SIZE = 12
private const val NT_GNU_BUILD_ID = 3u
