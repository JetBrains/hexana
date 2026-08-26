package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.closeToolWindow
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.ui
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNew019ZephyrCaptureTest {
    @Test
    fun captureZephyrCortexMFirmwareDisassembly() {
        hexanaIde(
            testName = "hexana-whats-new-019-zephyr-cortex-m-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            driver.closeToolWindow("Services")
            val editor = openAbsoluteFile(
                HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nrf52840dk-Os/zephyr.elf"),
            )
            waitForPanelsResolved(editor)
            verifyThumbDecode(editor)
            editor.selectTab(DISASSEMBLY_TAB)
            waitForSelectedTab(editor, DISASSEMBLY_TAB)
            drillIntoMain(editor)
            Thread.sleep(6_000)
            copyScreenshot(driver, "zephyr-cortex-m-firmware", "zephyr-cortex-m-firmware.png")
        }
    }

    private fun verifyThumbDecode(editor: HexanaEditor) {
        val summary = editor.nativeDisassemblySummary(100_000)
        val instructionCount = summary.entryValue("insnCount")?.toLongOrNull() ?: 0L
        val byteFallbackCount = summary.entryValue("byteFallbackCount")?.toLongOrNull() ?: Long.MAX_VALUE
        check(summary.entryValue("arch") == "THUMB") { "Expected THUMB decode, got $summary" }
        check(summary.entryValue("oddVaRangeCount") == "0") { "Expected even function addresses, got $summary" }
        check(instructionCount > 0) { "Expected decoded instructions, got $summary" }
        check(byteFallbackCount.toDouble() / instructionCount < 0.05) {
            "Thumb decode has too many byte fallbacks: $summary"
        }
    }

    private fun org.jetbrains.hexana.ui.framework.HexanaIde.drillIntoMain(editor: HexanaEditor) {
        val probe = editor.nativeDisassemblyInstructions("main", 12)
        check(probe.entryValue("resolvedName") == "main") { "Expected main, got $probe" }
        check(probe.entryValue("virtualAddress") == "0x100") { "Expected main at 0x100, got $probe" }
        val instructions = probe.filter { it.startsWith("insn=") }
            .map { it.removePrefix("insn=").split('|', limit = 3) }
        check(instructions.map { it[0] }.take(7) == listOf("0x100", "0x102", "0x104", "0x106", "0x10a", "0x10c", "0x10e")) {
            "Unexpected main instruction addresses: $instructions"
        }
        check(
            instructions[0][1] == "push" && instructions[3][1].startsWith("bl") &&
                instructions[5][1] == "pop" && instructions[6][1] == "nop",
        ) { "Unexpected main instructions: $instructions" }
        val resultIndex = probe.entryValue("resultIndex")?.toIntOrNull()
            ?: error("Missing search result index: $probe")
        check(resultIndex >= 0) { "No search result for main: $probe" }
        setClipboardText("main")
        driver.withContext {
            val field = ui.x { byText(SEARCH_PLACEHOLDER) }.waitFound(90.seconds)
            field.click()
            ui.keyboard { hotKey(java.awt.event.KeyEvent.VK_META, java.awt.event.KeyEvent.VK_V) }
            Thread.sleep(1_500)
            ui.keyboard {
                repeat(resultIndex) { down() }
                enter()
            }
        }
    }

    private fun waitForPanelsResolved(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 90.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.panelsResolved && DISASSEMBLY_TAB in lastSnapshot.panelNames) return
            Thread.sleep(250)
        }
        error("Timed out waiting for native editor panels: $lastSnapshot")
    }

    private fun waitForSelectedTab(editor: HexanaEditor, tabName: String) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.selectedTabName == tabName) return
            Thread.sleep(250)
        }
        error("Timed out waiting for selected $tabName tab: $lastSnapshot")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val target = repoRoot().resolve("docs/whatsnew/captures/0.19").createDirectories().resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.19 screenshot: $target")
    }

    private fun List<String>.entryValue(key: String): String? =
        firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repository root")
        }
        return path
    }

    private companion object {
        const val DISASSEMBLY_TAB = "Disassembly (virtualized)"
        const val SEARCH_PLACEHOLDER = "Search symbol, string, or hex offset (0x\u2026)"
    }
}
