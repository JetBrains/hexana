package org.jetbrains.hexana.providers.memory

// Fixture-based tests for parseLinkerMapRegions and linkerMapMatchesElf.
// Fixtures live at ui-driver-tests/testData/fixtures/zephyr-corpus/budget-samples/.
// ELFs are gzip-compressed (.elf.gz); decompressed to binaryProvider/build/fixtures/ on first use.
// Set HEXANA_ZEPHYR_CORPUS to a local corpus checkout to use live files (corpus root expected to
// contain a budget-samples/ subdirectory).
//
// Run with: ./gradlew :binaryProvider:jvmTest --tests "*LinkerMapRegions*"

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryFormatDetector
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkerMapRegionsTest {

    private val repoRoot: Path by lazy {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent ?: error("Hexana repo root not found above ${Path.of("").toAbsolutePath()}")
        }
        dir
    }

    /** Resolves a budget-sample path, decompressing gzip fixtures on first use. */
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
        // Non-gzip file (map, dts, json, etc.)
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

    private fun elfSections(relativePath: String): List<ElfSectionHeader> =
        elfProvider(relativePath).sections ?: error("No sections in $relativePath")

    // ---------------------------------------------------------------------------
    // hello_world-nrf52840dk-Os: GNU ld map with FLASH + RAM + IDT_LIST
    // Expected: exactly FLASH(0x0, 1MB, FLASH) and RAM(0x20000000, 256KB, RAM)
    // IDT_LIST has attrs "xw": 'w' present -> RAM kind.
    // ---------------------------------------------------------------------------

    @Test
    fun nrf52840dkOsHelloWorldMapRegions() {
        val mapText = readText("hello_world-nrf52840dk-Os/build/zephyr/zephyr.map")
        val regions = parseLinkerMapRegions(mapText)

        // *default* must be excluded.
        assertFalse(regions.any { it.name == "*default*" }, "*default* must be excluded")

        val flash = regions.single { it.name == "FLASH" }
        assertEquals(0x0000_0000uL, flash.origin, "FLASH origin")
        assertEquals(0x0010_0000uL, flash.length, "FLASH length = 1 MB")
        assertEquals(MemoryRegionKind.FLASH, flash.kind, "FLASH kind (xr attrs)")

        val ram = regions.single { it.name == "RAM" }
        assertEquals(0x2000_0000uL, ram.origin, "RAM origin")
        assertEquals(0x0004_0000uL, ram.length, "RAM length = 256 KB")
        assertEquals(MemoryRegionKind.RAM, ram.kind, "RAM kind (xw attrs contain 'w')")

        // IDT_LIST attrs "xw": 'w' present -> RAM kind.
        val idt = regions.single { it.name == "IDT_LIST" }
        assertEquals(MemoryRegionKind.RAM, idt.kind, "IDT_LIST attrs=xw -> RAM kind")

        // Exactly 3 non-default entries.
        assertEquals(3, regions.size, "FLASH + RAM + IDT_LIST = 3 regions")
    }

    // ---------------------------------------------------------------------------
    // hello_world-nucleo_f429zi-Os: FLASH + RAM + SRAM0 + DTCM + IDT_LIST
    // DTCM attrs="rw": 'w' present -> RAM kind (no execute; pure data TCM).
    // SRAM0 attrs="rw" -> RAM kind.
    // ---------------------------------------------------------------------------

    @Test
    fun nucleoF429ziOsMapRegions() {
        val mapText = readText("hello_world-nucleo_f429zi-Os/build/zephyr/zephyr.map")
        val regions = parseLinkerMapRegions(mapText)

        val flash = regions.single { it.name == "FLASH" }
        assertEquals(0x0800_0000uL, flash.origin, "nucleo FLASH origin = 0x08000000")
        assertEquals(0x0020_0000uL, flash.length, "nucleo FLASH length = 2 MB")
        assertEquals(MemoryRegionKind.FLASH, flash.kind, "FLASH kind (xr)")

        val ram = regions.single { it.name == "RAM" }
        assertEquals(0x2000_0000uL, ram.origin)
        assertEquals(0x0003_0000uL, ram.length, "nucleo RAM = 192 KB")
        assertEquals(MemoryRegionKind.RAM, ram.kind)

        // DTCM: origin=0x10000000, length=64KB, attrs="rw" => RAM kind.
        // (STM32F429 DTCM is tightly-coupled data RAM; map lists it with rw, no execute.)
        val dtcm = regions.single { it.name == "DTCM" }
        assertEquals(0x1000_0000uL, dtcm.origin, "DTCM origin")
        assertEquals(0x0001_0000uL, dtcm.length, "DTCM length = 64 KB")
        assertEquals(MemoryRegionKind.RAM, dtcm.kind, "DTCM attrs=rw -> 'w' present -> RAM kind")

        assertFalse(regions.any { it.name == "*default*" })
    }

    // ---------------------------------------------------------------------------
    // no-memconfig-map: map with the Memory Configuration block removed -> emptyList
    // ---------------------------------------------------------------------------

    @Test
    fun noMemconfigMapReturnsEmpty() {
        val mapText = readText("no-memconfig-map/build/zephyr/zephyr.map")
        val regions = parseLinkerMapRegions(mapText)
        assertTrue(regions.isEmpty(),
            "A map without 'Memory Configuration' block must yield an empty list, got $regions")
    }

    // ---------------------------------------------------------------------------
    // LLD firmware.map: LLD emits NO Memory Configuration block -> emptyList.
    // LLD maps start directly with a column header line:
    //   "     VMA      LMA     Size Align Out     In      Symbol"
    // followed by section rows. There is no "Memory Configuration" string anywhere.
    // Verified against the lld-baremetal sample built with LLVM 21 ld.lld.
    // ---------------------------------------------------------------------------

    @Test
    fun lldMapReturnsEmpty() {
        val mapText = readText("lld-baremetal/firmware.map")
        val regions = parseLinkerMapRegions(mapText)
        assertTrue(regions.isEmpty(),
            "LLD maps have no Memory Configuration block and must yield an empty list, got $regions")
    }

    // ---------------------------------------------------------------------------
    // linkerMapMatchesElf: stale-map (O2 map vs Os ELF) -> false
    //                      fresh map (Os map vs Os ELF)  -> true
    // ---------------------------------------------------------------------------

    // ---------------------------------------------------------------------------
    // linkerMapMatchesElf
    //
    // Zephyr GNU ld maps use *undotted* output section names (text, rodata, datas …),
    // so the parser's single-line regex (^\s+\.\S+\s+0x…) never fires on them and
    // total < 3 -> benefit-of-doubt returns true for all real Zephyr maps.
    //
    // Staleness detection is exercised via the lld-baremetal ELF, which uses
    // conventional dotted names (.text, .data, .bss, .vectors) that the parser CAN match.
    // A synthetic GNU-ld-style map is constructed with wrong addresses for those sections.
    // ---------------------------------------------------------------------------

    /** Build a minimal GNU-ld-style map with dotted-name rows for linkerMapMatchesElf tests. */
    private fun syntheticMapBlock(sections: List<Pair<String, ULong>>): String {
        val rows = sections.joinToString("\n") { (name, addr) ->
            " $name  0x${addr.toString(16).padStart(8, '0')}  0x100"
        }
        return "Memory Configuration\n\nName  Origin  Length  Attributes\n" +
            "FLASH  0x08000000  0x00040000  xr\n" +
            "RAM  0x20000000  0x00010000  xw\n\n" +
            "Linker script and memory map\n\n$rows\n"
    }

    @Test
    fun staleMapDetected() {
        // lld-baremetal ELF ALLOC sections (verified by parsing section headers):
        //   .vectors  at 0x08000000   .ARM.exidx at 0x08000040
        //   .text     at 0x08000058   .data      at 0x20000000   .bss at 0x20000100
        //
        // A stale map would show .vectors at 0x08000000 (agrees), but .text, .data, .bss
        // at shifted addresses (disagree).  3/4 matched sections disagree (75% > 20%
        // threshold) -> linkerMapMatchesElf returns false.
        val sections = elfSections("lld-baremetal/firmware.elf")
        val staleMap = syntheticMapBlock(listOf(
            ".vectors" to 0x0800_0000uL, // agrees
            ".text"    to 0x0800_00A0uL, // disagrees (ELF: 0x08000058)
            ".data"    to 0x2000_0200uL, // disagrees (ELF: 0x20000000)
            ".bss"     to 0x2000_0400uL, // disagrees (ELF: 0x20000100)
        ))

        assertFalse(
            linkerMapMatchesElf(staleMap, sections),
            "3/4 sections disagree -> must return false",
        )
    }

    @Test
    fun freshMapMatchesSynthetic() {
        // Same ELF, correct addresses -> all 4 agree (100%) -> returns true.
        val sections = elfSections("lld-baremetal/firmware.elf")
        val freshMap = syntheticMapBlock(listOf(
            ".vectors" to 0x0800_0000uL, // correct
            ".text"    to 0x0800_0058uL, // correct
            ".data"    to 0x2000_0000uL, // correct
            ".bss"     to 0x2000_0100uL, // correct
        ))

        assertTrue(
            linkerMapMatchesElf(freshMap, sections),
            "All 4 sections agree -> must return true",
        )
    }

    @Test
    fun staleZephyrMapDetected() {
        // Real stale pair: the stale-map fixture holds an Os-built ELF paired with an O2 map.
        // The ELF has undotted section names (text, rodata, datas, bss, ...).  The O2 map
        // has the same section names but different VMAs for the FLASH sections (e.g. rodata at
        // 0x61d0 vs ELF's 0x4bb0).  With the ELF-driven matching, at least 3 sections are
        // comparable and fewer than 80% agree => returns false.
        val staleMap = readText("stale-map/build/zephyr/zephyr.map")
        val sections = elfSections("stale-map/build/zephyr/zephyr.elf")

        assertFalse(
            linkerMapMatchesElf(staleMap, sections),
            "Real stale pair (O2 map vs Os ELF): mismatched VMAs must be detected -> false",
        )
    }

    @Test
    fun freshMapMatches() {
        // Matching pair: Os map with Os ELF.  All section VMAs agree => returns true.
        val freshMap = readText("hello_world-nrf52840dk-Os/build/zephyr/zephyr.map")
        val sections = elfSections("hello_world-nrf52840dk-Os/build/zephyr/zephyr.elf")

        assertTrue(
            linkerMapMatchesElf(freshMap, sections),
            "Os map against its own Os ELF: all VMAs agree -> must return true",
        )
    }
}
