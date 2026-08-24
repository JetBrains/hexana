package org.jetbrains.hexana.providers.memory

// Fixture-based tests for findFdtBlob and parseFdtMemoryRegions.
// The embedded-fdt sample contains a hand-crafted FDT blob embedded in .rodata of image.elf
// at file offset 0x10004 (as recorded in fdt-offset.txt).
//
// Run with: ./gradlew :binaryProvider:jvmTest --tests "*FdtRegions*"

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FdtRegionsTest {

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

    // ---------------------------------------------------------------------------
    // findFdtBlob: image.elf contains the FDT magic at file offset 0x10004.
    // The blob describes memory@40000000 with reg=<0x40000000 0x8000000> (128 MB).
    // ---------------------------------------------------------------------------

    @Test
    fun findFdtBlobInImageElf() {
        val elfPath = budgetFile("embedded-fdt/image.elf")
        val elfFile = File(elfPath)
        assertTrue(elfFile.exists(), "image.elf fixture absent: $elfPath")
        val bytes = elfFile.readBytes()

        val regions = findFdtBlob(bytes)

        assertEquals(1, regions.size, "Expected exactly 1 FDT memory region; got $regions")
        val region = regions[0]
        // memory@40000000: origin=0x40000000, size=0x8000000 (128 MB)
        assertEquals(0x4000_0000uL, region.origin, "FDT memory origin = 0x40000000")
        assertEquals(0x0800_0000uL, region.length, "FDT memory size = 128 MB (0x8000000)")
        assertEquals(MemoryRegionKind.RAM, region.kind, "FDT memory node -> RAM kind")
    }

    // ---------------------------------------------------------------------------
    // parseFdtMemoryRegions on garbage bytes -> emptyList (must not throw)
    // ---------------------------------------------------------------------------

    @Test
    fun garbageBytesReturnsEmpty() {
        val garbage = ByteArray(256) { (it * 37 + 13).toByte() }
        val regions = parseFdtMemoryRegions(garbage)
        assertTrue(regions.isEmpty(), "Garbage bytes must yield an empty list, not throw")
    }

    @Test
    fun emptyBytesReturnsEmpty() {
        val regions = parseFdtMemoryRegions(ByteArray(0))
        assertTrue(regions.isEmpty(), "Empty byte array must yield an empty list")
    }

    @Test
    fun tooShortBytesReturnsEmpty() {
        val regions = parseFdtMemoryRegions(ByteArray(10))
        assertTrue(regions.isEmpty(), "Too-short byte array must yield an empty list")
    }

    // ---------------------------------------------------------------------------
    // findFdtBlob on a byte array with no FDT magic -> emptyList
    // ---------------------------------------------------------------------------

    @Test
    fun findFdtBlobNoMagicReturnsEmpty() {
        val noMagic = ByteArray(512) { it.toByte() }
        val regions = findFdtBlob(noMagic)
        assertTrue(regions.isEmpty(), "No FDT magic -> empty list")
    }
}
