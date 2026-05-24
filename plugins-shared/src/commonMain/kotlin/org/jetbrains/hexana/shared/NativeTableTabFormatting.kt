package org.jetbrains.hexana.shared

import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.NativeFunctionRecord
import org.jetbrains.hexana.providers.elf.ElfHeader
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.macho.MachODylibKind
import org.jetbrains.hexana.providers.macho.MachOFatHeader
import org.jetbrains.hexana.providers.macho.MachOHeader
import org.jetbrains.hexana.providers.pe.PeCoffHeader
import org.jetbrains.hexana.providers.pe.PeDosHeader
import org.jetbrains.hexana.providers.pe.PeImportEntry
import org.jetbrains.hexana.providers.pe.PeImportLibrary
import org.jetbrains.hexana.providers.pe.PeOptionalHeader

internal data class HeaderRow(val label: String, val value: String, val range: NativeByteRange?)

internal fun keyValueTab(
    name: String,
    rows: List<HeaderRow>,
    labelWidth: ColumnWidth = ColumnWidth.Fill(0.35f),
): TableTabDescriptor =
    TableTabDescriptor(
        name = name,
        columns = listOf(
            col("Field", labelWidth) { CellValue(rows[it].label) },
            col("Value", ColumnWidth.Fill()) { CellValue(rows[it].value) },
        ),
        rowCount = rows.size,
        rowSelectionProvider = { rows.getOrNull(it)?.range?.let(::selection) },
    )

internal fun elfHeaderRows(header: ElfHeader, sections: List<ElfSectionHeader>?): List<HeaderRow> = buildList {
    val ranges = header.fieldRanges
    add(HeaderRow("Ident", "${header.wordSize}, ${header.endian}", ranges.ident))
    add(HeaderRow("OS ABI", header.osAbi.toString(), ranges.osAbi))
    add(HeaderRow("ABI Version", header.abiVersion.toString(), ranges.abiVersion))
    add(HeaderRow("File Type", hex(header.fileType), ranges.fileType))
    add(HeaderRow("Machine", hex(header.machine), ranges.machine))
    add(HeaderRow("Version", hex(header.version), ranges.version))
    add(HeaderRow("Entry Point", hex(header.entryPoint), ranges.entryPoint))
    add(HeaderRow("Program Header Offset", hex(header.programHeaderOffset), ranges.programHeaderOffset))
    add(HeaderRow("Section Header Offset", hex(header.sectionHeaderOffset), ranges.sectionHeaderOffset))
    add(HeaderRow("Flags", hex(header.flags), ranges.flags))
    add(HeaderRow("Header Size", header.headerSize.toString(), ranges.headerSize))
    add(HeaderRow("Program Header Entry Size", header.programHeaderEntrySize.toString(), ranges.programHeaderEntrySize))
    add(HeaderRow("Program Header Count", header.programHeaderCount.toString(), ranges.programHeaderCount))
    add(HeaderRow("Section Header Entry Size", header.sectionHeaderEntrySize.toString(), ranges.sectionHeaderEntrySize))
    add(HeaderRow("Section Header Count", header.sectionHeaderCount.toString(), ranges.sectionHeaderCount))
    add(HeaderRow("Section Name String Table Index", header.sectionNameStringTableIndex.toString(), ranges.sectionNameStringTableIndex))
    val debugInfoSection = sections?.firstOrNull { it.name.startsWith(".debug") || it.name.startsWith(".zdebug") }
    add(HeaderRow("Debug Info", if (debugInfoSection != null) "present" else "absent", debugInfoSection?.headerRange))
}

internal fun NativeFunctionRecord.kindLabel(): String =
    if (imported) "imported" else if (thunkLike) "thunk" else "defined"

internal fun NativeFunctionRecord.selection(): RowSelectionData? {
    val offset = fileOffset
    if (offset != null && offset in 0..Int.MAX_VALUE) {
        val size = size?.takeIf { it <= Int.MAX_VALUE.toULong() }?.toInt()?.takeIf { it > 0 } ?: 1
        return RowSelectionData(offset.toInt(), size)
    }
    return selection(recordRange)
}

internal fun selection(range: NativeByteRange): RowSelectionData =
    RowSelectionData(range.offset, range.size)

internal fun col(name: String, width: ColumnWidth = ColumnWidth.MeasuredMin(name), cellProvider: (Int) -> CellValue<*>): ColumnDescriptor =
    ColumnDescriptor(name, width, cellProvider)

internal fun symbolBinding(binding: Int): String = when (binding) {
    0 -> "local"
    1 -> "global"
    2 -> "weak"
    else -> binding.toString()
}

internal fun symbolType(type: Int): String = when (type) {
    0 -> "none"
    1 -> "object"
    2 -> "func"
    3 -> "section"
    4 -> "file"
    5 -> "common"
    6 -> "tls"
    else -> type.toString()
}

