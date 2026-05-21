package org.jetbrains.hexana.shared

import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.NativeFunctionRecord
import org.jetbrains.hexana.providers.elf.ElfHeader
import org.jetbrains.hexana.providers.elf.ElfSectionHeader

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

internal fun hex(value: Int): String = "0x${value.toUInt().toString(16).uppercase()}"
internal fun hex(value: Long): String = "0x${value.toString(16).uppercase()}"
internal fun hex(value: UInt): String = "0x${value.toString(16).uppercase()}"
internal fun hex(value: ULong): String = "0x${value.toString(16).uppercase()}"

internal const val SHT_DYNSYM = 11u
