package org.jetbrains.hexana.whatsnew

import com.intellij.testFramework.UsefulTestCase
import javax.imageio.ImageIO
import kotlin.io.path.exists

class HexanaWhatsNewTest : UsefulTestCase() {
    fun testComposeModelLoadsBundledReleaseSource() {
        val content = requireNotNull(HexanaWhatsNewContentService.createContent("0.15"))
        try {
            val model = loadWhatsNewUiModel(content)
            assertEquals("0.15", model.version)
            assertEquals(5, model.features.size)
            assertEquals("See where every byte goes", model.features.first().title)
            assertTrue(model.features.first().asset?.exists() == true)
            assertFalse(model.features.first().entries.first().contains("**"))
        } finally {
            content.dispose()
        }
    }

    fun testBundled019AnimatedGifFitsRuntimeBudget() {
        val content = requireNotNull(HexanaWhatsNewContentService.createContent("0.19"))
        try {
            ImageIO.createImageInputStream(content.rootDirectory.resolve("screenshots/component-model-debugger.gif").toFile()).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                    ?: error("No ImageIO reader for the bundled 0.19 GIF")
                try {
                    reader.input = input
                    val frameCount = reader.getNumImages(true)
                    val decodedBytes = frameCount.toLong() * reader.getWidth(0) * reader.getHeight(0) * 4

                    assertEquals(19, frameCount)
                    assertTrue(decodedBytes <= MAX_ANIMATED_DECODED_BYTES)
                    assertTrue(frameCount <= MAX_ANIMATED_FRAME_COUNT)
                } finally {
                    reader.dispose()
                }
            }
        } finally {
            content.dispose()
        }
    }

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
        assertEquals("0.13", resolveWhatsNewResourceVersion("0.13"))
        assertEquals("0.15", resolveWhatsNewResourceVersion("0.15"))
        assertEquals("0.17", resolveWhatsNewResourceVersion("0.17"))
        assertEquals("0.18", resolveWhatsNewResourceVersion("0.18"))
        assertEquals("0.19", resolveWhatsNewResourceVersion("0.19"))
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

    fun testCreatesGeneratedBundledHtmlContentFor013() {
        val content = HexanaWhatsNewContentService.createContent("0.13")
        assertNotNull(content)
        content!!

        try {
            assertEquals("0.13", content.resourceVersion)
            assertTrue(content.indexFile.exists())
            assertTrue(content.rootDirectory.resolve("style.css").exists())
            assertTrue(content.rootDirectory.resolve("source.json").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/columnar-data.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/protobuf-inspection.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/structure-tool-window.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/scriptable-tabs.png").exists())
        } finally {
            val root = content.rootDirectory
            content.dispose()
            assertFalse(root.exists())
        }
    }

    fun testCreatesGeneratedBundledHtmlContentFor015() {
        val content = HexanaWhatsNewContentService.createContent("0.15")
        assertNotNull(content)
        content!!

        try {
            assertEquals("0.15", content.resourceVersion)
            assertTrue(content.indexFile.exists())
            assertTrue(content.rootDirectory.resolve("style.css").exists())
            assertTrue(content.rootDirectory.resolve("source.json").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/top-size-visualization.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/inline-dispatch-candidates.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/exception-surface.png").exists())
        } finally {
            val root = content.rootDirectory
            content.dispose()
            assertFalse(root.exists())
        }
    }

    fun testCreatesGeneratedBundledHtmlContentFor016() {
        val content = HexanaWhatsNewContentService.createContent("0.16")
        assertNotNull(content)
        content!!

        try {
            assertEquals("0.16", content.resourceVersion)
            assertTrue(content.indexFile.exists())
            assertTrue(content.rootDirectory.resolve("style.css").exists())
            assertTrue(content.rootDirectory.resolve("source.json").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/glue-component-virtualized-wat.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/nested-component-editor.png").exists())
        } finally {
            val root = content.rootDirectory
            content.dispose()
            assertFalse(root.exists())
        }
    }

    fun testCreatesGeneratedBundledHtmlContentFor017() {
        val content = HexanaWhatsNewContentService.createContent("0.17")
        assertNotNull(content)
        content!!

        try {
            assertEquals("0.17", content.resourceVersion)
            assertTrue(content.indexFile.exists())
            assertTrue(content.rootDirectory.resolve("style.css").exists())
            assertTrue(content.rootDirectory.resolve("source.json").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/wasm-debugger-tab.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/wasm-problems-tab.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/pcap-packet-inspection.png").exists())
        } finally {
            val root = content.rootDirectory
            content.dispose()
            assertFalse(root.exists())
        }
    }

    fun testCreatesGeneratedBundledHtmlContentFor018() {
        val content = HexanaWhatsNewContentService.createContent("0.18")
        assertNotNull(content)
        content!!

        try {
            assertEquals("0.18", content.resourceVersion)
            assertTrue(content.indexFile.exists())
            assertTrue(content.rootDirectory.resolve("style.css").exists())
            assertTrue(content.rootDirectory.resolve("source.json").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/wasmtime-live-debugger.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/managed-wasi-sdk.png").exists())
            assertTrue(content.rootDirectory.resolve("screenshots/wat-component-authoring.png").exists())
        } finally {
            val root = content.rootDirectory
            content.dispose()
            assertFalse(root.exists())
        }
    }

    fun testGeneratedWhatsNew015StaticResourceContract() {
        val root = "whatsnew/0.15"
        val html = resourceText("$root/index.html")
        val manifestEntries = resourceText("$root/manifest.txt")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

        val expectedScreenshots = setOf(
            "screenshots/top-size-visualization.png",
            "screenshots/inline-dispatch-candidates.png",
            "screenshots/exception-surface.png",
        )
        assertTrue(manifestEntries.containsAll(expectedScreenshots))
        expectedScreenshots.forEach { assertResourceExists("$root/$it") }

        assertTrue(html.contains("<h1>What's New in Hexana 0.15</h1>"))
        assertTrue(html.contains("<h2>See where every byte goes</h2>"))
        assertTrue(html.contains("<h2>Expand indirect-call candidates inline</h2>"))
        assertTrue(html.contains("<h2>Understand what can escape to the host</h2>"))
        assertTrue(html.contains("<h2>Also in 0.15</h2>"))
        assertTrue(html.contains("<strong>Go from WASM rows back to source</strong>"))
        assertFalse(html.contains("sound static under-approximation"))

        val imageTags = Regex("""<img\b[^>]*>""").findAll(html).map { it.value }.toList()
        assertEquals(3, imageTags.size)
        imageTags.forEach { imageTag ->
            assertTrue("Missing image alt text: $imageTag", Regex("""\balt="[^"]*\S[^"]*"""").containsMatchIn(imageTag))
        }
    }

    fun testGeneratedWhatsNew016StaticResourceContract() {
        val root = "whatsnew/0.16"
        val html = resourceText("$root/index.html")
        val css = resourceText("$root/style.css")
        val sourceJson = resourceText("$root/source.json")
        val manifestLines = resourceText("$root/manifest.txt")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val manifestEntries = manifestLines.toSet()

        assertEquals(manifestLines.size, manifestEntries.size)
        for (entry in manifestEntries) {
            assertResourceExists("$root/$entry")
        }

        val htmlLocalReferences = htmlAttributeReferences(html).filter(::isLocalReference).toSortedSet()
        val cssLocalReferences = cssUrlReferences(css).filter(::isLocalReference).toSortedSet()
        val sourceAssetReferences = sourceAssetReferences(sourceJson)
        val expectedLocalReferences = sortedSetOf(
            "style.css",
            "screenshots/glue-component-virtualized-wat.png",
            "screenshots/nested-component-editor.png",
        )

        assertEquals(expectedLocalReferences, htmlLocalReferences)
        assertTrue(cssLocalReferences.isEmpty())
        assertTrue(manifestEntries.containsAll(htmlLocalReferences + cssLocalReferences + sourceAssetReferences))
        assertTrue(html.contains("<h1>What's New in Hexana 0.16</h1>"))
        assertTrue(html.contains("<h2>Read glue-only components in WAT (virtualized)</h2>"))
        assertTrue(html.contains("<h2>Open nested components as editor views</h2>"))
        assertTrue(sourceJson.contains("\"version\": \"0.16\""))
        assertTrue(sourceJson.contains("search by name or offset"))

        val imageTags = Regex("""<img\b[^>]*>""").findAll(html).map { it.value }.toList()
        assertEquals(2, imageTags.size)
        imageTags.forEach { imageTag ->
            assertTrue("Missing image alt text: $imageTag", Regex("""\balt="[^"]*\S[^"]*""").containsMatchIn(imageTag))
        }
    }

    fun testGeneratedWhatsNew017StaticResourceContract() {
        val root = "whatsnew/0.17"
        val html = resourceText("$root/index.html")
        val css = resourceText("$root/style.css")
        val sourceJson = resourceText("$root/source.json")
        val manifestLines = resourceText("$root/manifest.txt")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val manifestEntries = manifestLines.toSet()

        assertEquals(manifestLines.size, manifestEntries.size)
        for (entry in manifestEntries) {
            assertResourceExists("$root/$entry")
        }

        val htmlLocalReferences = htmlAttributeReferences(html).filter(::isLocalReference).toSortedSet()
        val cssLocalReferences = cssUrlReferences(css).filter(::isLocalReference).toSortedSet()
        val sourceAssetReferences = sourceAssetReferences(sourceJson)
        val expectedLocalReferences = sortedSetOf(
            "style.css",
            "screenshots/wasm-debugger-tab.png",
            "screenshots/wasm-problems-tab.png",
            "screenshots/pcap-packet-inspection.png",
        )

        assertEquals(expectedLocalReferences, htmlLocalReferences)
        assertTrue(cssLocalReferences.isEmpty())
        assertTrue(manifestEntries.containsAll(htmlLocalReferences + cssLocalReferences + sourceAssetReferences))
        assertTrue(html.contains("<h1>What's New in Hexana 0.17</h1>"))
        assertTrue(html.contains("<h2>Step through paused WASM at instruction precision</h2>"))
        assertTrue(html.contains("<h2>Keep investigating modules with parse problems</h2>"))
        assertTrue(html.contains("<h2>Inspect PCAP and PCAPNG packets</h2>"))
        assertTrue(html.contains("<h2>Also in 0.17</h2>"))
        assertTrue(html.contains("<strong>Debug source-level state with more context</strong>"))
        assertTrue(html.contains("<strong>Open TAR entries and gzip-compressed binaries directly</strong>"))
        assertTrue(html.contains("<strong>Read current WasmGC custom descriptors</strong>"))
        assertTrue(sourceJson.contains("\"version\": \"0.17\""))

        val imageTags = Regex("""<img\b[^>]*>""").findAll(html).map { it.value }.toList()
        assertEquals(3, imageTags.size)
        imageTags.forEach { imageTag ->
            assertTrue("Missing image alt text: $imageTag", Regex("""\balt="[^"]*\S[^"]*""").containsMatchIn(imageTag))
        }
    }

    fun testGeneratedWhatsNew018StaticResourceContract() {
        val root = "whatsnew/0.18"
        val html = resourceText("$root/index.html")
        val css = resourceText("$root/style.css")
        val sourceJson = resourceText("$root/source.json")
        val manifestLines = resourceText("$root/manifest.txt")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val manifestEntries = manifestLines.toSet()

        assertEquals(manifestLines.size, manifestEntries.size)
        for (entry in manifestEntries) {
            assertResourceExists("$root/$entry")
        }

        val htmlLocalReferences = htmlAttributeReferences(html).filter(::isLocalReference).toSortedSet()
        val cssLocalReferences = cssUrlReferences(css).filter(::isLocalReference).toSortedSet()
        val sourceAssetReferences = sourceAssetReferences(sourceJson)
        val expectedLocalReferences = sortedSetOf(
            "style.css",
            "screenshots/wasmtime-live-debugger.png",
            "screenshots/managed-wasi-sdk.png",
            "screenshots/wat-component-authoring.png",
        )

        assertEquals(expectedLocalReferences, htmlLocalReferences)
        assertTrue(cssLocalReferences.isEmpty())
        assertTrue(manifestEntries.containsAll(htmlLocalReferences + cssLocalReferences + sourceAssetReferences))
        assertTrue(html.contains("<h1>What's New in Hexana 0.18</h1>"))
        assertTrue(html.contains("<h2>Debug Wasmtime one WASM instruction at a time</h2>"))
        assertTrue(html.contains("<h2>Install and select wasi-sdk from the IDE</h2>"))
        assertTrue(html.contains("<h2>Navigate Component Model WAT with confidence</h2>"))
        assertTrue(html.contains("<h2>Also in 0.18</h2>"))
        assertTrue(html.contains("<strong>Bring the Wasm debugger tab to WAMR</strong>"))
        assertTrue(sourceJson.contains("\"version\": \"0.18\""))
        assertTrue(sourceJson.contains("explicitly use the selected SDK's LLDB"))

        val imageTags = Regex("""<img\b[^>]*>""").findAll(html).map { it.value }.toList()
        assertEquals(3, imageTags.size)
        imageTags.forEach { imageTag ->
            assertTrue("Missing image alt text: $imageTag", Regex("""\balt="[^"]*\S[^"]*"""").containsMatchIn(imageTag))
        }
    }

    fun testGeneratedWhatsNew019StaticResourceContract() {
        val root = "whatsnew/0.19"
        val html = resourceText("$root/index.html")
        val css = resourceText("$root/style.css")
        val sourceJson = resourceText("$root/source.json")
        val manifestLines = resourceText("$root/manifest.txt")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val manifestEntries = manifestLines.toSet()

        assertEquals(manifestLines.size, manifestEntries.size)
        for (entry in manifestEntries) {
            assertResourceExists("$root/$entry")
        }

        val htmlLocalReferences = htmlAttributeReferences(html).filter(::isLocalReference).toSortedSet()
        val cssLocalReferences = cssUrlReferences(css).filter(::isLocalReference).toSortedSet()
        val sourceAssetReferences = sourceAssetReferences(sourceJson)
        val expectedLocalReferences = sortedSetOf(
            "style.css",
            "screenshots/component-model-debugger.gif",
            "screenshots/zephyr-cortex-m-firmware.png",
        )

        assertEquals(expectedLocalReferences, htmlLocalReferences)
        assertTrue(cssLocalReferences.isEmpty())
        assertTrue(manifestEntries.containsAll(htmlLocalReferences + cssLocalReferences + sourceAssetReferences))
        assertTrue(html.contains("<h1>What's New in Hexana 0.19</h1>"))
        assertTrue(html.contains("<h2>Inspect Zephyr firmware with confidence</h2>"))
        assertTrue(html.contains("<h2>Follow Component Model modules in the debugger</h2>"))
        assertTrue(html.contains("GCC source locations"))
        assertTrue(sourceJson.contains("\"version\": \"0.19\""))
        assertTrue(sourceJson.contains("Cortex-M firmware ELFs"))
        assertTrue(sourceJson.contains("component-model binaries"))

        val imageTags = Regex("""<img\b[^>]*>""").findAll(html).map { it.value }.toList()
        assertEquals(2, imageTags.size)
        imageTags.forEach { imageTag ->
            assertTrue(
                "Missing image alt text: $imageTag",
                Regex("""\balt="[^"]*\S[^"]*""").containsMatchIn(imageTag),
            )
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

    fun testGeneratedWhatsNew013StaticResourceContract() {
        val root = "whatsnew/0.13"
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
            "screenshots/columnar-data.png",
            "screenshots/protobuf-inspection.png",
            "screenshots/structure-tool-window.png",
            "screenshots/scriptable-tabs.png",
        )

        assertEquals(expectedLocalReferences, htmlLocalReferences)
        assertTrue(cssLocalReferences.isEmpty())
        assertTrue(manifestEntries.containsAll(htmlLocalReferences + cssLocalReferences + sourceAssetReferences))

        for (heading in requiredGenerated013Headings()) {
            assertTrue("Missing heading: $heading", html.contains(heading))
        }
        assertTrue(html.contains("""href="https://plugins.jetbrains.com/plugin/29090-hexana--webassembly--binary-inspector">Open release notes</a>"""))
        assertTrue(html.contains("""href="https://github.com/JetBrains/hexana">Open GitHub</a>"""))
        assertTrue(sourceJson.contains(""""version": "0.13""""))
        assertTrue(sourceJson.contains(""""changelog": "idea-plugin/CHANGELOG.md""""))

        val imageTags = Regex("""<img\b[^>]*>""").findAll(html).map { it.value }.toList()
        assertEquals(4, imageTags.size)
        for (imageTag in imageTags) {
            assertTrue("Missing image alt text: $imageTag", Regex("""\balt="[^"]*\S[^"]*"""").containsMatchIn(imageTag))
        }

        val listItems = Regex("""<li>.*?</li>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.value }.toList()
        assertTrue(listItems.isNotEmpty())
        for (listItem in listItems) {
            assertFalse("Raw bold marker leaked into generated HTML: $listItem", listItem.contains("**"))
            assertFalse("Raw code marker leaked into generated HTML: $listItem", listItem.contains("`"))
        }
        assertTrue(html.contains("<code>PAR1</code>"))
        assertTrue(html.contains("<code>ARROW1</code>"))
        assertTrue(html.contains("<strong>Open As Protobuf Message</strong>"))
        assertTrue(html.contains("<strong>Run Export</strong>"))
        assertTrue(html.contains("<strong>Run with Wazero and search tables consistently</strong>"))
        assertFalse(html.contains("0xFFFFFFFF"))
        assertFalse(sourceJson.contains("0xFFFFFFFF"))
        assertFalse(html.contains("Arrow IPC stream"))
        assertFalse(sourceJson.contains("Arrow IPC stream"))
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
    )

    private fun requiredGenerated013Headings(): List<String> = listOf(
        "<h1>What's New in Hexana 0.13</h1>",
        "<h2>Inspect Parquet and Arrow data in Hexana</h2>",
        "<h2>Browse Protobuf schemas and raw wire messages</h2>",
        "<h2>Navigate binaries from the IDE Structure tool window</h2>",
        "<h2>Add custom scriptable analysis tabs</h2>",
        "<h2>Also in 0.13</h2>",
    )
}
