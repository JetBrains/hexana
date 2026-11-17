package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNew020RuntimeCompatibilityCaptureTest {
    @Test
    fun captureRuntimeCompatibility() {
        val project = Files.createTempDirectory("hexana-whatsnew-020-runtime-compatibility-")
        val wasm = project.resolve("runtime-compatibility.wasm")
        wasm.writeBytes(bulkMemoryWasm())
        project.resolve(".hexana").createDirectories().resolve("runtimes.json").writeText(
            """
            {
              "version": 1,
              "targets": [
                { "runtime": "Chrome", "version": "60" }
              ]
            }
            """.trimIndent(),
        )

        hexanaIde(
            testName = "hexana-whats-new-020-runtime-compatibility-capture",
            projectDir = project,
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            val editor = openFile(wasm.fileName.toString())
            waitForCompatibility(editor)
            editor.selectTab("Compatibility")
            waitForSelectedCompatibility(editor)
            copyScreenshot(driver, "wasm-runtime-compatibility", "wasm-runtime-compatibility.png")
        }
    }

    private fun waitForCompatibility(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 90.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        var lastTable = editor.tableTabState("Compatibility")
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            lastTable = editor.tableTabState("Compatibility")
            if (
                lastSnapshot.panelsResolved &&
                "Compatibility" in lastSnapshot.panelNames &&
                lastTable.resolved &&
                lastTable.columns == listOf("Proposal", "Phase", "Chrome 60", "Other runtimes") &&
                lastTable.prologue.contains("not supported by a configured runtime")
            ) return
            Thread.sleep(250)
        }
        error("Timed out waiting for the Runtime Compatibility tab; snapshot=$lastSnapshot table=$lastTable")
    }

    private fun waitForSelectedCompatibility(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var last = editor.snapshot()
        while (System.nanoTime() < deadline) {
            last = editor.snapshot()
            if (last.selectedTabName == "Compatibility") return
            Thread.sleep(250)
        }
        error("Timed out waiting for the selected Compatibility tab; snapshot=$last")
    }

    private fun bulkMemoryWasm(): ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x0a, 0x0e, 0x01, 0x0c, 0x00,
        0x41, 0x00, 0x41, 0x00, 0x41, 0x00,
        0xfc.toByte(), 0x0a, 0x00, 0x00, 0x0b,
    )

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val target = repoRoot().resolve("docs/whatsnew/captures/0.20").createDirectories().resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.20 Runtime Compatibility screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) path = path.parent ?: error("Could not locate repository root")
        return path
    }
}
