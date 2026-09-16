package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.closeAllNotifications
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.ui
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.HexanaEditorSnapshot
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

/**
 * Captures two dark-theme screenshots showing binary-view breakpoint gutter markers:
 *
 *  1. binary-view-breakpoints-elf.png — Zephyr ELF in the Disassembly (virtualized) tab,
 *     breakpoint set on the first instruction of bt_hrs_notify_rate (or main as
 *     fallback).
 *  2. binary-view-breakpoints.png — hello.wasm in the WAT (virtualized) tab, breakpoint
 *     set on the first instruction of the first defined function.
 *
 * Screenshots land in docs/whatsnew/captures/0.20 unless HEXANA_CORPUS_SCREENSHOT_DIR overrides it.
 */
@Tag("screenshot")
class BreakpointCaptureTest {

    private companion object {
        const val DISASM_TAB = "Disassembly (virtualized)"
        const val WAT_TAB = "WAT (virtualized)"
        const val SEARCH_PLACEHOLDER = "Search symbol, string, or hex offset (0x…)"

        val elfPath: Path = HexanaUiDriverTestSupport.corpusElf(
            "apps/peripheral_hr/nrf52840dk-Os-abi-drift/zephyr.elf",
        )
        val wasmPath: Path =
            Path.of("testData", "projects", HexanaUiDriverTestSupport.projectName, "hello.wasm")
                .toAbsolutePath().normalize()
    }

