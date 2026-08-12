package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.ui
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.HexanaProviderKind
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.minutes

@Tag("screenshot")
class HexanaWhatsNew017ProblemsCaptureTest {
    @Test
    fun captureWasmProblemsTab() {
        val fixture = Files.createTempFile("hexana-whatsnew-017-problems-", ".wasm").also {
            it.writeBytes(threeFunctionUnknownOpcodeWasm())
        }
        hexanaIde(
            testName = "hexana-whats-new-017-problems-tab-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            val editor = openAbsoluteFile(fixture)
            waitForProblemsTab(editor)
            editor.selectTab("Problems")
            waitForSelectedProblemsTab(editor)
            driver.withContext {
                ui.x {
                    byText(
                        "0 || Unknown opcode || func 0 || 0x1B || 0x3 || " +
                            "Unsupported opcode: 00000032 @0x0000001b",
                    )
                }.waitFound(2.minutes)
            }
            copyScreenshot(driver, "wasm-problems-tab", "wasm-problems-tab.png")
        }
    }

    private fun waitForProblemsTab(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 2.minutes.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (
                lastSnapshot.providerKind == HexanaProviderKind.CORE_WASM.id &&
                lastSnapshot.panelsResolved &&
                "Problems" in lastSnapshot.panelNames
            ) return
            Thread.sleep(250)
        }
        error("Timed out waiting for Problems tab: $lastSnapshot")
    }

    private fun waitForSelectedProblemsTab(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 30_000_000_000L
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.selectedTabName == "Problems") return
            Thread.sleep(250)
        }
        error("Timed out waiting for selected Problems tab: $lastSnapshot")
    }

    private fun threeFunctionUnknownOpcodeWasm(): ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x04, 0x03, 0x00, 0x00, 0x00,
        0x0A, 0x16, 0x03,
        0x06, 0x00, 0x41, 0x00, 0xFB.toByte(), 0x32, 0x0B,
        0x06, 0x00, 0x41, 0x01, 0xFB.toByte(), 0x32, 0x0B,
        0x06, 0x00, 0x41, 0x02, 0xFB.toByte(), 0x32, 0x0B,
    )

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val target = repoRoot().resolve("docs/whatsnew/captures/0.17").createDirectories().resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.17 screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) path = path.parent ?: error("Could not locate repository root")
        return path
    }
}