internal fun symbolKind(type: Int): String = when (type and 0x0E) {
    0x00 -> "undefined"
    0x02 -> "absolute"
    0x0E -> "section"
    0x0C -> "prebound"
    0x0A -> "indirect"
    else -> hex(type and 0x0E)
}

internal fun symbolFlags(type: Int): String = buildList {
    if ((type and 0x01) != 0) add("external")
    if ((type and 0x10) != 0) add("private")
    if ((type and 0xE0) != 0) add("stab")
}.joinToString("+").ifBlank { "-" }

internal fun machOFatHeaderRows(header: MachOFatHeader): List<HeaderRow> = listOf(
    HeaderRow("Format", "Universal Mach-O", null),
    HeaderRow("Word Size", header.wordSize.toString(), null),
    HeaderRow("Endian", header.endian.toString(), null),
    HeaderRow("Architectures", header.archCount.toString(), NativeByteRange(4, 4)),
)

/**
 * Mach-O CPU type → human-readable architecture name. Subtype is reserved
 * for future refinement (e.g. distinguishing arm64e from arm64); unknown
 * pairs fall back to the raw cpuType hex. Public so the IDE plugin can use
 * the same labels when naming opened slice editors.
 */
fun machOCpuArchName(cpuType: Int, @Suppress("UNUSED_PARAMETER") cpuSubtype: Int): String = when (cpuType) {
    0x00000007 -> "x86"
    0x01000007 -> "x86_64"
    0x0000000C -> "arm"
    0x0100000C -> "arm64"
    0x0200000C -> "arm64_32"
    0x00000012 -> "ppc"
    0x01000012 -> "ppc64"
    0x01000018 -> "riscv64"
    else -> hex(cpuType)
}

fun endianLabel(endian: NativeBinaryEndian): String = when (endian) {
    NativeBinaryEndian.Little -> "little"
    NativeBinaryEndian.Big -> "big"
    NativeBinaryEndian.Unknown -> "?"
}

internal fun machOHeaderRows(header: MachOHeader, uuid: String?): List<HeaderRow> = buildList {
    add(HeaderRow("Magic", hex(header.magic), NativeByteRange(0, 4)))
    add(HeaderRow("CPU Type", hex(header.cpuType), NativeByteRange(4, 4)))
    add(HeaderRow("CPU Subtype", hex(header.cpuSubtype), NativeByteRange(8, 4)))
    add(HeaderRow("File Type", hex(header.fileType), NativeByteRange(12, 4)))
    add(HeaderRow("Load Commands", header.loadCommandCount.toString(), NativeByteRange(16, 4)))
    add(HeaderRow("Load Commands Size", header.loadCommandsSize.toString(), NativeByteRange(20, 4)))
    add(HeaderRow("Flags", hex(header.flags), NativeByteRange(24, 4)))
    header.reserved?.let { add(HeaderRow("Reserved", hex(it), NativeByteRange(28, 4))) }
    uuid?.let { add(HeaderRow("UUID", it, null)) }
}

internal fun loadCommandName(type: UInt): String = when (type) {
    0x1u -> "LC_SEGMENT"
    0x2u -> "LC_SYMTAB"
    0xBu -> "LC_DYSYMTAB"
    0xCu -> "LC_LOAD_DYLIB"
    0xDu -> "LC_ID_DYLIB"
    0x80000018u -> "LC_LOAD_WEAK_DYLIB"
    0x19u -> "LC_SEGMENT_64"
    0x1Bu -> "LC_UUID"
    0x8000001Fu -> "LC_REEXPORT_DYLIB"
    0x20u -> "LC_LAZY_LOAD_DYLIB"
    0x22u -> "LC_DYLD_INFO"
    0x80000022u -> "LC_DYLD_INFO_ONLY"
    0x80000023u -> "LC_LOAD_UPWARD_DYLIB"
    0x80000028u -> "LC_MAIN"
    else -> "unknown"
}

internal fun dylibKindLabel(kind: MachODylibKind): String = when (kind) {
    MachODylibKind.Load -> "load"
    MachODylibKind.WeakLoad -> "weak"
    MachODylibKind.Reexport -> "reexport"
    MachODylibKind.UpwardLoad -> "upward"
    MachODylibKind.LazyLoad -> "lazy"
    MachODylibKind.Id -> "id"
    MachODylibKind.Other -> "other"
}

internal fun version(value: UInt): String {
    val raw = value.toInt()
    return "${raw ushr 16}.${(raw ushr 8) and 0xFF}.${raw and 0xFF}"
}

