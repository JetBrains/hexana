package org.jetbrains.hexana.providers.memory

// Fixture-based tests for parseDtsMemoryRegions.
// Fixtures: ui-driver-tests/testData/fixtures/zephyr-corpus/budget-samples/
//
// Run with: ./gradlew :binaryProvider:jvmTest --tests "*DtsRegions*"

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DtsRegionsTest {

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
        return repoRoot
            .resolve("ui-driver-tests/testData/fixtures/zephyr-corpus/budget-samples/$relativePath")
            .toAbsolutePath().normalize().toString()
    }

    private fun readText(relativePath: String): String =
        File(budgetFile(relativePath)).also {
            assertTrue(it.exists(), "Fixture not found: $relativePath")
        }.readText()

    // ---------------------------------------------------------------------------
    // hello_world-nrf52840dk-Os: DTS has flash0: flash@0 nested inside
    // flash-controller@4001e000.  The parser descends into the controller node
    // (which contains "-controller" in its name) and finds flash@0 with
    // reg=<0x0 0x100000>.  sram0: memory@20000000 is at the top level.
    // ---------------------------------------------------------------------------

    @Test
    fun nrf52840dkOsFlashAndSram() {
        val dtsText = readText("hello_world-nrf52840dk-Os/build/zephyr/zephyr.dts")
        val regions = parseDtsMemoryRegions(dtsText)

        // flash@0 is nested inside flash-controller@4001e000.  The controller node is now
        // descended into (not consumed), so flash@0 is found and returned as FLASH.
        // flash@0 reg = <0x0 0x100000> => origin=0x0, length=1 MB.
        val flash = assertNotNull(
            regions.firstOrNull { it.kind == MemoryRegionKind.FLASH },
            "Expected a FLASH region (flash@0 inside flash-controller); got $regions"
        )
        assertEquals(0x0000_0000uL, flash.origin, "flash@0 origin = 0x0")
        assertEquals(0x0010_0000uL, flash.length, "flash@0 length = 1 MB (0x100000)")

        // sram0: memory@20000000, reg = <0x20000000 0x40000> => 256 KB.
        val ram = assertNotNull(
            regions.firstOrNull { it.kind == MemoryRegionKind.RAM && it.origin == 0x2000_0000uL },
            "Expected RAM region at 0x20000000; got $regions"
        )
        assertEquals(0x0004_0000uL, ram.length, "sram0 length = 256 KB (0x40000)")
    }

    // ---------------------------------------------------------------------------
    // overlay pair: both stock and overlay DTS have flash0 nested inside
    // flash-controller@4001e000.  The parser descends into the controller node
    // and finds flash@0.  Stock: reg=<0x0 0x100000> (1 MB).
    // Overlay: reg=<0x0 0x80000> (512 KB).
    // ---------------------------------------------------------------------------

    @Test
    fun overlayStockFlashLength() {
        val dtsText = readText("hello_world-nrf52840dk-overlay/stock/zephyr/zephyr.dts")
        val regions = parseDtsMemoryRegions(dtsText)
        val flash = regions.first { it.kind == MemoryRegionKind.FLASH }
        // Stock build: flash@0 reg = <0x0 0x100000> => 1 MB.
        assertEquals(0x0000_0000uL, flash.origin, "stock: flash@0 origin = 0x0")
        assertEquals(0x0010_0000uL, flash.length, "stock: flash@0 capacity = 1 MB (0x100000)")
    }

    @Test
    fun overlayReducedFlashLength() {
        // The overlay's flash@0 reg is reduced to 0x80000 (512 KB).  With recursive descent
        // the parser now finds flash@0 inside flash-controller@4001e000.
        val dtsText = readText("hello_world-nrf52840dk-overlay/overlay/zephyr/zephyr.dts")
        val regions = parseDtsMemoryRegions(dtsText)
        val flash = regions.first { it.kind == MemoryRegionKind.FLASH }
        assertEquals(0x0000_0000uL, flash.origin, "overlay: flash@0 origin = 0x0")
        assertEquals(0x0008_0000uL, flash.length, "overlay: flash@0 capacity = 512 KB (0x80000)")
    }

    // ---------------------------------------------------------------------------
    // Synthetic: 2-cell reg form (addrHi addrLo sizeHi sizeLo)
    // The bundled DTS files use 1-cell addressing; this test covers the 4-cell path.
    // reg = < 0x0 0x40000000 0x0 0x8000000 > encodes:
    //   addr = (0x0 << 32) | 0x40000000 = 0x40000000
    //   size = (0x0 << 32) | 0x8000000  = 0x8000000 (128 MB)
    // ---------------------------------------------------------------------------

    @Test
    fun twoCellRegForm() {
        val dtsText = """
            /dts-v1/;
            / {
                #address-cells = < 0x2 >;
                #size-cells = < 0x2 >;
                memory@40000000 {
                    device_type = "memory";
                    reg = < 0x0 0x40000000 0x0 0x8000000 >;
                };
            };
        """.trimIndent()
        val regions = parseDtsMemoryRegions(dtsText)
        assertEquals(1, regions.size, "Expected 1 region from 2-cell reg form; got $regions")
        assertEquals(0x4000_0000uL, regions[0].origin, "2-cell addr = 0x40000000")
        assertEquals(0x0800_0000uL, regions[0].length, "2-cell size = 128 MB")
        assertEquals(MemoryRegionKind.RAM, regions[0].kind)
    }

    // ---------------------------------------------------------------------------
    // Partition sub-nodes must be skipped
    // ---------------------------------------------------------------------------

    @Test
    fun partitionNodesAreSkipped() {
        val dtsText = """
            /dts-v1/;
            / {
                #address-cells = < 0x1 >;
                #size-cells = < 0x1 >;
                flash@8000000 {
                    reg = < 0x8000000 0x200000 >;
                    boot_partition: partition@0 {
                        reg = < 0x0 0x10000 >;
                    };
                    slot0_partition: partition@10000 {
                        reg = < 0x10000 0x70000 >;
                    };
                };
            };
        """.trimIndent()
        val regions = parseDtsMemoryRegions(dtsText)
        // Only the outer flash node counts; partitions are skipped.
        assertEquals(1, regions.size, "Only the outer flash node; partition sub-nodes must be skipped")
        assertEquals(0x0800_0000uL, regions[0].origin)
        assertEquals(0x0020_0000uL, regions[0].length)
        assertEquals(MemoryRegionKind.FLASH, regions[0].kind)
    }
}
