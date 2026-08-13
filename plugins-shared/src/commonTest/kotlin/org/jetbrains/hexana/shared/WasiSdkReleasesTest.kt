package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WasiSdkReleasesTest {

    // ── parseTag ────────────────────────────────────────────────────────────

    @Test
    fun parseTagAcceptsMinVersion() {
        assertEquals(31, WasiSdkReleases.parseTag("wasi-sdk-31"))
    }

    @Test
    fun parseTagAcceptsHigherVersion() {
        assertEquals(33, WasiSdkReleases.parseTag("wasi-sdk-33"))
    }

    @Test
    fun parseTagRejectsBelowMin() {
        assertNull(WasiSdkReleases.parseTag("wasi-sdk-30"))
    }

    @Test
    fun parseTagRejectsBelowMinSmall() {
        assertNull(WasiSdkReleases.parseTag("wasi-sdk-1"))
    }

    @Test
    fun parseTagRejectsRcTag() {
        assertNull(WasiSdkReleases.parseTag("wasi-sdk-34-rc.1"))
    }

    @Test
    fun parseTagRejectsRcTagOtherFormat() {
        assertNull(WasiSdkReleases.parseTag("wasi-sdk-33-rc.2"))
    }

    @Test
    fun parseTagRejectsGarbage() {
        assertNull(WasiSdkReleases.parseTag("garbage"))
    }

    @Test
    fun parseTagRejectsEmptyString() {
        assertNull(WasiSdkReleases.parseTag(""))
    }

    @Test
    fun parseTagRejectsVersionWithDotSuffix() {
        // "wasi-sdk-33.0" is not a valid release tag
        assertNull(WasiSdkReleases.parseTag("wasi-sdk-33.0"))
    }

    // ── expectedAssetName ───────────────────────────────────────────────────

    @Test
    fun expectedAssetNameMacosArm() {
        assertEquals(
            "wasi-sdk-33.0-arm64-macos.tar.gz",
            WasiSdkReleases.expectedAssetName(33, "macos", "arm64"),
        )
    }

    @Test
    fun expectedAssetNameLinuxX86() {
        assertEquals(
            "wasi-sdk-32.0-x86_64-linux.tar.gz",
            WasiSdkReleases.expectedAssetName(32, "linux", "x86_64"),
        )
    }

    @Test
    fun expectedAssetNameWindows() {
        assertEquals(
            "wasi-sdk-31.0-x86_64-windows.tar.gz",
            WasiSdkReleases.expectedAssetName(31, "windows", "x86_64"),
        )
    }

    // ── selectAsset ─────────────────────────────────────────────────────────

    @Test
    fun selectAssetFindsMatch() {
        val names = listOf(
            "wasi-sdk-33.0-x86_64-linux.tar.gz",
            "wasi-sdk-33.0-arm64-macos.tar.gz",
            "wasi-sdk-33.0-x86_64-windows.tar.gz",
        )
        assertEquals(
            "wasi-sdk-33.0-arm64-macos.tar.gz",
            WasiSdkReleases.selectAsset(names, 33, "macos", "arm64"),
        )
    }

    @Test
    fun selectAssetNoMatch() {
        val names = listOf(
            "wasi-sdk-33.0-x86_64-linux.tar.gz",
            "wasi-sdk-33.0-x86_64-windows.tar.gz",
        )
        assertNull(WasiSdkReleases.selectAsset(names, 33, "macos", "arm64"))
    }

    @Test
    fun selectAssetEmptyList() {
        assertNull(WasiSdkReleases.selectAsset(emptyList(), 33, "macos", "arm64"))
    }

    @Test
    fun selectAssetWrongVersion() {
        val names = listOf("wasi-sdk-32.0-arm64-macos.tar.gz")
        assertNull(WasiSdkReleases.selectAsset(names, 33, "macos", "arm64"))
    }

    // ── parseSha256Digest ────────────────────────────────────────────────────

    @Test
    fun parseSha256DigestNull() {
        assertNull(WasiSdkReleases.parseSha256Digest(null))
    }

    @Test
    fun parseSha256DigestBlank() {
        assertNull(WasiSdkReleases.parseSha256Digest(""))
    }

    @Test
    fun parseSha256DigestMalformed() {
        assertNull(WasiSdkReleases.parseSha256Digest("md5:abc123"))
    }

    @Test
    fun parseSha256DigestValid() {
        val hex = "abc123def456"
        assertEquals(hex, WasiSdkReleases.parseSha256Digest("sha256:$hex"))
    }

    @Test
    fun parseSha256DigestEmptyHex() {
        assertNull(WasiSdkReleases.parseSha256Digest("sha256:"))
    }

    // ── installDirName ───────────────────────────────────────────────────────

    @Test
    fun installDirName33() {
        assertEquals("wasi-sdk-33.0", WasiSdkReleases.installDirName(33))
    }

    @Test
    fun installDirName31() {
        assertEquals("wasi-sdk-31.0", WasiSdkReleases.installDirName(31))
    }

    // ── archiveTopLevelDir ───────────────────────────────────────────────────

    @Test
    fun archiveTopLevelDirMacosArm() {
        assertEquals(
            "wasi-sdk-33.0-arm64-macos",
            WasiSdkReleases.archiveTopLevelDir(33, "macos", "arm64"),
        )
    }

    @Test
    fun archiveTopLevelDirLinuxX86() {
        assertEquals(
            "wasi-sdk-32.0-x86_64-linux",
            WasiSdkReleases.archiveTopLevelDir(32, "linux", "x86_64"),
        )
    }

    // ── lldbRelativePath ─────────────────────────────────────────────────────

    @Test
    fun lldbRelativePathMacos() {
        assertEquals("bin/lldb", WasiSdkReleases.lldbRelativePath("macos"))
    }

    @Test
    fun lldbRelativePathLinux() {
        assertEquals("bin/lldb", WasiSdkReleases.lldbRelativePath("linux"))
    }

    @Test
    fun lldbRelativePathWindows() {
        assertEquals("bin/lldb.exe", WasiSdkReleases.lldbRelativePath("windows"))
    }
}
