package org.jetbrains.hexana.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.elf.ElfProgramHeader
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.elf.ElfSymbol

class NativeFunctionDiscoveryTest {
    @Test
    fun `discovers defined functions from ELF symbols`() {
        val functions = NativeFunctionDiscovery.fromElf(
            symbols = listOf(
                symbol(index = 0, name = "main", tableSectionIndex = 1, sectionIndex = 3, value = 0x401000uL, size = 0x20uL),
                symbol(index = 1, name = "helper", tableSectionIndex = 1, sectionIndex = 3, value = 0x401020uL, size = 0uL),
                symbol(index = 2, name = "puts", tableSectionIndex = 2, sectionIndex = 0, value = 0uL, size = 0uL),
            ),
            sections = listOf(
                section(index = 1, name = ".symtab"),
                section(index = 2, name = ".dynsym"),
                section(index = 3, name = ".text", address = 0x401000uL, fileOffset = 0x1000uL, size = 0x80uL),
            ),
            programHeaders = listOf(loadSegment()),
        )

        assertEquals(3, functions.size)

        val main = functions[0]
        assertEquals("main", main.name)
        assertEquals(NativeFunctionSymbolSource.Symtab, main.source)
        assertFalse(main.imported)
        assertEquals(0x1000, main.fileOffset)
        assertEquals(0x20uL, main.size)
        assertEquals(NativeFunctionRangeConfidence.SymbolSize, main.rangeConfidence)

        val helper = functions[1]
        assertEquals(NativeFunctionRangeConfidence.Unknown, helper.rangeConfidence)
        assertNull(helper.size)

        val puts = functions[2]
        assertEquals(NativeFunctionSymbolSource.Dynsym, puts.source)
        assertTrue(puts.imported)
        assertNull(puts.fileOffset)
    }

    @Test
    fun `falls back to next symbol in same section for function size`() {
        val functions = NativeFunctionDiscovery.fromElf(
            symbols = listOf(
                symbol(index = 0, name = "first", sectionIndex = 3, value = 0x401000uL, size = 0uL),
                symbol(index = 1, name = "second", sectionIndex = 3, value = 0x401040uL, size = 0x10uL),
            ),
            sections = listOf(
                section(index = 1, name = ".symtab"),
                section(index = 3, name = ".text", address = 0x401000uL, fileOffset = 0x1000uL, size = 0x80uL),
            ),
            programHeaders = listOf(loadSegment()),
        )

        assertEquals(0x40uL, functions[0].size)
        assertEquals(NativeFunctionRangeConfidence.NextSymbolInSection, functions[0].rangeConfidence)
    }

    @Test
    fun `demangles common Itanium C++ function names`() {
        assertEquals("foo(int)", NativeSymbolDemangler.demangle("_Z3fooi"))
        assertEquals("ns::foo(const char*)", NativeSymbolDemangler.demangle("_ZN2ns3fooEPKc"))
        assertNull(NativeSymbolDemangler.demangle("main"))
    }
}

private fun symbol(
    index: Int,
    name: String,
    tableSectionIndex: Int = 1,
    sectionIndex: Int,
    value: ULong,
    size: ULong,
): ElfSymbol = ElfSymbol(
    index = index,
    tableSectionIndex = tableSectionIndex,
    nameOffset = 0u,
    name = name,
    info = 0,
    binding = 1,
    type = 2,
    other = 0,
    visibility = 0,
    sectionIndex = sectionIndex,
    value = value,
    size = size,
    recordRange = NativeByteRange(index * 24, 24),
)

private fun section(
    index: Int,
    name: String,
    address: ULong = 0uL,
    fileOffset: ULong = 0uL,
    size: ULong = 0uL,
): ElfSectionHeader = ElfSectionHeader(
    index = index,
    nameOffset = 0u,
    name = name,
    type = 0u,
    flags = 0uL,
    address = address,
    fileOffset = fileOffset,
    size = size,
    link = 0u,
    info = 0u,
    addressAlignment = 1uL,
    entrySize = 0uL,
    headerRange = NativeByteRange(0, 64),
    contentRange = NativeByteRange(fileOffset.toInt(), size.toInt()),
)

private fun loadSegment(): ElfProgramHeader = ElfProgramHeader(
    index = 0,
    type = 1u,
    flags = 5u,
    fileOffset = 0x1000uL,
    virtualAddress = 0x401000uL,
    physicalAddress = 0x401000uL,
    fileSize = 0x100uL,
    memorySize = 0x100uL,
    alignment = 0x1000uL,
    headerRange = NativeByteRange(0, 56),
    fileRange = NativeByteRange(0x1000, 0x100),
)
