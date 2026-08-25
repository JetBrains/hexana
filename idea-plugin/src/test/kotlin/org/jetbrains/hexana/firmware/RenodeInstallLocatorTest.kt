package org.jetbrains.hexana.firmware

import com.intellij.testFramework.UsefulTestCase
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Locator tests for [RenodeDownloader.guessInstalledExe] against the real install layouts:
 * the macOS DMG copy lands the app's `Contents/` directly in the install dir (and a plain
 * stream copy loses the executable bit — the locator must restore it), portable archives
 * put `renode` at the root or under a version subdirectory.
 *
 * Regression for the sandbox failure "Renode executable not found after extraction":
 * the extracted layout was `<installDir>/Contents/MacOS/renode` (lowercase, no
 * `Renode.app` level, exec bit lost) and the old locator missed it.
 */
class RenodeInstallLocatorTest : UsefulTestCase() {

    fun testDmgContentsLayoutLowercaseWithoutExecBit() {
        val dir = createTempDirectory("renode-locator-").toFile()
        try {
            val exe = File(dir, "Contents/MacOS/renode")
            exe.parentFile.mkdirs()
            exe.writeText("#!/bin/sh\n")
            exe.setExecutable(false)

            val found = RenodeDownloader.guessInstalledExe(dir.toPath())
            assertEquals(exe.absolutePath, found)
            assertTrue("locator must restore the executable bit", File(found!!).canExecute())
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testPreservedAppBundleLayout() {
        val dir = createTempDirectory("renode-locator-").toFile()
        try {
            val exe = File(dir, "Renode.app/Contents/MacOS/renode")
            exe.parentFile.mkdirs()
            exe.writeText("#!/bin/sh\n")
            assertEquals(exe.absolutePath, RenodeDownloader.guessInstalledExe(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testPortableRootAndNestedVersionDirLayouts() {
        val root = createTempDirectory("renode-locator-").toFile()
        try {
            val rootExe = File(root, "renode")
            rootExe.writeText("#!/bin/sh\n")
            assertEquals(rootExe.absolutePath, RenodeDownloader.guessInstalledExe(root.toPath()))
        } finally {
            root.deleteRecursively()
        }

        val nested = createTempDirectory("renode-locator-").toFile()
        try {
            val exe = File(nested, "renode_1.16.1_portable/renode")
            exe.parentFile.mkdirs()
            exe.writeText("#!/bin/sh\n")
            assertEquals(exe.absolutePath, RenodeDownloader.guessInstalledExe(nested.toPath()))
        } finally {
            nested.deleteRecursively()
        }
    }

    fun testNoExecutableAnywhereReturnsNull() {
        val dir = createTempDirectory("renode-locator-").toFile()
        try {
            File(dir, "Contents/MacOS").mkdirs()
            File(dir, "README.txt").writeText("nothing here")
            assertNull(RenodeDownloader.guessInstalledExe(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
