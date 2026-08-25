package org.jetbrains.hexana.firmware.run

import com.intellij.testFramework.UsefulTestCase
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Pins the plain-path sidecar discovery used by [FirmwareRunConfiguration.effectiveDtsPath].
 *
 * Regression for the sandbox failure "Renode platform generation requires a zephyr.dts
 * file" shown for an ELF with `zephyr.dts` sitting right next to it: a manually created
 * run configuration with a blank DTS field never ran any discovery.
 */
class SidecarPathDiscoveryTest : UsefulTestCase() {

    fun testSiblingDtsIsDiscovered() {
        val dir = createTempDirectory("sidecar-").toFile()
        try {
            val elf = File(dir, "zephyr.elf").apply { writeText("") }
            val dts = File(dir, "zephyr.dts").apply { writeText("/ {};") }
            assertEquals(dts.absolutePath, discoverSidecarPath(elf.absolutePath, "zephyr.dts"))
            assertNull(discoverSidecarPath(elf.absolutePath, ".config"))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testZephyrSubdirOfElfDirAndOfParentDir() {
        val root = createTempDirectory("sidecar-").toFile()
        try {
            // Build-tree shape: <root>/zephyr/zephyr.elf with .config in the same zephyr/ dir
            // is the sibling case; here we pin the two zephyr/-subdir fallbacks.
            val elf = File(root, "out/zephyr.elf").apply { parentFile.mkdirs(); writeText("") }
            val dtsInElfDirZephyr = File(root, "out/zephyr/zephyr.dts").apply { parentFile.mkdirs(); writeText("/ {};") }
            assertEquals(dtsInElfDirZephyr.absolutePath, discoverSidecarPath(elf.absolutePath, "zephyr.dts"))

            dtsInElfDirZephyr.delete()
            val dtsInParentZephyr = File(root, "zephyr/zephyr.dts").apply { parentFile.mkdirs(); writeText("/ {};") }
            assertEquals(dtsInParentZephyr.absolutePath, discoverSidecarPath(elf.absolutePath, "zephyr.dts"))
        } finally {
            root.deleteRecursively()
        }
    }

    fun testBlankAndMissingReturnNull() {
        assertNull(discoverSidecarPath("", "zephyr.dts"))
        val dir = createTempDirectory("sidecar-").toFile()
        try {
            val elf = File(dir, "zephyr.elf").apply { writeText("") }
            assertNull(discoverSidecarPath(elf.absolutePath, "zephyr.dts"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
