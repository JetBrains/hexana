package org.jetbrains.hexana.whatsnew

import com.intellij.testFramework.UsefulTestCase
import kotlin.io.path.exists

class HexanaWhatsNewTest : UsefulTestCase() {
    fun testMajorMinorVersion() {
        assertEquals("0.12", majorMinorVersion("0.12-SNAPSHOT"))
        assertEquals("0.12", majorMinorVersion("0.12.34-nightly"))
        assertEquals("2026.1", majorMinorVersion("2026.1"))
        assertEquals("2026.1", majorMinorVersion(" 2026.1.4 "))
    }

    fun testMajorMinorVersionRejectsMissingMinorVersion() {
        assertNull(majorMinorVersion("1"))
        assertNull(majorMinorVersion("SNAPSHOT"))
        assertNull(majorMinorVersion(""))
    }

    fun testShouldShowWhatsNewOnStartup() {
        assertTrue(shouldShowWhatsNewOnStartup(true, null, "0.12"))
        assertTrue(shouldShowWhatsNewOnStartup(true, "0.11", "0.12"))
        assertFalse(shouldShowWhatsNewOnStartup(true, "0.12", "0.12"))
        assertFalse(shouldShowWhatsNewOnStartup(false, "0.11", "0.12"))
        assertFalse(shouldShowWhatsNewOnStartup(true, "0.11", null))
    }

    fun testWhatsNewVirtualFileNameIncludesVersion() {
        val file = HexanaWhatsNewVirtualFile("0.12")

        assertEquals("Hexana What's New 0.12.hexana-whats-new", file.name)
        assertFalse(file.name.contains("{0}"))
    }

    fun testWhatsNewResourceDoesNotFallBackToLatest() {
        assertNull(resolveWhatsNewResourceVersion("999.999"))
        assertNull(HexanaWhatsNewContentService.createContent("999.999"))
    }

    fun testWhatsNewResourceResolvesExactFolderWhenSeveralVersionsExist() {
        assertEquals("0.11", resolveWhatsNewResourceVersion("0.11"))
        assertNull(resolveWhatsNewResourceVersion("0.12"))
        assertNull(resolveWhatsNewResourceVersion("0.12.1"))
    }

    fun testCreatesContentForOlderExactResourceVersion() {
        val content = HexanaWhatsNewContentService.createContent("0.11")
        assertNotNull(content)
        content!!

        try {
            assertEquals("0.11", content.resourceVersion)
            assertTrue(content.indexFile.exists())
            assertTrue(content.rootDirectory.resolve("style.css").exists())
            assertTrue(content.rootDirectory.resolve("assets/marker.txt").exists())
        } finally {
            val root = content.rootDirectory
            content.dispose()
            assertFalse(root.exists())
        }
    }

    fun testCreateContentReturnsNullWhenManifestIsMissing() {
        assertNull(resolveWhatsNewResourceVersion("no-manifest"))
        assertNull(HexanaWhatsNewContentService.createContent("no-manifest"))
    }

    fun testCreateContentReturnsNullWhenManifestAssetIsMissing() {