internal fun peHeaderRows(dosHeader: PeDosHeader, coffHeader: PeCoffHeader, optionalHeader: PeOptionalHeader?): List<HeaderRow> = buildList {
    add(HeaderRow("DOS Magic", "MZ", dosHeader.mzRange))
    add(HeaderRow("PE Header Offset", hex(dosHeader.peHeaderOffset), dosHeader.peHeaderOffsetRange))
    add(HeaderRow("PE Signature", "PE", dosHeader.peSignatureRange))
    add(HeaderRow("Machine", machineName(coffHeader.machine), coffHeader.headerRange))
    add(HeaderRow("Sections", coffHeader.sectionCount.toString(), coffHeader.headerRange))
    add(HeaderRow("Timestamp", hex(coffHeader.timeDateStamp), coffHeader.headerRange))
    add(HeaderRow("Symbol Table Offset", hex(coffHeader.symbolTableOffset), coffHeader.headerRange))
    add(HeaderRow("Symbols", coffHeader.symbolCount.toString(), coffHeader.headerRange))
    add(HeaderRow("Optional Header Size", coffHeader.optionalHeaderSize.toString(), coffHeader.headerRange))
    add(HeaderRow("Characteristics", hex(coffHeader.characteristics), coffHeader.headerRange))
    optionalHeader?.let { header ->
        add(HeaderRow("Optional Magic", hex(header.magic), header.headerRange))
        add(HeaderRow("Word Size", header.wordSize.toString(), header.headerRange))
        add(HeaderRow("Entry Point RVA", hex(header.addressOfEntryPoint), header.headerRange))
        add(HeaderRow("Image Base", hex(header.imageBase), header.headerRange))
        add(HeaderRow("Section Alignment", hex(header.sectionAlignment), header.headerRange))
        add(HeaderRow("File Alignment", hex(header.fileAlignment), header.headerRange))
        add(HeaderRow("Image Size", hex(header.sizeOfImage), header.headerRange))
        add(HeaderRow("Headers Size", hex(header.sizeOfHeaders), header.headerRange))
        add(HeaderRow("Subsystem", subsystemName(header.subsystem), header.headerRange))
        add(HeaderRow("DLL Characteristics", hex(header.dllCharacteristics), header.headerRange))
        add(HeaderRow("Data Directories", header.dataDirectoryCount.toString(), header.headerRange))
    } ?: add(HeaderRow("Optional Header", "absent or unsupported", null))
}

internal fun peImportRows(libraries: List<PeImportLibrary>): List<PeImportRow> = buildList {
    libraries.forEach { library ->
        if (library.entries.isEmpty()) add(PeImportRow(library, null))
        else library.entries.forEach { add(PeImportRow(library, it)) }
    }
}

internal data class PeImportRow(val library: PeImportLibrary, val entry: PeImportEntry?)

internal fun PeImportEntry.displayName(): String =
    name ?: ordinal?.let { "ordinal #$it" } ?: "<unnamed>"

internal fun machineName(machine: Int): String = when (machine) {
    0x014C -> "I386 (${hex(machine)})"
    0x8664 -> "AMD64 (${hex(machine)})"
    0xAA64 -> "ARM64 (${hex(machine)})"
    0x01C0 -> "ARM (${hex(machine)})"
    0x01C4 -> "ARMNT (${hex(machine)})"
    else -> hex(machine)
}

internal fun subsystemName(subsystem: Int): String = when (subsystem) {
    1 -> "Native (${hex(subsystem)})"
    2 -> "Windows GUI (${hex(subsystem)})"
    3 -> "Windows CUI (${hex(subsystem)})"
    9 -> "Windows CE GUI (${hex(subsystem)})"
    10 -> "EFI application (${hex(subsystem)})"
    else -> hex(subsystem)
}

internal fun relocationTypeName(type: Int): String = when (type) {
    0 -> "ABSOLUTE (${hex(type)})"
    3 -> "HIGHLOW (${hex(type)})"
    10 -> "DIR64 (${hex(type)})"
    else -> hex(type)
}

internal fun debugTypeName(type: UInt): String = when (type) {
    2u -> "CodeView (${hex(type)})"
    12u -> "VC Feature (${hex(type)})"
    16u -> "Repro (${hex(type)})"
    20u -> "Extended DLL Characteristics (${hex(type)})"
    else -> hex(type)
}

internal fun storageClassName(storageClass: Int): String = when (storageClass) {
    2 -> "External (${hex(storageClass)})"
    3 -> "Static (${hex(storageClass)})"
    103 -> "File (${hex(storageClass)})"
    else -> hex(storageClass)
}

internal fun hex(value: Int): String = "0x${value.toUInt().toString(16).uppercase()}"
internal fun hex(value: Long): String = "0x${value.toString(16).uppercase()}"
internal fun hex(value: UInt): String = "0x${value.toString(16).uppercase()}"
internal fun hex(value: ULong): String = "0x${value.toString(16).uppercase()}"

internal const val SHT_DYNSYM = 11u
