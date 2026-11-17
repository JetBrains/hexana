package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.parsing.common.NativeByteRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElfAddressMapperTest {
    @Test
    fun `maps virtual address through loadable segment file range`() {
        val offset = ElfAddressMapper.virtualAddressToFileOffset(
            virtualAddress = 0x401020uL,
            programHeaders = listOf(loadSegment()),
        )

        assertEquals(0x220uL, offset)
    }

    @Test
    fun `does not map segment memory-only bytes past file size`() {
        val offset = ElfAddressMapper.virtualAddressToFileOffset(
            virtualAddress = 0x401048uL,
            programHeaders = listOf(loadSegment()),
        )

        assertNull(offset)
    }

    @Test
    fun `maps file offset through loadable segment virtual range`() {
        val address = ElfAddressMapper.fileOffsetToVirtualAddress(
            fileOffset = 0x220uL,
            programHeaders = listOf(loadSegment()),
        )

        assertEquals(0x401020uL, address)
    }

    @Test
    fun `does not map file offset outside segment file range`() {
        val address = ElfAddressMapper.fileOffsetToVirtualAddress(
            fileOffset = 0x248uL,
            programHeaders = listOf(loadSegment()),
        )

        assertNull(address)
    }

    @Test
    fun `falls back to section addresses when no segment maps the address`() {
        val offset = ElfAddressMapper.virtualAddressToFileOffset(
            virtualAddress = 0x500008uL,
            programHeaders = emptyList(),
            sections = listOf(section()),
        )

        assertEquals(0x308uL, offset)
    }

    @Test
    fun `falls back to section file offsets when no segment maps the offset`() {
        val address = ElfAddressMapper.fileOffsetToVirtualAddress(
            fileOffset = 0x308uL,
            programHeaders = emptyList(),
            sections = listOf(section()),
        )

        assertEquals(0x500008uL, address)
    }

    @Test
    fun `returns null for unmapped virtual address`() {
        val offset = ElfAddressMapper.virtualAddressToFileOffset(
            virtualAddress = 0x600000uL,
            programHeaders = listOf(loadSegment()),
            sections = listOf(section()),
        )

        assertNull(offset)
    }

    private fun loadSegment() = ElfProgramHeader(
        index = 0,
        type = 1u,
        flags = 0x5u,
        fileOffset = 0x200uL,
        virtualAddress = 0x401000uL,
        physicalAddress = 0x401000uL,
        fileSize = 0x40uL,
        memorySize = 0x80uL,
        alignment = 0x1000uL,
        headerRange = NativeByteRange(0x40, 56),
        fileRange = NativeByteRange(0x200, 0x40),
    )

    private fun section() = ElfSectionHeader(
        index = 1,
        nameOffset = 1u,
        name = ".text",
        type = 1u,
        flags = 0x6uL,
        address = 0x500000uL,
        fileOffset = 0x300uL,
        size = 0x20uL,
        link = 0u,
        info = 0u,
        addressAlignment = 16uL,
        entrySize = 0uL,
        headerRange = NativeByteRange(0x100, 64),
        contentRange = NativeByteRange(0x300, 0x20),
    )
}
