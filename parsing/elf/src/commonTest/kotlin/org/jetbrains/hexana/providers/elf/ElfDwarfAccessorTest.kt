package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.dwarf.DwarfAbbrevSection
import org.jetbrains.hexana.dwarf.DwarfAddressSection
import org.jetbrains.hexana.dwarf.DwarfInfoSection
import org.jetbrains.hexana.dwarf.DwarfLineSection
import org.jetbrains.hexana.dwarf.DwarfLineStrSection
import org.jetbrains.hexana.dwarf.DwarfSection
import org.jetbrains.hexana.dwarf.DwarfStrSection
import org.jetbrains.hexana.parsing.common.NativeByteRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ElfDwarfAccessorTest {
    @Test
    fun `maps ELF debug sections to DWARF section types`() {
        val accessor = ElfDwarfAccessor(
            listOf(
                section(".debug_line", 0x100, 0x20),
                section(".debug_info", 0x120, 0x30),
                section(".debug_abbrev", 0x150, 0x10),
                section(".debug_str", 0x160, 0x40),
                section(".debug_line_str", 0x1a0, 0x18),
                section(".debug_addr", 0x1b8, 0x08),
                section(".debug_rnglists", 0x1c0, 0x10),
            )
        )

        assertIs<DwarfLineSection>(accessor.debugLineSection())
        assertIs<DwarfInfoSection>(accessor.debugInfoSection())
        assertIs<DwarfAbbrevSection>(accessor.debugAbbrevSection())
        assertIs<DwarfStrSection>(accessor.debugStrSection())
        assertIs<DwarfLineStrSection>(accessor.section(".debug_line_str"))
        assertIs<DwarfAddressSection>(accessor.debugAddrSection())

        val generic = assertIs<DwarfSection>(accessor.section(".debug_rnglists"))
        assertEquals(0x1c0, generic.contentOffset)
        assertEquals(0x10, generic.contentSize)
    }

    @Test
    fun `returns null for missing or sectionless debug data`() {
        val accessor = ElfDwarfAccessor(
            listOf(
                section(".debug_line", 0x100, 0x20),
                section(".debug_info", 0x120, 0x30, contentRange = null),
            )
        )

        assertNull(accessor.section(".missing"))
        assertNull(accessor.debugInfoSection())
    }

    private fun section(
        name: String,
        offset: Int,
        size: Int,
        contentRange: NativeByteRange? = NativeByteRange(offset, size),
    ): ElfSectionHeader =
        ElfSectionHeader(
            index = 0,
            nameOffset = 0u,
            name = name,
            type = 1u,
            flags = 0u,
            address = 0u,
            fileOffset = offset.toULong(),
            size = size.toULong(),
            link = 0u,
            info = 0u,
            addressAlignment = 1u,
            entrySize = 0u,
            headerRange = NativeByteRange(0, 64),
            contentRange = contentRange,
        )
}
