package org.jetbrains.hexana.wasisdk

import com.google.gson.Gson
import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.WasiSdkReleases
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class WasiSdkStorageTest : UsefulTestCase() {

    private lateinit var tempRoot: Path

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("wasisdk-test")
    }

    override fun tearDown() {
        tempRoot.toFile().deleteRecursively()
        super.tearDown()
    }

    // ── installedVersions ─────────────────────────────────────────────────────

    fun testInstalledVersionsEmptyRoot() {
        val storage = storage("linux")
        assertEquals(emptyList<Int>(), storage.installedVersions())
    }

    fun testInstalledVersionsFindsValidInstall() {
        createFakeInstall(33, "linux")
        val storage = storage("linux")
        assertEquals(listOf(33), storage.installedVersions())
    }

    fun testInstalledVersionsIgnoresDirWithoutLldb() {
        val installDir = tempRoot.resolve(WasiSdkReleases.installDirName(33))
        Files.createDirectories(installDir.resolve("bin"))
        // No lldb binary created
        val storage = storage("linux")
        assertEquals(emptyList<Int>(), storage.installedVersions())
    }

    fun testInstalledVersionsSortedDescending() {
        createFakeInstall(31, "linux")
        createFakeInstall(33, "linux")
        createFakeInstall(32, "linux")
        val storage = storage("linux")
        assertEquals(listOf(33, 32, 31), storage.installedVersions())
    }

    // ── manifest read/write/rebuild ───────────────────────────────────────────

    fun testManifestRoundtrip() {
        val storage = storage("linux")
        Files.createDirectories(tempRoot)
        val manifest = WasiSdkStorage.Manifest(
            schemaVersion = 1,
            activeVersion = 33,
            installed = listOf(
                WasiSdkStorage.InstalledEntry(33, 1000L),
                WasiSdkStorage.InstalledEntry(31, 500L),
            ),
        )
        storage.writeManifest(manifest)
        val read = storage.readManifest()
        assertEquals(1, read.schemaVersion)
        assertEquals(33, read.activeVersion)
        assertEquals(2, read.installed.size)
        assertEquals(33, read.installed[0].version)
        assertEquals(31, read.installed[1].version)
    }

    fun testManifestRebuildFromDiskWhenMissing() {
        createFakeInstall(33, "linux")
        val storage = storage("linux")
        // No manifest file written
        val manifest = storage.readManifest()
        assertEquals(1, manifest.installed.size)
        assertEquals(33, manifest.installed[0].version)
        assertNull(manifest.activeVersion)
    }

    fun testManifestRebuildOnCorruption() {
        Files.createDirectories(tempRoot)
        val manifestPath = tempRoot.resolve("manifest.json")
        manifestPath.toFile().writeText("NOT VALID JSON $$$$")
        createFakeInstall(32, "linux")
        val storage = storage("linux")
        val manifest = storage.readManifest()
        assertEquals(1, manifest.installed.size)
        assertEquals(32, manifest.installed[0].version)
    }

    // ── activeVersion / setActiveVersion ─────────────────────────────────────

    fun testActiveVersionNullWhenNoManifest() {
        val storage = storage("linux")
        assertNull(storage.activeVersion())
    }

    fun testSetActiveVersionAndRead() {
        Files.createDirectories(tempRoot)
        val storage = storage("linux")
        storage.setActiveVersion(33)
        assertEquals(33, storage.activeVersion())
    }

    // ── activeLldbPath ────────────────────────────────────────────────────────

    fun testActiveLldbPathNullWhenNoActiveVersion() {
        val storage = storage("linux")
        assertNull(storage.activeLldbPath())
    }

    fun testActiveLldbPathReturnsCorrectPath() {
        createFakeInstall(33, "linux")
        val storage = storage("linux")
        storage.setActiveVersion(33)
        val lldbPath = storage.activeLldbPath()
        assertNotNull(lldbPath)
        assertTrue("lldb binary should exist", Files.exists(lldbPath!!))
        assertTrue("path should end with bin/lldb", lldbPath.toString().endsWith("bin/lldb"))
    }

    fun testActiveLldbPathNullWhenBinaryMissing() {
        val installDir = tempRoot.resolve(WasiSdkReleases.installDirName(33))
        Files.createDirectories(installDir.resolve("bin"))
        // lldb binary NOT created
        Files.createDirectories(tempRoot)
        val storage = storage("linux")
        storage.setActiveVersion(33)
        assertNull(storage.activeLldbPath())
    }

    // ── sha256 ────────────────────────────────────────────────────────────────

    fun testSha256HexMatchesKnownValue() {
        val bytes = "abc".toByteArray()
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals(expected, hex)
    }

    // ── lock ─────────────────────────────────────────────────────────────────

    fun testLockAcquireAndRelease() {
        Files.createDirectories(tempRoot)
        val storage = storage("linux")
        val lock = storage.acquireLock()
        assertNotNull("Should be able to acquire lock on empty dir", lock)
        storage.releaseLock(lock!!)
        assertFalse(
            "Lock file should be removed after release",
            Files.exists(tempRoot.resolve(".lock")),
        )
    }

    // ── recordInstalled ───────────────────────────────────────────────────────

    fun testRecordInstalledSetsActiveVersion() {
        Files.createDirectories(tempRoot)
        val storage = storage("linux")
        storage.recordInstalled(33)
        assertEquals(33, storage.activeVersion())
        val manifest = storage.readManifest()
        assertEquals(1, manifest.installed.size)
        assertEquals(33, manifest.installed[0].version)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun createFakeInstall(version: Int, os: String) {
        val installDir = tempRoot.resolve(WasiSdkReleases.installDirName(version))
        val lldbRel = WasiSdkReleases.lldbRelativePath(os)
        val lldbPath = installDir.resolve(lldbRel)
        Files.createDirectories(lldbPath.parent)
        Files.write(lldbPath, byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
    }

    private fun storage(os: String): WasiSdkStorage = WasiSdkStorage(tempRoot, os, "x86_64")
}
