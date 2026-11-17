package org.jetbrains.hexana.providers.macho

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

class MachODwarfAccessorTest {
    @Test
    fun `maps Mach-O debug sections to DWARF section types`() {
        val accessor = MachODwarfAccessor(
            listOf(
                section("__debug_line", 0x100, 0x20),
                section("__debug_info", 0x120, 0x30),
                section("__debug_abbrev", 0x150, 0x10),
                section("__debug_str", 0x160, 0x40),
                section("__debug_line_str", 0x1a0, 0x18),
                section("__debug_addr", 0x1b8, 0x08),
                section("__debug_rnglists", 0x1c0, 0x10),
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
        val accessor = MachODwarfAccessor(
            listOf(
                section("__debug_line", 0x100, 0x20),
                section("__debug_info", 0x120, 0x30, contentRange = null),
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
    ): MachOSection =
        MachOSection(
            index = 0,
            segmentIndex = 0,
            name = name,
            segmentName = "__DWARF",
            address = 0uL,
            size = size.toULong(),
            fileOffset = offset.toULong(),
            alignment = 1u,
            relocationOffset = 0u,
            relocationCount = 0u,
            flags = 0u,
            headerRange = NativeByteRange(0, 80),
            contentRange = contentRange,
        )
}