    @Test
    fun captureBreakpointShots() {
        require(elfPath.exists()) { "Missing ELF corpus: $elfPath" }
        require(wasmPath.exists()) { "Missing wasm fixture: $wasmPath" }

        val screenshotDir = resolveScreenshotDir()
        val elfTarget = screenshotDir.resolve("binary-view-breakpoints-elf.png")
        val watTarget = screenshotDir.resolve("binary-view-breakpoints.png")

        quitSystemSettingsQuietly()
        hexanaIde(
            testName = "breakpoint-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            runCatching { driver.closeAndDisableAllBalloonNotifications() }
                .onFailure { logOutput("[bp-capture] balloon disable failed: ${it.message}") }

            // ---- (1) ELF: Disassembly (virtualized) tab + breakpoint ---------
            closeAllEditors()
            Thread.sleep(500)
            runCatching {
                val elfEditor = openAbsoluteFile(elfPath)
                val elfSnapshot = waitForPanelsResolved(elfEditor, 90.seconds)
                logOutput("[bp-capture] ELF panels: ${elfSnapshot.panelNames}")

                val disasmTab = elfSnapshot.panelNames.firstOrNull { it == DISASM_TAB }
                    ?: elfSnapshot.panelNames.firstOrNull { it == "Disassembly" }
                if (disasmTab == null) {
                    logOutput("[bp-capture] ELF: no Disassembly tab; panels=${elfSnapshot.panelNames}")
                } else {
                    elfEditor.selectTab(disasmTab)
                    waitForSelectedTab(elfEditor, disasmTab)
                    Thread.sleep(3_000) // allow Capstone + search field to compose

                    // Wait for the editor's identity + GutterMarksModel wiring (async
                    // in initPanel) BEFORE toggling, otherwise the toggle races it.
                    waitForGutterCondition(elfEditor, 30.seconds, "modelReady=true") { p ->
                        p.any { it == "modelReady=true" }
                    }

                    // Navigate to bt_hrs_notify_rate or fall back to main
                    val query = run {
                        val probe = runCatching {
                            elfEditor.nativeDisassemblyInstructions("bt_hrs_notify_rate", 4)
                        }.getOrNull()
                        val resultIdx = probe?.firstOrNull { it.startsWith("resultIndex=") }
                            ?.substringAfter("resultIndex=")?.toIntOrNull() ?: -1
                        if (resultIdx >= 0) "bt_hrs_notify_rate" else "main"
                    }
                    logOutput("[bp-capture] ELF disasm query: $query")

                    // Toggle a breakpoint on the first instruction of the found symbol
                    val bpResult = runCatching {
                        elfEditor.toggleBinaryBreakpointOnFirstInstruction(query)
                    }
                    val bpPayload = bpResult.getOrNull()
                    logOutput("[bp-capture] ELF bp toggle: ${bpPayload ?: bpResult.exceptionOrNull()?.message}")

                    // Gate on the mark landing in GutterMarksModel.marks — the exact
                    // map the gutter renders from — instead of sleeping and hoping.
                    val elfVa = bpPayload?.firstOrNull { it.startsWith("va=0x") }
                        ?.substringAfter("va=0x")
                    if (elfVa != null) {
                        waitForGutterCondition(elfEditor, 15.seconds, "markKey=0x$elfVa") { p ->
                            p.any { it == "markKey=0x$elfVa" }
                        }
                    }
                    Thread.sleep(1_000)

                    // Search + navigate to the symbol so it's visible in frame
                    val probe = elfEditor.nativeDisassemblyInstructions(query, 4)
                    val resultIndex = probe.firstOrNull { it.startsWith("resultIndex=") }
                        ?.substringAfter("resultIndex=")?.toIntOrNull() ?: -1
                    logOutput("[bp-capture] ELF nav: resultIndex=$resultIndex, query=$query")
                    if (resultIndex >= 0 && context.ide.isMajorBuildVersionAtLeast(261)) {
                        setClipboardText(query)
                        driver.withContext {
                            val field = ui.x { byText(SEARCH_PLACEHOLDER) }.waitFound(30.seconds)
                            field.click()
                            ui.keyboard {
                                hotKey(java.awt.event.KeyEvent.VK_META, java.awt.event.KeyEvent.VK_V)
                            }
                            Thread.sleep(1_500)
                            ui.keyboard {
                                repeat(resultIndex) { down() }
                                enter()
                            }
                        }
                        Thread.sleep(10_000) // wait for async decode + gutter render + recompose
                    } else {
                        logOutput("[bp-capture] ELF nav skipped: resultIndex=$resultIndex")
                        Thread.sleep(6_000)
                    }

                    // Final read-back just before the screenshot so the log records
                    // exactly what the gutter had at capture time.
                    runCatching { elfEditor.binaryGutterMarks() }
                        .onSuccess { logOutput("[bp-capture] ELF marks@shot: $it") }
                        .onFailure { logOutput("[bp-capture] ELF marks@shot failed: ${it.message}") }

                    quitSystemSettingsQuietly()
                    runCatching { driver.closeAllNotifications() }
                    Thread.sleep(750)
                    copyScreenshot(driver, "breakpoint-disasm-elf", elfTarget)
                    logOutput("[bp-capture] ELF screenshot: $elfTarget")

                }
            }.onFailure { e -> logOutput("[bp-capture] ELF step failed: ${e.message}\n${e.stackTraceToString().take(800)}") }

            // ---- (2) WASM: WAT (virtualized) tab + breakpoint ----------------
            // Skippable so a re-run for the ELF shot doesn't overwrite an
            // already-accepted WAT screenshot.
            if (System.getenv("HEXANA_BP_SKIP_WASM") == "1") {
                logOutput("[bp-capture] WASM step skipped (HEXANA_BP_SKIP_WASM=1)")
                return@hexanaIde
            }
            closeAllEditors()
            Thread.sleep(500)
            runCatching {
                val watEditor = openAbsoluteFile(wasmPath)
                val watSnapshot = waitForPanelsResolved(watEditor, 90.seconds)
                logOutput("[bp-capture] WASM panels: ${watSnapshot.panelNames}")

                val watTab = watSnapshot.panelNames.firstOrNull { it == WAT_TAB }
                    ?: watSnapshot.panelNames.firstOrNull { it.startsWith("WAT") }
                if (watTab == null) {
                    logOutput("[bp-capture] WASM: no WAT (virtualized) tab; panels=${watSnapshot.panelNames}")
                } else {
                    watEditor.selectTab(watTab)
                    waitForSelectedTab(watEditor, watTab)
                    Thread.sleep(4_000) // allow WAT conversion + compose

                    // Toggle breakpoint on the first defined function
                    val bpResult = runCatching {
                        watEditor.toggleBinaryBreakpointOnFirstInstruction("")
                    }
                    val watBpPayload = bpResult.getOrNull()
                    logOutput("[bp-capture] WASM bp toggle: ${watBpPayload ?: bpResult.exceptionOrNull()?.message}")

                    // Navigate the WAT view to the function's byte offset so the breakpoint row is in frame
                    val watByteOffset = watBpPayload
                        ?.firstOrNull { it.startsWith("byteOffset=0x") }
                        ?.substringAfter("byteOffset=0x")
                        ?.removePrefix("0x")
                        ?.toLongOrNull(16)
                        ?.toInt()
                    if (watByteOffset != null && watByteOffset > 0) {
                        waitForGutterCondition(watEditor, 15.seconds, "markKey=0x${watByteOffset.toString(16)}") { p ->
                            p.any { it == "markKey=0x${watByteOffset.toString(16)}" }
                        }
                        runCatching { watEditor.navigateWatToByteOffset(watByteOffset) }
                            .onSuccess { logOutput("[bp-capture] WAT nav: offset=0x${watByteOffset.toString(16)}") }
                            .onFailure { logOutput("[bp-capture] WAT nav failed: ${it.message}") }
                    } else {
                        error("WASM breakpoint capture did not return a valid byte offset: $watBpPayload")
                    }
                    Thread.sleep(10_000) // let WAT view scroll + GutterMarksModel emit + Compose recompose

                    quitSystemSettingsQuietly()
                    runCatching { driver.closeAllNotifications() }
                    Thread.sleep(750)
                    copyScreenshot(driver, "breakpoint-wat-wasm", watTarget)
                    logOutput("[bp-capture] WASM screenshot: $watTarget")
                }
            }.onFailure { e -> logOutput("[bp-capture] WASM step failed: ${e.message}\n${e.stackTraceToString().take(800)}") }
        }

        // Verify screenshots were produced
        check(elfTarget.exists()) { "ELF screenshot not produced: $elfTarget" }
        check(watTarget.exists()) { "WASM screenshot not produced: $watTarget" }
        logOutput("[bp-capture] done: $elfTarget, $watTarget")
    }

