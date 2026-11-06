package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.dwarf.DwarfAbbrevSection
import org.jetbrains.hexana.dwarf.DwarfAccessor
import org.jetbrains.hexana.dwarf.DwarfAddressSection
import org.jetbrains.hexana.dwarf.DwarfInfoSection
import org.jetbrains.hexana.dwarf.DwarfLineSection
import org.jetbrains.hexana.dwarf.DwarfLineStrSection
import org.jetbrains.hexana.dwarf.DwarfLocSection
import org.jetbrains.hexana.dwarf.DwarfSection
import org.jetbrains.hexana.dwarf.DwarfStrSection

/**
 * DWARF sections of an ELF file, at their offsets in the buffer the DWARF decoder reads.
 *
 * [base] is the file offset of that buffer's first byte: 0 when it is the whole file, the start
 * of the debug sections when a file past 2 GiB hands the decoder a window over just them (see
 * [debugSectionSpan]). A section outside the window is reported as absent.
 */
class ElfDwarfAccessor(
    private val sections: List<ElfSectionHeader>,
    private val base: Long = 0,
) : DwarfAccessor {
    override fun section(name: String): DwarfSection? {
        val section = sections.firstOrNull { it.name == name } ?: return null
        val contentRange = section.contentRange ?: return null
        val offset = contentRange.start - base
        if (offset < 0 || contentRange.length > Int.MAX_VALUE || offset > Int.MAX_VALUE - contentRange.length) return null
        val start = offset.toInt()
        val size = contentRange.length.toInt()
        return when (name) {
            ".debug_line" -> DwarfLineSection(start, size, this)
            ".debug_info" -> DwarfInfoSection(start, size, this)
            ".debug_abbrev" -> DwarfAbbrevSection(start, size, this)
            ".debug_str" -> DwarfStrSection(start, size, this)
            ".debug_line_str" -> DwarfLineStrSection(start, size, this)
            ".debug_addr" -> DwarfAddressSection(start, size, this)
            ".debug_loc" -> DwarfLocSection(start, size, this)
            else -> DwarfSection(start, size, this)
        }
    }

    override fun debugLineSection(): DwarfLineSection? =
        section(".debug_line") as? DwarfLineSection

    override fun debugInfoSection(): DwarfInfoSection? =
        section(".debug_info") as? DwarfInfoSection

    override fun debugAbbrevSection(): DwarfAbbrevSection? =
        section(".debug_abbrev") as? DwarfAbbrevSection

    override fun debugStrSection(): DwarfStrSection? =
        section(".debug_str") as? DwarfStrSection

    override fun debugAddrSection(): DwarfAddressSection? =
        section(".debug_addr") as? DwarfAddressSection

    override fun debugLocSection(): DwarfLocSection? =
        section(".debug_loc") as? DwarfLocSection
}

/**
 * The file range covering every `.debug_*` section with content, or `null` when there are none.
 * Debug sections sit together (usually at the end of the file), so for a file past 2 GiB this span
 * is what the `Int`-addressed DWARF decoder can be handed as one window.
 */
fun debugSectionSpan(sections: List<ElfSectionHeader>): LongRange? {
    val ranges = sections.filter { it.name.startsWith(".debug_") }.mapNotNull { it.contentRange?.takeIf { r -> r.length > 0 } }
    if (ranges.isEmpty()) return null
    return ranges.minOf { it.start } until ranges.maxOf { it.end }
}
