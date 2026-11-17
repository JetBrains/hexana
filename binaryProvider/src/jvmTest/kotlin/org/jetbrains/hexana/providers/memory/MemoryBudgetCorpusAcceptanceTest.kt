package org.jetbrains.hexana.providers.memory

// End-to-end corpus acceptance tests for computeMemoryBudget.
// Each positive sample is parsed via ElfBinaryProvider and the budget is computed with
// the real map text.  Assertions pin exact computed values; any deviation requires a
// documented explanation (not a tolerance range).
//
// Ground truth for ROM/RAM totals: rom.json / ram.json total_size fields in the corpus.
// Deviations from those totals are documented inline where they occur.
//
// Run with: ./gradlew :binaryProvider:jvmTest --tests "*MemoryBudgetCorpusAcceptance*"

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryFormatDetector
import org.jetbrains.hexana.providers.ElfBinaryProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryBudgetCorpusAcceptanceTest {

    private val repoRoot: Path by lazy {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent ?: error("Hexana repo root not found above ${Path.of("").toAbsolutePath()}")
        }
        dir
    }

    private fun budgetFile(relativePath: String): String {
        System.getenv("HEXANA_ZEPHYR_CORPUS")?.trim()?.ifBlank { null }?.let {
            return Path.of(it).resolve("budget-samples/$relativePath").toAbsolutePath().normalize().toString()
        }
        val bundled = repoRoot.resolve(
            "ui-driver-tests/testData/fixtures/zephyr-corpus/budget-samples/$relativePath.gz"
        )
        if (Files.exists(bundled)) {
            val target = repoRoot.resolve(
                "binaryProvider/build/fixtures/zephyr-corpus/budget-samples/$relativePath"
            )
            if (!Files.exists(target) || Files.size(target) == 0L) {
                Files.createDirectories(target.parent)
                GZIPInputStream(Files.newInputStream(bundled)).use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return target.toString()
        }
        return repoRoot
            .resolve("ui-driver-tests/testData/fixtures/zephyr-corpus/budget-samples/$relativePath")
            .toAbsolutePath().normalize().toString()
    }

    private fun readText(relativePath: String): String =
        File(budgetFile(relativePath)).also {
            assertTrue(it.exists(), "Fixture not found: $relativePath")
        }.readText()

    private fun elfProvider(relativePath: String): ElfBinaryProvider {
        val path = budgetFile(relativePath)
        val file = File(path)
        assertTrue(file.exists(), "ELF absent: $path")
        val bytes = file.readBytes().toCommonByteBuffer()
        val fmt = NativeBinaryFormatDetector.detect(bytes) as? NativeBinaryFormat.Elf
            ?: error("Not an ELF: $path")
        return ElfBinaryProvider(bytes, fmt)
    }

    // ---------------------------------------------------------------------------
    // hello_world-nrf52840dk-Os
    //
    // Model vs rom.json / ram.json:
    //   ROM model: 21380  = romReport.total 21380 (exact match)
    //   RAM model: 4562  != ramReport.total 4578 (delta = -16)
    //     Reason: Zephyr's footprint tool adds 16 bytes of alignment padding between
    //     sections (gap from bss end 0x200003D2 to noinit start 0x20000400 is partly
    //     counted).  The ELF model sums sh_size values only, no inter-section gaps.
    //     Pinned as 4562uL with this comment; do not widen to a range.
    //
    //   arm-zephyr-eabi-size: text=21168 data=212 bss=4366
    //   Model ROM = 21380 = all FLASH-LMA load-image sections = 21168+212 (arm-size) ✓
    //   Model RAM = 4562 = datas(188)+device_states(12)+bss(778)+noinit(3584); arm-size
    //     bss=4366 counts tbss(4)+bss(778)+noinit(3584)=4366, plus arm-size data=212
    //     includes WA sections at FLASH VMA (clock_control+last_section, not in RAM region).
    // ---------------------------------------------------------------------------

    @Test
    fun nrf52840dkOsHelloWorld() {
        val elf = elfProvider("hello_world-nrf52840dk-Os/build/zephyr/zephyr.elf")
        val mapText = readText("hello_world-nrf52840dk-Os/build/zephyr/zephyr.map")

        val budget = assertNotNull(elf.computeMemoryBudget(mapText = mapText))

        // (a) ROM total: 21380 bytes = romReport.total (exact match).
        val romUsed = budget.romUsages.fold(0uL) { acc, u -> acc + u.usedBytes }
        assertEquals(21380uL, romUsed,
            "ROM total must be 21380 (romReport.total); diff=${romUsed.toLong() - 21380}")

        // (a) RAM total: 4562 bytes (not 4578 from ramReport; delta=16 is inter-section padding
        //     that the ELF model does not count — see comment above).
        val ramUsed = budget.ramUsages.fold(0uL) { acc, u -> acc + u.usedBytes }
        assertEquals(4562uL, ramUsed,
            "RAM total must be 4562 (section headers only, no padding); ramReport=4578 delta=16")

        // (b) The 'datas' section appears in both ROM and RAM with BOTH_ROM_AND_RAM tag.
        val romDatas = budget.romUsages.flatMap { it.sections }.firstOrNull { it.name == "datas" }
        val ramDatas = budget.ramUsages.flatMap { it.sections }.firstOrNull { it.name == "datas" }
        assertNotNull(romDatas, "datas must appear in ROM usages (loaded from FLASH)")
        assertNotNull(ramDatas, "datas must appear in RAM usages (runs at 0x20000000)")
        assertTrue(SectionTag.BOTH_ROM_AND_RAM in romDatas.tags, "datas in ROM must have BOTH_ROM_AND_RAM")
        assertTrue(SectionTag.BOTH_ROM_AND_RAM in ramDatas.tags, "datas in RAM must have BOTH_ROM_AND_RAM")

        // (c) 'bss' appears only in RAM, never ROM.
        val romBss = budget.romUsages.flatMap { it.sections }.firstOrNull { it.name == "bss" }
        val ramBss = budget.ramUsages.flatMap { it.sections }.firstOrNull { it.name == "bss" }
        assertNull(romBss, "bss (NOBITS) must NOT appear in ROM")
        assertNotNull(ramBss, "bss must appear in RAM")

        // (d) Percent is in (0, 100) for regions that actually have sections.
        // Zero-usage regions (IDT_LIST, DTCM) have percent=0.0 which is correct; skip them.
        for (usage in budget.romUsages + budget.ramUsages) {
            if (usage.usedBytes == 0uL) continue
            val pct = usage.percent ?: continue
            assertTrue(pct > 0.0 && pct < 100.0,
                "percent must be in (0,100) for region ${usage.region.name}, got $pct")
        }

        assertEquals(CapacitySource.LINKER_MAP, budget.capacitySource)
    }

    // ---------------------------------------------------------------------------
    // peripheral_hr-nrf52840dk-Os
    //
    // Model vs rom.json / ram.json:
    //   ROM model: 162888 != romReport.total 162880 (delta = +8)
    //     Reason: .ARM.exidx (ARM unwind table, size=8) has type SHT_ARM_EXIDX (0x70000001),
    //     is ALLOC, is in the LOAD segment at FLASH range, and is counted by the ELF model.
    //     Zephyr's footprint tool excludes .ARM.exidx.  Pinned as 162888uL.
    //
    //   RAM model: 28453 != ramReport.total 28473 (delta = -20)
    //     Reason: inter-section alignment padding (gap of 39 bytes between bss end at
    //     0x20003919 and noinit start at 0x20003940) is partially counted by Zephyr footprint
    //     but not by the ELF section-header model.  Pinned as 28453uL.
    //
    //   arm-zephyr-eabi-size: text=161076 data=1812 bss=26661
    //   arm-size text+data = 162888 = ROM model ✓ (arm-size counts .ARM.exidx in text too)
    // ---------------------------------------------------------------------------

    @Test
    fun nrf52840dkOsPeripheralHr() {
        val elf = elfProvider("peripheral_hr-nrf52840dk-Os/build/zephyr/zephyr.elf")
        val mapText = readText("peripheral_hr-nrf52840dk-Os/build/zephyr/zephyr.map")

        val budget = assertNotNull(elf.computeMemoryBudget(mapText = mapText))

        // ROM total: 162888 (includes .ARM.exidx; romReport.total=162880 excludes it, delta=+8).
        val romUsed = budget.romUsages.fold(0uL) { acc, u -> acc + u.usedBytes }
        assertEquals(162888uL, romUsed,
            "ROM total must be 162888 (includes .ARM.exidx unwind table; romReport=162880 delta=+8)")

        // RAM total: 28453 (section headers only; ramReport=28473 includes padding, delta=-20).
        val ramUsed = budget.ramUsages.fold(0uL) { acc, u -> acc + u.usedBytes }
        assertEquals(28453uL, ramUsed,
            "RAM total must be 28453; ramReport=28473 includes alignment padding, delta=20")

        // (b) 'datas' dual-count.
        val romDatas = budget.romUsages.flatMap { it.sections }.firstOrNull { it.name == "datas" }
        val ramDatas = budget.ramUsages.flatMap { it.sections }.firstOrNull { it.name == "datas" }
        assertNotNull(romDatas, "datas must appear in ROM")
        assertNotNull(ramDatas, "datas must appear in RAM")
        assertTrue(SectionTag.BOTH_ROM_AND_RAM in romDatas.tags)
        assertTrue(SectionTag.BOTH_ROM_AND_RAM in ramDatas.tags)

        // (c) 'bss' only in RAM.
        assertNull(budget.romUsages.flatMap { it.sections }.firstOrNull { it.name == "bss" })
        assertNotNull(budget.ramUsages.flatMap { it.sections }.firstOrNull { it.name == "bss" })

        // (d) Percent sane. Skip zero-usage regions (IDT_LIST, DTCM have no sections in them).
        for (usage in budget.romUsages + budget.ramUsages) {
            if (usage.usedBytes == 0uL) continue
            val pct = usage.percent ?: continue
            assertTrue(pct > 0.0 && pct < 100.0, "percent out of (0,100): ${usage.region.name}=$pct")
        }
    }

    // ---------------------------------------------------------------------------
    // hello_world-nucleo_f429zi-Os
    //
    // Model vs rom.json / ram.json:
    //   ROM model: 17100 != romReport.total 17092 (delta = +8)
    //     Reason: .ARM.exidx (size=8) is counted by the model but excluded by Zephyr
    //     footprint.  Pinned as 17100uL.
    //
    //   RAM model: 4416 != ramReport.total 4424 (delta = -8)
    //     Reason: alignment padding between sections not in section headers.  Pinned as 4416uL.
    //
    //   arm-zephyr-eabi-size: text=16976 data=124 bss=4300
    //   arm-size text+data=17100 = ROM model ✓.
    // ---------------------------------------------------------------------------

    @Test
    fun nucleoF429ziOsHelloWorld() {
        val elf = elfProvider("hello_world-nucleo_f429zi-Os/build/zephyr/zephyr.elf")
        val mapText = readText("hello_world-nucleo_f429zi-Os/build/zephyr/zephyr.map")

        val budget = assertNotNull(elf.computeMemoryBudget(mapText = mapText))

        // ROM total: 17100 (includes .ARM.exidx; romReport.total=17092 excludes it, delta=+8).
        val romUsed = budget.romUsages.fold(0uL) { acc, u -> acc + u.usedBytes }
        assertEquals(17100uL, romUsed,
            "ROM total must be 17100 (includes .ARM.exidx; romReport=17092 delta=+8)")

        // RAM total: 4416 (section headers; ramReport=4424 includes padding, delta=-8).
        val ramUsed = budget.ramUsages.fold(0uL) { acc, u -> acc + u.usedBytes }
        assertEquals(4416uL, ramUsed,
            "RAM total must be 4416; ramReport=4424 includes alignment padding, delta=8")

        // (b) 'datas' dual-count.
        val romDatas = budget.romUsages.flatMap { it.sections }.firstOrNull { it.name == "datas" }
        val ramDatas = budget.ramUsages.flatMap { it.sections }.firstOrNull { it.name == "datas" }
        assertNotNull(romDatas, "datas must appear in ROM")
        assertNotNull(ramDatas, "datas must appear in RAM")
        assertTrue(SectionTag.BOTH_ROM_AND_RAM in romDatas.tags)
        assertTrue(SectionTag.BOTH_ROM_AND_RAM in ramDatas.tags)

        // (c) 'bss' only in RAM.
        assertNull(budget.romUsages.flatMap { it.sections }.firstOrNull { it.name == "bss" })
        assertNotNull(budget.ramUsages.flatMap { it.sections }.firstOrNull { it.name == "bss" })

        // (d) Percent sane. Skip zero-usage regions (IDT_LIST, DTCM have no sections in them).
        for (usage in budget.romUsages + budget.ramUsages) {
            if (usage.usedBytes == 0uL) continue
            val pct = usage.percent ?: continue
            assertTrue(pct > 0.0 && pct < 100.0, "percent out of (0,100): ${usage.region.name}=$pct")
        }
    }

    // ---------------------------------------------------------------------------
    // LLD baremetal: no map (empty regions), manualRegions from linker.ld values.
    //
    // linker.ld: FLASH (rx) ORIGIN=0x08000000 LENGTH=256K
    //            RAM  (rwx) ORIGIN=0x20000000 LENGTH=64K
    //
    // Sections: .vectors(A,0x40) + .ARM.exidx(AL,0x18) + .text(AX,0x1c) all in FLASH;
    //           .data(WA, VMA=0x20000000, LMA=0x08000074, size=0x100) -> BOTH_ROM_AND_RAM;
    //           .bss(NOBITS, WA, 0x2000) -> RAM only.
    //
    // ROM model = 0x40+0x18+0x1c+0x100 = 372 = arm-size text(116)+data(256) ✓
    // RAM model = 0x100+0x2000 = 8448 = data(256)+bss(8192) ✓
    // ---------------------------------------------------------------------------

    @Test
    fun lldBaremetalWithManualRegions() {
        val elf = elfProvider("lld-baremetal/firmware.elf")

        val manualRegions = listOf(
            MemoryRegion("FLASH", 0x0800_0000uL, 256uL * 1024uL, MemoryRegionKind.FLASH),
            MemoryRegion("RAM",   0x2000_0000uL, 64uL  * 1024uL, MemoryRegionKind.RAM),
        )

        val budget = assertNotNull(
            elf.computeMemoryBudget(manualRegions = manualRegions),
            "LLD ELF must be eligible for memory budget"
        )

        // ROM total: 372 = text(116) + data(256) via arm-size; includes .ARM.exidx.
        val romUsed = budget.romUsages.fold(0uL) { acc, u -> acc + u.usedBytes }
        assertEquals(372uL, romUsed,
            "LLD ROM = 0x40+0x18+0x1c+0x100 = 372 (vectors+exidx+text+data load)")

        // .data LMA must equal 0x08000074 (from readelf -l: p_paddr=0x08000074, p_vaddr=0x20000000).
        val romData = budget.romUsages.flatMap { it.sections }.firstOrNull { it.name == ".data" }
        assertNotNull(romData, ".data must appear in ROM usages (LMA in FLASH)")
        assertEquals(0x0800_0074uL, romData.lma, ".data LMA = p_paddr + (sh_addr - p_vaddr) = 0x08000074")

        // .data in RAM too, with BOTH_ROM_AND_RAM tag.
        val ramData = budget.ramUsages.flatMap { it.sections }.firstOrNull { it.name == ".data" }
        assertNotNull(ramData, ".data must appear in RAM usages (VMA=0x20000000)")
        assertTrue(SectionTag.BOTH_ROM_AND_RAM in ramData.tags)

        // .bss: RAM only, size=8192.
        val romBss = budget.romUsages.flatMap { it.sections }.firstOrNull { it.name == ".bss" }
        val ramBss = budget.ramUsages.flatMap { it.sections }.firstOrNull { it.name == ".bss" }
        assertNull(romBss, ".bss must not appear in ROM")
        assertNotNull(ramBss, ".bss must appear in RAM")
        assertEquals(8192uL, ramBss.size, ".bss size = 8192 bytes")

        // RAM total: data(256) + bss(8192) = 8448.
        val ramUsed = budget.ramUsages.fold(0uL) { acc, u -> acc + u.usedBytes }
        assertEquals(8448uL, ramUsed, "LLD RAM = .data(256) + .bss(8192) = 8448")

        assertEquals(CapacitySource.MANUAL, budget.capacitySource)
    }

    // ---------------------------------------------------------------------------
    // Overlay pair: same ELF sections, different FLASH capacity from the two maps.
    // stock map  -> FLASH region length 0x100000 (1 MB)
    // overlay map -> FLASH region length 0x80000  (512 KB)
    // ---------------------------------------------------------------------------

    @Test
    fun overlayStockFlashCapacity() {
        val elf     = elfProvider("hello_world-nrf52840dk-overlay/stock/zephyr/zephyr.elf")
        val mapText = readText("hello_world-nrf52840dk-overlay/stock/zephyr/zephyr.map")

        val budget = assertNotNull(elf.computeMemoryBudget(mapText = mapText))

        val flashUsage = budget.romUsages.firstOrNull { it.region.name == "FLASH" }
        assertNotNull(flashUsage, "Must have a FLASH region usage from the stock map")
        assertEquals(0x0010_0000uL, flashUsage.region.length,
            "Stock build: FLASH capacity = 1 MB (0x100000)")
    }

    @Test
    fun overlayReducedFlashCapacity() {
        val elf     = elfProvider("hello_world-nrf52840dk-overlay/overlay/zephyr/zephyr.elf")
        val mapText = readText("hello_world-nrf52840dk-overlay/overlay/zephyr/zephyr.map")

        val budget = assertNotNull(elf.computeMemoryBudget(mapText = mapText))

        val flashUsage = budget.romUsages.firstOrNull { it.region.name == "FLASH" }
        assertNotNull(flashUsage, "Must have a FLASH region usage from the overlay map")
        assertEquals(0x0008_0000uL, flashUsage.region.length,
            "Overlay build: FLASH capacity = 512 KB (0x80000)")
    }

    // ---------------------------------------------------------------------------
    // Stale-map sample: linkerMapMatchesElf returns false for the real O2 map vs Os ELF,
    // so the caller discards it and falls back to LOAD_SEGMENTS.
    //
    // The stale-map fixture contains an Os ELF paired with an O2 map.  ELF-driven matching
    // finds >= 3 undotted section names in the map; FLASH-section VMAs disagree => false.
    // ---------------------------------------------------------------------------

    @Test
    fun staleMapExcludedFromBudget() {
        // Real stale pair: stale-map fixture holds an Os ELF with an O2 map.
        // The O2 map has FLASH-section VMAs that differ from the Os ELF (e.g. rodata at
        // 0x61d0 in the map vs 0x4bb0 in the ELF).  ELF-driven matching finds >= 3 comparable
        // sections with < 80% agreement => linkerMapMatchesElf returns false.
        val elf = elfProvider("stale-map/build/zephyr/zephyr.elf")
        val staleMapText = readText("stale-map/build/zephyr/zephyr.map")
        val sections = elf.sections ?: error("No sections in stale-map ELF")

        // Guard: the real stale pair must be detected.
        assertFalse(
            linkerMapMatchesElf(staleMapText, sections),
            "Real stale pair (O2 map vs Os ELF) must return false from linkerMapMatchesElf"
        )

        // Caller's responsibility: discard the stale map and fall back.
        val mapTextToUse = if (linkerMapMatchesElf(staleMapText, sections)) staleMapText else null
        val budget = assertNotNull(elf.computeMemoryBudget(mapText = mapTextToUse))

        assertFalse(
            budget.capacitySource == CapacitySource.LINKER_MAP,
            "Budget must NOT use LINKER_MAP source when the map is stale; " +
                "got capacitySource=${budget.capacitySource}"
        )
    }

    // ---------------------------------------------------------------------------
    // no-siblings: bare ELF, no map, no dts -> LOAD_SEGMENTS fallback.
    // Structural assertions: capacitySource=LOAD_SEGMENTS, percent=null everywhere,
    // at least some sections assigned to OTHER-kind derived regions.
    // ---------------------------------------------------------------------------

    @Test
    fun noSiblingsFallsBackToLoadSegments() {
        val elf = elfProvider("no-siblings/zephyr.elf")

        val budget = assertNotNull(elf.computeMemoryBudget(
            mapText = null, dtsText = null, fdtBytes = null, manualRegions = emptyList()
        ))

        assertEquals(CapacitySource.LOAD_SEGMENTS, budget.capacitySource,
            "No map/dts/fdt -> must fall back to LOAD_SEGMENTS")

        // All derived regions have length=null -> percent must be null.
        val allUsages = budget.romUsages + budget.ramUsages
        for (usage in allUsages) {
            assertNull(usage.percent,
                "LOAD_SEGMENTS regions have unknown capacity; percent must be null for ${usage.region.name}")
        }

        // At least one warning about unknown capacity.
        assertTrue(
            budget.warnings.any { "unknown" in it.lowercase() || "segments" in it.lowercase() || "load" in it.lowercase() },
            "Expected a warning about inferred capacity; got ${budget.warnings}"
        )

        // The hello_world ELF has real ALLOC sections; they must be assigned somewhere (not all unmapped).
        val totalAssigned = allUsages.sumOf { it.sections.size }
        assertTrue(totalAssigned > 0,
            "At least some sections must be assigned to derived regions; got $totalAssigned")
    }
}
