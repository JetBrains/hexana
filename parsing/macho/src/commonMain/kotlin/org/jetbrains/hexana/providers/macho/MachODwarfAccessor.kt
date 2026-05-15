package org.jetbrains.hexana.providers.macho

import org.jetbrains.hexana.dwarf.DwarfAbbrevSection
import org.jetbrains.hexana.dwarf.DwarfAccessor
import org.jetbrains.hexana.dwarf.DwarfAddressSection
import org.jetbrains.hexana.dwarf.DwarfInfoSection
import org.jetbrains.hexana.dwarf.DwarfLineSection
import org.jetbrains.hexana.dwarf.DwarfLineStrSection
import org.jetbrains.hexana.dwarf.DwarfLocSection
import org.jetbrains.hexana.dwarf.DwarfSection
import org.jetbrains.hexana.dwarf.DwarfStrSection

class MachODwarfAccessor(private val sections: List<MachOSection>) : DwarfAccessor {
    override fun section(name: String): DwarfSection? {
        val section = sections.firstOrNull { it.name == name.toMachODebugSectionName() } ?: return null
        val contentRange = section.contentRange ?: return null
        return when (name) {
            ".debug_line" -> DwarfLineSection(contentRange.offset, contentRange.size, this)
            ".debug_info" -> DwarfInfoSection(contentRange.offset, contentRange.size, this)
            ".debug_abbrev" -> DwarfAbbrevSection(contentRange.offset, contentRange.size, this)
            ".debug_str" -> DwarfStrSection(contentRange.offset, contentRange.size, this)
            ".debug_line_str" -> DwarfLineStrSection(contentRange.offset, contentRange.size, this)
            ".debug_addr" -> DwarfAddressSection(contentRange.offset, contentRange.size, this)
            ".debug_loc" -> DwarfLocSection(contentRange.offset, contentRange.size, this)
            else -> DwarfSection(contentRange.offset, contentRange.size, this)
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

private fun String.toMachODebugSectionName(): String =
    if (startsWith(".debug_")) "__${removePrefix(".")}" else this
