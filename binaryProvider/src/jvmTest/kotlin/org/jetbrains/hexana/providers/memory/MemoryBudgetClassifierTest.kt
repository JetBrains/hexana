package org.jetbrains.hexana.providers.memory

// Pure synthetic classifier tests: no ELF bytes, no fixture files.
// Each test constructs ElfHeader/ElfSectionHeader/ElfProgramHeader directly to exercise
// a single classification path in computeMemoryBudget/isMemoryBudgetEligible.
//
// Run with: ./gradlew :binaryProvider:jvmTest --tests "*MemoryBudgetClassifier*"

import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.elf.ElfHeader
import org.jetbrains.hexana.providers.elf.ElfHeaderFieldRanges
import org.jetbrains.hexana.providers.elf.ElfProgramHeader
import org.jetbrains.hexana.providers.elf.ElfProgramHeaderType
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.elf.ElfSectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryBudgetClassifierTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private val zeroRange = NativeByteRange(0, 0)

    private fun makeHeader(fileType: Int = 2 /* ET_EXEC */): ElfHeader {
        val fr = ElfHeaderFieldRanges(
            ident = zeroRange, osAbi = zeroRange, abiVersion = zeroRange,
            fileType = zeroRange, machine = zeroRange, version = zeroRange,
            entryPoint = zeroRange, programHeaderOffset = zeroRange,
            sectionHeaderOffset = zeroRange, flags = zeroRange,
            headerSize = zeroRange, programHeaderEntrySize = zeroRange,
            programHeaderCount = zeroRange, sectionHeaderEntrySize = zeroRange,
            sectionHeaderCount = zeroRange, sectionNameStringTableIndex = zeroRange,
        )
        return ElfHeader(
            wordSize = NativeBinaryWordSize.Bits32,
            endian = NativeBinaryEndian.Little,
            osAbi = 0, abiVersion = 0,
            fileType = fileType,
            machine = 40, // ARM
            version = 1u,
            entryPoint = 0uL,
            programHeaderOffset = 52uL, sectionHeaderOffset = 0uL,
            flags = 0u,
            headerSize = 52, programHeaderEntrySize = 32, programHeaderCount = 0,
            sectionHeaderEntrySize = 40, sectionHeaderCount = 0,
            sectionNameStringTableIndex = 0,
            identRange = zeroRange, headerRange = zeroRange,
            fieldRanges = fr,
        )
    }

    private fun makeSection(
        name: String,
        type: UInt = ElfSectionType.ProgBits,
        flags: ULong = 0x2uL, // SHF_ALLOC
        address: ULong = 0uL,
        size: ULong = 0x100uL,
        index: Int = 1,
    ): ElfSectionHeader = ElfSectionHeader(
        index = index, nameOffset = 0u, name = name,
        type = type, flags = flags, address = address,
        fileOffset = 0uL, size = size, link = 0u, info = 0u,
        addressAlignment = 4uL, entrySize = 0uL,
        headerRange = zeroRange, contentRange = zeroRange,
    )

    private fun makeLoadSegment(
        vaddr: ULong,
        paddr: ULong,
        fileSize: ULong = 0x100uL,
        memSize: ULong = 0x100uL,
        index: Int = 0,
    ): ElfProgramHeader = ElfProgramHeader(
        index = index,
        type = ElfProgramHeaderType.Load,
        flags = 6u,
        fileOffset = 0uL,
        virtualAddress = vaddr,
        physicalAddress = paddr,
        fileSize = fileSize,
        memorySize = memSize,
        alignment = 4uL,
        headerRange = zeroRange,
        fileRange = zeroRange,
    )

    private val flashRegion = MemoryRegion("FLASH", 0x0800_0000uL, 0x0020_0000uL, MemoryRegionKind.FLASH)
    private val ramRegion   = MemoryRegion("RAM",   0x2000_0000uL, 0x0004_0000uL, MemoryRegionKind.RAM)

    // ---------------------------------------------------------------------------
    // (a) .data dual-count: PROGBITS WA, VMA in RAM, LMA in FLASH
    // ---------------------------------------------------------------------------

    @Test
    fun dataSectionDualCount() {
        // .data: VMA=0x20000000 (RAM), LMA via PT_LOAD p_paddr=0x08004000 (FLASH)
        val dataSection = makeSection(
            name = "datas",
            type = ElfSectionType.ProgBits,
            flags = 0x3uL, // SHF_ALLOC | SHF_WRITE
            address = 0x2000_0000uL,
            size = 0x200uL,
        )
        val loadSeg = makeLoadSegment(
            vaddr = 0x2000_0000uL,
            paddr = 0x0800_4000uL,
            fileSize = 0x200uL,
            memSize = 0x200uL,
        )
        val budget = computeMemoryBudget(
            header = makeHeader(),
            sections = listOf(dataSection),
            programHeaders = listOf(loadSeg),
            manualRegions = listOf(flashRegion, ramRegion),
        )

        // Appears in both ROM and RAM usages.
        assertEquals(1, budget.romUsages.size, "expected one ROM region")
        assertEquals(1, budget.ramUsages.size, "expected one RAM region")
        assertEquals(0x200uL, budget.romUsages[0].usedBytes, "ROM usedBytes")
        assertEquals(0x200uL, budget.ramUsages[0].usedBytes, "RAM usedBytes")

        // The section carries BOTH_ROM_AND_RAM tag on both sides.
        val romSec = budget.romUsages[0].sections.single()
        val ramSec = budget.ramUsages[0].sections.single()
        assertTrue(SectionTag.BOTH_ROM_AND_RAM in romSec.tags, "ROM section must have BOTH_ROM_AND_RAM tag")
        assertTrue(SectionTag.BOTH_ROM_AND_RAM in ramSec.tags, "RAM section must have BOTH_ROM_AND_RAM tag")

        // Not in unmapped.
        assertTrue(budget.unmapped.isEmpty(), "unmapped must be empty")
    }

    // ---------------------------------------------------------------------------
    // (b) NOBITS .bss: never in ROM even when VMA is in range
    // ---------------------------------------------------------------------------

    @Test
    fun bssSectionRamOnly() {
        val bssSection = makeSection(
            name = "bss",
            type = ElfSectionType.NoBits,
            flags = 0x3uL,
            address = 0x2000_0200uL,
            size = 0x800uL,
        )
        val budget = computeMemoryBudget(
            header = makeHeader(),
            sections = listOf(bssSection),
            programHeaders = emptyList(),
            manualRegions = listOf(flashRegion, ramRegion),
        )
        // The model always emits one RegionUsage per declared region (usedBytes may be 0).
        // The FLASH region has no sections assigned -> usedBytes must be 0.
        val romFlash = budget.romUsages.firstOrNull { it.region.name == "FLASH" }
        if (romFlash != null) {
            assertEquals(0uL, romFlash.usedBytes, "FLASH region must have zero usedBytes for bss")
            assertTrue(romFlash.sections.isEmpty(), "FLASH region must have no sections for bss")
        }
        // RAM region must have the bss section.
        val ramEntry = budget.ramUsages.firstOrNull { it.region.name == "RAM" }
        assertNotNull(ramEntry, "RAM region must be present")
        assertEquals(0x800uL, ramEntry.usedBytes)
        assertEquals(1, ramEntry.sections.size, "exactly one section in RAM")
        val sec = ramEntry.sections.single()
        assertFalse(sec.occupiesLoadImage, "bss must not occupy load image")
        assertTrue(SectionTag.NOINIT !in sec.tags, "bss does not get NOINIT tag")
    }

    // ---------------------------------------------------------------------------
    // (c) Section named "noinit" receives NOINIT tag, lands only in RAM
    // ---------------------------------------------------------------------------

    @Test
    fun noinit() {
        val noinit = makeSection(
            name = "noinit",
            type = ElfSectionType.NoBits,
            flags = 0x3uL,
            address = 0x2000_1000uL,
            size = 0x400uL,
        )
        val budget = computeMemoryBudget(
            header = makeHeader(),
            sections = listOf(noinit),
            programHeaders = emptyList(),
            manualRegions = listOf(ramRegion),
        )
        assertEquals(1, budget.ramUsages.size, "noinit must appear in RAM")
        assertEquals(0, budget.romUsages.size, "noinit must not appear in ROM")
        val sec = budget.ramUsages[0].sections.single()
        assertTrue(SectionTag.NOINIT in sec.tags, "noinit must carry NOINIT tag")

        // .noinit (with dot) also gets the tag.
        val noinit2 = makeSection(
            name = ".noinit",
            type = ElfSectionType.NoBits,
            flags = 0x3uL,
            address = 0x2000_2000uL,
            size = 0x100uL,
            index = 2,
        )
        val budget2 = computeMemoryBudget(
            header = makeHeader(),
            sections = listOf(noinit2),
            programHeaders = emptyList(),
            manualRegions = listOf(ramRegion),
        )
        assertTrue(SectionTag.NOINIT in budget2.ramUsages[0].sections.single().tags)
    }

    // ---------------------------------------------------------------------------
    // (d) XIP LMA: lma = p_paddr + (sh_addr - p_vaddr)
    // ---------------------------------------------------------------------------

    @Test
    fun xipLmaComputation() {
        // Segment: VMA=0x20001000 PA=0x08005000 size=0x400
        // Section: sh_addr=0x20001200 (within segment, offset=0x200)
        // Expected LMA = 0x08005000 + (0x20001200 - 0x20001000) = 0x08005200
        val seg = makeLoadSegment(
            vaddr = 0x2000_1000uL,
            paddr = 0x0800_5000uL,
            fileSize = 0x400uL,
            memSize = 0x400uL,
        )
        val sec = makeSection(
            name = "mydata",
            type = ElfSectionType.ProgBits,
            flags = 0x3uL,
            address = 0x2000_1200uL,
            size = 0x80uL,
        )
        val budget = computeMemoryBudget(
            header = makeHeader(),
            sections = listOf(sec),
            programHeaders = listOf(seg),
            manualRegions = listOf(flashRegion, ramRegion),
        )
        val romSec = budget.romUsages.single().sections.single()
        assertEquals(0x0800_5200uL, romSec.lma,
            "LMA must equal p_paddr + (sh_addr - p_vaddr) = 0x08005200")
        assertEquals(0x2000_1200uL, romSec.vma, "VMA must equal sh_addr")
    }

    // ---------------------------------------------------------------------------
    // (e) No program headers: lma == vma
    // ---------------------------------------------------------------------------

    @Test
    fun noSegmentsLmaEqualsVma() {
        val sec = makeSection(
            name = "text",
            type = ElfSectionType.ProgBits,
            flags = 0x6uL, // AX
            address = 0x0800_0100uL,
            size = 0x500uL,
        )
        val budget = computeMemoryBudget(
            header = makeHeader(),
            sections = listOf(sec),
            programHeaders = emptyList(),
            manualRegions = listOf(flashRegion),
        )
        val romSec = budget.romUsages.single().sections.single()
        assertEquals(romSec.vma, romSec.lma, "Without program headers, lma must equal vma")
    }

    // ---------------------------------------------------------------------------
    // (f) Section outside all regions goes to unmapped with a warning
    // ---------------------------------------------------------------------------

    @Test
    fun unmappedSection() {
        // Section VMA=0xE000_0000 is outside FLASH (0x0800_0000) and RAM (0x2000_0000).
        val sec = makeSection(
            name = "peripheral",
            type = ElfSectionType.ProgBits,
            flags = 0x2uL,
            address = 0xE000_0000uL,
            size = 0x100uL,
        )
        val budget = computeMemoryBudget(
            header = makeHeader(),
            sections = listOf(sec),
            programHeaders = emptyList(),
            manualRegions = listOf(flashRegion, ramRegion),
        )
        assertTrue(budget.romUsages.all { it.usedBytes == 0uL }, "ROM usages must be empty")
        assertTrue(budget.ramUsages.all { it.usedBytes == 0uL }, "RAM usages must be empty")
        assertEquals(1, budget.unmapped.size, "section must be in unmapped")
        assertEquals("peripheral", budget.unmapped[0].name)
        assertTrue(
            budget.warnings.any { "outside" in it.lowercase() || "unmapped" in it.lowercase() ||
                "section" in it.lowercase() },
            "warnings must mention the unmapped section: ${budget.warnings}",
        )
    }

    // ---------------------------------------------------------------------------
    // (g1) Percent: 256 KB region, 128 KB used => 50.0 %
    // ---------------------------------------------------------------------------

    @Test
    fun percentCalculation() {
        val region256k = MemoryRegion("FLASH", 0x0800_0000uL, 256uL * 1024uL, MemoryRegionKind.FLASH)
        val sec = makeSection(
            name = "text",
            type = ElfSectionType.ProgBits,
            flags = 0x6uL,
            address = 0x0800_0000uL,
            size = 128uL * 1024uL,
        )
        val budget = computeMemoryBudget(
            header = makeHeader(),
            sections = listOf(sec),
            programHeaders = emptyList(),
            manualRegions = listOf(region256k),
        )
        val pct = budget.romUsages.single().percent
        assertNotNull(pct, "percent must not be null for a region with known capacity")
        assertEquals(50.0, pct, 0.01, "128 KB / 256 KB must be 50.0 %")
    }

    // ---------------------------------------------------------------------------
    // (g2) No Int overflow with a >2 GB region
    // ---------------------------------------------------------------------------

    @Test
    fun noIntOverflowLargeRegion() {
        // Region: 3 GB (0xC000_0000). Usage: 2 GB (0x8000_0000). Expected ~66.67 %.
        val bigLen = 0xC000_0000uL
        val bigUsed = 0x8000_0000uL
        val bigRegion = MemoryRegion("DDR", 0x4000_0000uL, bigLen, MemoryRegionKind.RAM)
        val sec = makeSection(
            name = "heap",
            type = ElfSectionType.NoBits,
            flags = 0x3uL,
            address = 0x4000_0000uL,
            size = bigUsed,
        )
        val budget = computeMemoryBudget(
            header = makeHeader(),
            sections = listOf(sec),
            programHeaders = emptyList(),
            manualRegions = listOf(bigRegion),
        )
        val pct = budget.ramUsages.single().percent
        assertNotNull(pct)
        assertEquals(66.67, pct, 0.01, "2 GB / 3 GB must be ~66.67 %")
        assertEquals(bigUsed, budget.ramUsages.single().usedBytes)
    }

    // ---------------------------------------------------------------------------
    // (h) isMemoryBudgetEligible
    // ---------------------------------------------------------------------------

    @Test
    fun eligibilityEtRel() {
        val hdr = makeHeader(fileType = 1 /* ET_REL */)
        val sec = makeSection("text", flags = 0x6uL)
        assertFalse(isMemoryBudgetEligible(hdr, listOf(sec)), "ET_REL must not be eligible")
    }

    @Test
    fun eligibilityEtExecWithAlloc() {
        val hdr = makeHeader(fileType = 2 /* ET_EXEC */)
        val sec = makeSection("text", flags = 0x6uL)
        assertTrue(isMemoryBudgetEligible(hdr, listOf(sec)), "ET_EXEC with ALLOC section must be eligible")
    }

    @Test
    fun eligibilityNullArguments() {
        assertFalse(isMemoryBudgetEligible(null, null), "null header must not be eligible")
        assertFalse(isMemoryBudgetEligible(makeHeader(), null), "null sections must not be eligible")
        assertFalse(isMemoryBudgetEligible(null, emptyList()), "null header must not be eligible")
    }

    @Test
    fun eligibilityEtDynIsEligible() {
        val hdr = makeHeader(fileType = 3 /* ET_DYN */)
        val sec = makeSection("text", flags = 0x6uL)
        assertTrue(isMemoryBudgetEligible(hdr, listOf(sec)), "ET_DYN must be eligible")
    }

    @Test
    fun eligibilityNoAllocSections() {
        val hdr = makeHeader(fileType = 2)
        // Section with no SHF_ALLOC flag.
        val sec = makeSection("debug", flags = 0x0uL)
        assertFalse(isMemoryBudgetEligible(hdr, listOf(sec)), "No ALLOC sections => not eligible")
    }

    // ---------------------------------------------------------------------------
    // (i) percent is null when region has no capacity (LOAD_SEGMENTS path)
    // ---------------------------------------------------------------------------

    @Test
    fun percentNullWhenCapacityUnknown() {
        // deriveRegionsFromLoadSegments produces regions with length=null.
        val seg = makeLoadSegment(vaddr = 0x0800_0000uL, paddr = 0x0800_0000uL)
        val sec = makeSection("text", flags = 0x6uL, address = 0x0800_0000uL, size = 0x100uL)
        val budget = computeMemoryBudget(
            header = makeHeader(),
            sections = listOf(sec),
            programHeaders = listOf(seg),
            // No manualRegions, no map, no dts -> falls back to LOAD_SEGMENTS
        )
        assertEquals(CapacitySource.LOAD_SEGMENTS, budget.capacitySource)
        // Sections land in OTHER regions (not ROM/RAM), so romUsages/ramUsages may be empty.
        // In either case, no region should have a non-null percent.
        val allUsages = budget.romUsages + budget.ramUsages
        assertTrue(allUsages.all { it.percent == null }, "derived-segment regions must have null percent")
    }
}
