package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.closeToolWindow
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.structureToolWindow
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNew020StructuralRegionsCaptureTest {
    @Test
    fun captureStructuralRegionsInZip() {
        val fixture = Files.createTempFile("hexana-whatsnew-020-structure-", ".zip")
        ZipOutputStream(Files.newOutputStream(fixture)).use { zip ->
            listOf(
                "META-INF/manifest.json" to "{\"release\":\"0.20\"}",
                "assets/wasm/module.wasm" to "WebAssembly payload",
                "reports/compatibility.txt" to "Chrome 60: unsupported",
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }

        hexanaIde(
            testName = "hexana-whats-new-020-structural-regions-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            driver.closeToolWindow("Services")
            closeStructureToolWindow()
            val editor = openAbsoluteFile(fixture)
            waitForArchiveRegions(editor)
            copyScreenshot(driver, "structural-regions-zip", "structural-regions-zip.png")
        }
    }

    private fun waitForArchiveRegions(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 90.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        var lastArchive = editor.archiveState()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            lastArchive = editor.archiveState()
            if (
                lastSnapshot.panelsResolved &&
                lastArchive.memberCount == 3 &&
                lastArchive.members.map { it.name } == listOf(
                    "META-INF/manifest.json",
                    "assets/wasm/module.wasm",
                    "reports/compatibility.txt",
                )
            ) return
            Thread.sleep(250)
        }
        error("Timed out waiting for ZIP structural regions; snapshot=$lastSnapshot archive=$lastArchive")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val target = repoRoot().resolve("docs/whatsnew/captures/0.20").createDirectories().resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.20 structural regions screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) path = path.parent ?: error("Could not locate repository root")
        return path
    }
}
