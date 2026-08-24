package org.jetbrains.hexana.providers.memory

// Pure ordering tests for siblingMapCandidates, the auto-discovery order used by the
// Memory tab when it attempts to load a linker map next to an opened ELF.
//
// Run with: ./gradlew :binaryProvider:jvmTest --tests "*SiblingMapCandidates*"

import kotlin.test.Test
import kotlin.test.assertEquals

class SiblingMapCandidatesTest {

    @Test
    fun zephyrBuildLayoutPrefersZephyrMap() {
        val siblings = listOf("BUILD.md", "zephyr.elf", "zephyr.dts", "zephyr.map")
        assertEquals(listOf("zephyr.map"), siblingMapCandidates("zephyr.elf", siblings))
    }

    @Test
    fun elfBaseNameMapBeatsZephyrMapBeatsOthers() {
        val siblings = listOf("other.map", "zephyr.map", "app.map", "app.elf")
        assertEquals(
            listOf("app.map", "zephyr.map", "other.map"),
            siblingMapCandidates("app.elf", siblings),
        )
    }

    @Test
    fun genericMapsOnlyAreReturnedSortedCaseInsensitively() {
        val siblings = listOf("readme.txt", "output.map", "Boot.map", "firmware.elf")
        assertEquals(
            listOf("Boot.map", "output.map"),
            siblingMapCandidates("firmware.elf", siblings),
        )
    }

    @Test
    fun absentExpectedNamesAreNotInvented() {
        val siblings = listOf("firmware.elf", "notes.md")
        assertEquals(emptyList(), siblingMapCandidates("firmware.elf", siblings))
    }

    @Test
    fun elfFileItselfIsNeverACandidate() {
        // Pathological name: the opened binary ends in .map; it must not offer itself.
        val siblings = listOf("kernel.map", "zephyr.map")
        assertEquals(listOf("zephyr.map"), siblingMapCandidates("kernel.map", siblings))
    }

    @Test
    fun extensionlessElfNameStillMatchesItsMap() {
        val siblings = listOf("vmlinux.map", "vmlinux")
        assertEquals(listOf("vmlinux.map"), siblingMapCandidates("vmlinux", siblings))
    }
}
