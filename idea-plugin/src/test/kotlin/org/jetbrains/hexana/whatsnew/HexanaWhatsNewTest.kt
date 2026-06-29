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
        assertEquals("0.12", resolveWhatsNewResourceVersion("0.12"))
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
        assertNull(resolveWhatsNewResourceVersion("broken-missing-asset"))
        assertNull(HexanaWhatsNewContentService.createContent("broken-missing-asset"))
    }

    fun testCreatesGeneratedBundledHtmlContent() {
        val content = HexanaWhatsNewContentService.createContent("0.12")
        assertNotNull(content)
        content!!

        try {
            assertEquals("0.12", content.resourceVersion)
            assertTrue(content.indexFile.exists())
            assertTrue(content.rootDirectory.resolve("style.css").exists())
            assertTrue(content.rootDirectory.resolve("source.json").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/wasm-component-model.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/static-archives.png").exists())
        } finally {
            val root = content.rootDirectory
            content.dispose()
            assertFalse(root.exists())
        }
    }

    fun testGeneratedWhatsNewStaticResourceContract() {
        val root = "whatsnew/0.12"
        val html = resourceText("$root/index.html")
        val css = resourceText("$root/style.css")
        val sourceJson = resourceText("$root/source.json")
        val manifestLines = resourceText("$root/manifest.txt")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val manifestEntries = manifestLines.toSet()

        assertTrue(manifestEntries.contains("index.html"))
        assertTrue(manifestEntries.contains("style.css"))
        assertTrue(manifestEntries.contains("source.json"))
        assertEquals(manifestLines.size, manifestEntries.size)
        for (entry in manifestEntries) {
            assertResourceExists("$root/$entry")
        }

        val htmlLocalReferences = htmlAttributeReferences(html).filter(::isLocalReference).toSortedSet()
        val cssLocalReferences = cssUrlReferences(css).filter(::isLocalReference).toSortedSet()
        val sourceAssetReferences = sourceAssetReferences(sourceJson)
        val expectedLocalReferences = sortedSetOf(
            "style.css",
            "screenshots/static-archives.png",
            "screenshots/wasm-component-model.png",
        )

        assertEquals(expectedLocalReferences, htmlLocalReferences)
        assertTrue(cssLocalReferences.isEmpty())
        assertTrue(manifestEntries.containsAll(htmlLocalReferences + cssLocalReferences + sourceAssetReferences))

        for (heading in requiredGeneratedHeadings()) {
            assertTrue("Missing heading: $heading", html.contains(heading))
        }
        assertTrue(html.contains("""href="https://plugins.jetbrains.com/plugin/29090-hexana--webassembly--binary-inspector">Open release notes</a>"""))
        assertTrue(html.contains("""href="https://github.com/JetBrains/hexana">Open GitHub</a>"""))
        assertTrue(sourceJson.contains(""""version": "0.12""""))
        assertTrue(sourceJson.contains(""""changelog": "idea-plugin/CHANGELOG.md""""))

        val imageTags = Regex("""<img\b[^>]*>""").findAll(html).map { it.value }.toList()
        assertEquals(2, imageTags.size)
        for (imageTag in imageTags) {
            assertTrue("Missing image alt text: $imageTag", Regex("""\balt="[^"]*\S[^"]*"""").containsMatchIn(imageTag))
        }

        val listItems = Regex("""<li>.*?</li>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.value }.toList()
        assertTrue(listItems.isNotEmpty())
        for (listItem in listItems) {
            assertFalse("Raw bold marker leaked into generated HTML: $listItem", listItem.contains("**"))
            assertFalse("Raw code marker leaked into generated HTML: $listItem", listItem.contains("`"))
        }
        assertTrue(html.contains("<strong>Compare WASM With…</strong>"))
        assertTrue(html.contains("<strong>Copy, inspect, and navigate imports and exports faster</strong>"))
        assertTrue(html.contains("<strong>More binary formats and cleaner IDE entry points</strong>"))
        assertTrue(html.contains("<code>.a</code>"))
        assertTrue(html.contains("<code>.lib</code>"))
        assertFalse(html.contains("Kotlin/Wasm"))
        assertFalse(sourceJson.contains("Kotlin/Wasm modules"))
        assertFalse(html.contains("Edit mode UX"))
    }

    private fun resourceText(path: String): String {
        val stream = HexanaWhatsNewTest::class.java.classLoader.getResourceAsStream(path)
        assertNotNull("Missing resource: $path", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun assertResourceExists(path: String) {
        assertNotNull("Missing resource: $path", HexanaWhatsNewTest::class.java.classLoader.getResource(path))
    }

    private fun htmlAttributeReferences(html: String): Set<String> {
        return Regex("""\b(?:href|src)="([^"]+)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun cssUrlReferences(css: String): Set<String> {
        return Regex("""url\((['"]?)([^)'"]+)\1\)""")
            .findAll(css)
            .map { it.groupValues[2] }
            .toSet()
    }

    private fun sourceAssetReferences(sourceJson: String): Set<String> {
        return Regex(""""asset":\s*"([^"]+)"""")
            .findAll(sourceJson)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun isLocalReference(reference: String): Boolean {
        return !reference.startsWith("http://") &&
            !reference.startsWith("https://") &&
            !reference.startsWith("mailto:") &&
            !reference.startsWith("#") &&
            !reference.startsWith("data:")
    }

    private fun requiredGeneratedHeadings(): List<String> = listOf(
        "<h1>What's New in Hexana 0.12</h1>",
        "<h2>Compare and inspect Component Model binaries</h2>",
        "<h2>Inspect static libraries and object members</h2>",
        "<h2>Also in 0.12</h2>",
        "<h2>Release notes</h2>",
    )
}
