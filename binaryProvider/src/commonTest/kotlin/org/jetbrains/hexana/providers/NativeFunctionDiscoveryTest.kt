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
import org.jetbrains.hexana.providers.pe.PeCoffSymbol
import org.jetbrains.hexana.providers.pe.PeSection

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

    @Test
    fun `maps relocatable ELF object function offsets as section-relative`() {
        // Layout mirrors a real ET_REL `.o` member of a `.a` (unwind-dw2.o): no program headers,
        // `.text` (section 1) at sh_addr 0 / sh_offset 64, and STT_FUNC symbols whose st_value is
        // a section-relative offset rather than a virtual address.
        val functions = NativeFunctionDiscovery.fromElf(
            symbols = listOf(
                symbol(index = 0, name = "init_table", tableSectionIndex = 9, sectionIndex = 1, value = 0uL, size = 344uL),
                symbol(index = 1, name = "uw_install", tableSectionIndex = 9, sectionIndex = 1, value = 352uL, size = 352uL),
                symbol(index = 2, name = "memcpy", tableSectionIndex = 9, sectionIndex = 0, value = 0uL, size = 0uL),
            ),
            sections = listOf(
                section(index = 9, name = ".symtab"),
                section(index = 1, name = ".text", address = 0uL, fileOffset = 64uL, size = 10656uL),
            ),
            programHeaders = emptyList(), // ET_REL has none
        )

        assertEquals(3, functions.size)

        val first = functions[0]
        assertEquals("init_table", first.name)
        assertFalse(first.imported)
        assertEquals(64L, first.fileOffset) // sh_offset(64) + st_value(0)
        assertEquals(344uL, first.size)

        val second = functions[1]
        assertEquals("uw_install", second.name)
        assertEquals(416L, second.fileOffset) // sh_offset(64) + st_value(352)

        val imported = functions[2]
        assertEquals("memcpy", imported.name)
        assertTrue(imported.imported)
        assertNull(imported.fileOffset)
    }

    @Test
    fun `discovers defined and imported functions from COFF symbols`() {
        // Layout mirrors a real MSVC i386 `.obj` (Util.obj): a single code section `.text`
        // (1-based COFF section number 5 => 0-based index 4) at file offset 2152, size 1093,
        // with two defined functions at section-relative offsets and one undefined import.
        val text = coffSection(index = 4, name = ".text", rawDataPointer = 2152u, rawDataSize = 1093u)
        val functions = NativeFunctionDiscovery.fromCoff(
            symbols = listOf(
                coffSym(index = 31, name = "_CU_translate_special_characters", value = 0u, sectionNumber = 5),
                coffSym(index = 32, name = "_memcpy", value = 0u, sectionNumber = 0), // undefined import
                coffSym(index = 35, name = "_get_index", value = 320u, sectionNumber = 5, storageClass = 3),
                coffSym(index = 40, name = "_data_label", value = 8u, sectionNumber = 3, type = 0x0000), // not ISFCN
            ),
            sections = listOf(text),
        )

        // The non-function (`_data_label`, type without DT_FUNCTION) is excluded.
        assertEquals(3, functions.size)

        val first = functions[0]
        assertEquals("_CU_translate_special_characters", first.name)
        assertFalse(first.imported)
        assertEquals(2152L, first.fileOffset)           // rawDataPointer + value(0)
        assertEquals(320uL, first.size)                  // gap to next defined fn (320 - 0)
        assertEquals(".text", first.sectionName)
        assertEquals(NativeFunctionRangeConfidence.NextSymbolInSection, first.rangeConfidence)

        val imported = functions[1]
        assertEquals("_memcpy", imported.name)
        assertTrue(imported.imported)
        assertNull(imported.fileOffset)
        assertNull(imported.size)

        val getIndex = functions[2]
        assertEquals("_get_index", getIndex.name)
        assertFalse(getIndex.imported)
        assertEquals(2472L, getIndex.fileOffset)         // 2152 + 320
        assertEquals(773uL, getIndex.size)               // section end (1093) - 320
    }

    @Test
    fun `strips bit 0 from Thumb symbol addresses on EM_ARM`() {
        // Symbols with odd st_value are Thumb-mode functions on EM_ARM (ARM IHI0044).
        // The virtualAddress stored in NativeFunctionRecord must have bit 0 cleared so
        // that file-offset mapping and size gap arithmetic are correct.
        val functions = NativeFunctionDiscovery.fromElf(
            symbols = listOf(
                symbol(index = 0, name = "thumb_fn", sectionIndex = 3, value = 0x401001uL, size = 0x10uL),
                symbol(index = 1, name = "arm_fn",   sectionIndex = 3, value = 0x401020uL, size = 0x08uL),
            ),
            sections = listOf(
                section(index = 1, name = ".symtab"),
                section(index = 3, name = ".text", address = 0x401000uL, fileOffset = 0x1000uL, size = 0x80uL),
            ),
            programHeaders = listOf(loadSegment()),
            machine = 0x28,
        )

        assertEquals(2, functions.size)

        val thumb = functions[0]
        assertEquals("thumb_fn", thumb.name)
        assertTrue(thumb.isThumb)
        assertEquals(0x401000uL, thumb.virtualAddress)   // bit 0 stripped
        assertEquals(0x1000L, thumb.fileOffset)          // maps to correct file offset

        val arm = functions[1]
        assertEquals("arm_fn", arm.name)
        assertFalse(arm.isThumb)
        assertEquals(0x401020uL, arm.virtualAddress)     // even address unchanged
    }

    @Test
    fun `does not set isThumb for EM_ARM symbols without bit 0`() {
        val functions = NativeFunctionDiscovery.fromElf(
            symbols = listOf(
                symbol(index = 0, name = "arm_fn", sectionIndex = 3, value = 0x401000uL, size = 0x20uL),
            ),
            sections = listOf(
                section(index = 1, name = ".symtab"),
                section(index = 3, name = ".text", address = 0x401000uL, fileOffset = 0x1000uL, size = 0x80uL),
            ),
            programHeaders = listOf(loadSegment()),
            machine = 0x28,
        )

        assertFalse(functions[0].isThumb)
        assertEquals(0x401000uL, functions[0].virtualAddress)
    }

    @Test
    fun `does not set isThumb on non-ARM architectures with odd symbol values`() {
        // Odd st_value on x86-64 is unusual but must not trigger Thumb detection.
        val functions = NativeFunctionDiscovery.fromElf(
            symbols = listOf(
                symbol(index = 0, name = "fn", sectionIndex = 3, value = 0x401001uL, size = 0x10uL),
            ),
            sections = listOf(
                section(index = 1, name = ".symtab"),
                section(index = 3, name = ".text", address = 0x401000uL, fileOffset = 0x1000uL, size = 0x80uL),
            ),
            programHeaders = listOf(loadSegment()),
            machine = 0x3E,  // EM_X86_64
        )

        assertFalse(functions[0].isThumb)
        assertEquals(0x401001uL, functions[0].virtualAddress)  // not stripped
    }

    @Test
    fun `computes correct size gap between Thumb symbols with odd addresses`() {
        // Two consecutive Thumb symbols: gap must be computed using masked addresses.
        // thumb_a at 0x1001 (masked 0x1000), thumb_b at 0x1021 (masked 0x1020).
        // Expected size of thumb_a = 0x1020 - 0x1000 = 0x20.
        val functions = NativeFunctionDiscovery.fromElf(
            symbols = listOf(
                symbol(index = 0, name = "thumb_a", sectionIndex = 3, value = 0x401001uL, size = 0uL),
                symbol(index = 1, name = "thumb_b", sectionIndex = 3, value = 0x401021uL, size = 0uL),
            ),
            sections = listOf(
                section(index = 1, name = ".symtab"),
                section(index = 3, name = ".text", address = 0x401000uL, fileOffset = 0x1000uL, size = 0x80uL),
            ),
            programHeaders = listOf(loadSegment()),
            machine = 0x28,
        )

        assertEquals(0x20uL, functions[0].size)
        assertEquals(0x401000uL, functions[0].virtualAddress)
        assertEquals(0x401020uL, functions[1].virtualAddress)
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

private fun coffSection(
    index: Int,
    name: String,
    rawDataPointer: UInt,
    rawDataSize: UInt,
    virtualAddress: UInt = 0u,
    characteristics: UInt = 0x60500020u, // IMAGE_SCN_CNT_CODE | MEM_EXECUTE | MEM_READ
): PeSection = PeSection(
    index = index,
    name = name,
    virtualSize = rawDataSize,
    virtualAddress = virtualAddress,
    rawDataSize = rawDataSize,
    rawDataPointer = rawDataPointer,
    relocationPointer = 0u,
    lineNumberPointer = 0u,
    relocationCount = 0,
    lineNumberCount = 0,
    characteristics = characteristics,
    headerRange = NativeByteRange(20 + index * 40, 40),
    contentRange = NativeByteRange(rawDataPointer.toInt(), rawDataSize.toInt()),
)

private fun coffSym(
    index: Int,
    name: String,
    value: UInt,
    sectionNumber: Int,
    type: Int = 0x0020, // DT_FUNCTION derived type (ISFCN)
    storageClass: Int = 2, // IMAGE_SYM_CLASS_EXTERNAL
): PeCoffSymbol = PeCoffSymbol(
    index = index,
    name = name,
    value = value,
    sectionNumber = sectionNumber,
    type = type,
    storageClass = storageClass,
    auxiliaryCount = 0,
    recordRange = NativeByteRange(index * 18, 18),
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
