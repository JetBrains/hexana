package org.jetbrains.hexana.wasm.dwarf

import org.jetbrains.hexana.dwarf.DwarfAbbrevSection
import org.jetbrains.hexana.dwarf.DwarfAccessor
import org.jetbrains.hexana.dwarf.DwarfAddressSection
import org.jetbrains.hexana.dwarf.DwarfInfoSection
import org.jetbrains.hexana.dwarf.DwarfLineSection
import org.jetbrains.hexana.dwarf.DwarfLineStrSection
import org.jetbrains.hexana.dwarf.DwarfSection
import org.jetbrains.hexana.dwarf.DwarfStrSection
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.customSectionByName

class WasmDwarfAccessor(val module: BaseModule): DwarfAccessor {
    override fun section(name: String): DwarfSection? {
        val customSection = module.customSectionByName(name) ?: return null
        return when (name) {
            ".debug_line" -> DwarfLineSection(customSection.contentOffset, customSection.contentSize, this@WasmDwarfAccessor)
            ".debug_info" -> DwarfInfoSection(customSection.contentOffset, customSection.contentSize, this@WasmDwarfAccessor)
            ".debug_abbrev" -> DwarfAbbrevSection(customSection.contentOffset, customSection.contentSize, this@WasmDwarfAccessor)
            ".debug_str" -> DwarfStrSection(
                customSection.contentOffset,
                customSection.contentSize,
                this@WasmDwarfAccessor
            )
            ".debug_line_str" -> DwarfLineStrSection(customSection.contentOffset, customSection.contentSize, this@WasmDwarfAccessor)
            ".debug_addr" -> DwarfAddressSection(customSection.contentOffset, customSection.contentSize, this@WasmDwarfAccessor)
            else -> null
        }

    }

    override fun debugLineSection(): DwarfLineSection? {
        return section(".debug_line") as? DwarfLineSection
    }

    override fun debugInfoSection(): DwarfInfoSection? {
        return section(".debug_info") as? DwarfInfoSection
    }

    override fun debugAbbrevSection(): DwarfAbbrevSection? {
        return section(".debug_abbrev") as? DwarfAbbrevSection
    }

    override fun debugStrSection(): DwarfStrSection? {
        return section(".debug_str") as? DwarfStrSection
    }

    override fun debugAddrSection(): DwarfAddressSection? {
        return section(".debug_addr") as? DwarfAddressSection
    }
}