    // ---- helpers ---------------------------------------------------------------

    private fun resolveScreenshotDir(): Path {
        val override = System.getenv("HEXANA_CORPUS_SCREENSHOT_DIR")?.trim()?.ifBlank { null }
        val dir = override?.let { Path.of(it) }
            ?: repoRoot().resolve("docs", "whatsnew", "captures", "0.20")
        return dir.toAbsolutePath().normalize().createDirectories()
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) path = path.parent ?: error("Could not locate repository root")
        return path
    }

    /**
     * Polls [HexanaEditor.binaryGutterMarks] until [condition] holds, logging the
     * payload each second. Logs (does not fail) on timeout so the screenshot is
     * still taken with full diagnostic context in the test log.
     */
    private fun waitForGutterCondition(
        editor: HexanaEditor,
        timeout: kotlin.time.Duration,
        label: String,
        condition: (List<String>) -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        var last: List<String> = emptyList()
        while (System.nanoTime() < deadline) {
            last = runCatching { editor.binaryGutterMarks() }.getOrDefault(emptyList())
            if (condition(last)) {
                logOutput("[bp-capture] gutter condition '$label' met: $last")
                return
            }
            Thread.sleep(1_000)
        }
        logOutput("[bp-capture] gutter condition '$label' TIMED OUT; last: $last")
    }

    private fun waitForPanelsResolved(
        editor: HexanaEditor,
        timeout: kotlin.time.Duration,
    ): HexanaEditorSnapshot {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        var last = editor.snapshot()
        while (System.nanoTime() < deadline) {
            last = editor.snapshot()
            if (last.panelsResolved) return last
            Thread.sleep(250)
        }
        error("Timed out waiting for panelsResolved; last: $last")
    }

    private fun waitForSelectedTab(editor: HexanaEditor, tabName: String) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var last = editor.snapshot()
        while (System.nanoTime() < deadline) {
            last = editor.snapshot()
            if (last.selectedTabName == tabName) return
            Thread.sleep(250)
        }
        error("Timed out waiting for selected tab '$tabName'; last: $last")
    }

    private fun copyScreenshot(driver: Driver, name: String, target: Path) {
        runCatching { driver.closeAllNotifications() }
        Thread.sleep(750)
        val screenshot = driver.takeScreenshot(name) ?: run {
            logOutput("[bp-capture] WARNING: null screenshot for '$name'")
            return
        }
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        if (!source.exists()) {
            logOutput("[bp-capture] WARNING: screenshot path missing: $source")
            return
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("[bp-capture] saved: $target")
    }

    private fun quitSystemSettingsQuietly() {
        runCatching {
            ProcessBuilder("osascript", "-e", "tell application \"System Settings\" to quit")
                .start().waitFor()
        }
    }
}
