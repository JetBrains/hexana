package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.closeAllNotifications
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.ui
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.HexanaNativeDiffState
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.menuShortcutModifier
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Produces ABI-drift demo screenshots from the nrf52840dk peripheral_hr
 * firmware variants in the Zephyr corpus:
 *   - Variant A: apps/peripheral_hr/nrf52840dk-Os/zephyr.elf  (baseline)
 *   - Variant B: apps/peripheral_hr/nrf52840dk-Os-abi-drift/zephyr.elf
 *     (bt_hrs_notify renamed, new exports bas_battery_notify / hrs_notify /
 *      fw_abi_probe_new_export, new 512-byte BSS buffer hr_event_log)
 *
 * Screenshots land in the directory given by HEXANA_CORPUS_SCREENSHOT_DIR (or
 * ui-driver-tests/build/corpus-screenshots if that env is not set):
 *   abi-diff-peripheral_hr-Os-vs-abi-drift.png
 *   abi-drift-sections.png
 *   abi-drift-top-treemap.png
 *   abi-drift-symbols-new-exports.png
 *   abi-drift-disasm-main-thumb2.png
 *   abi-drift-debug-info-lines.png
 *
 * Diff numbers (status, changedRowCount, per-section deltas) are written to
 * hexana-abi-drift.json in the system temp directory, alongside the diff
 * observations JSON.
 */
class AbiDriftCaptureTest {

    private companion object {
        const val DISASM_TAB = "Disassembly (virtualized)"
        const val SEARCH_PLACEHOLDER = "Search symbol, string, or hex offset (0x…)"

        val elfA: Path = HexanaUiDriverTestSupport.corpusElf("apps/peripheral_hr/nrf52840dk-Os/zephyr.elf")
        val elfB: Path = HexanaUiDriverTestSupport.corpusElf("apps/peripheral_hr/nrf52840dk-Os-abi-drift/zephyr.elf")
    }

    private data class DiffResult(
        val status: String,
        val changedRowCount: Int,
        val sections: List<String>,
        val error: String?,
    )

    @Test
    fun abiDriftPack() {
        require(elfA.exists()) { "Missing corpus artifact: $elfA" }
        require(elfB.exists()) { "Missing corpus artifact: $elfB" }

        val screenshotDir = corpusScreenshotDir()
        var diffResult: DiffResult? = null
        val screenshotsTaken = mutableListOf<String>()

        quitSystemSettingsQuietly()
        hexanaIde(
            testName = "abi-drift-pack",
            configure = {
                showWhatsNewOnNewVersion(false)
                patchContext { context ->
                    val optionsDir = context.paths.configDir.resolve("options").createDirectories()
                    Files.writeString(
                        optionsDir.resolve("laf.xml"),
                        """
                        <application>
                          <component name="LafManager" autodetect="false">
                            <laf themeId="ExperimentalLight" />
                          </component>
                        </application>
                        """.trimIndent()
                    )
                    context
                }
            },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            // Balloon notifications (e.g. a bundled plugin's "respond error") overlap
            // the bottom-right of whole-screen captures; disable them for the session
            // and close any that already appeared.
            runCatching { driver.closeAndDisableAllBalloonNotifications() }
                .onFailure { logOutput("[abi-drift] balloon disable failed: ${it.message}") }

            // (a) Native diff A vs B -----------------------------------------------
            closeAllEditors()
            Thread.sleep(500)
            runCatching {
                val diffEditor = openNativeDiff(elfA, elfB)
                Thread.sleep(3_000)
                val diffState = diffEditor.nativeDiffState()
                diffResult = DiffResult(
                    status = diffState.status,
                    changedRowCount = diffState.changedRowCount,
                    sections = diffState.sections,
                    error = diffState.error,
                )
                logOutput("[abi-drift] diff: status=${diffState.status} " +
                    "changedRowCount=${diffState.changedRowCount} sections=${diffState.sections}")
                copyScreenshot(driver, "abi-diff-peripheral_hr-Os-vs-abi-drift",
                    screenshotDir.resolve("abi-diff-peripheral_hr-Os-vs-abi-drift.png"))
                screenshotsTaken += "abi-diff-peripheral_hr-Os-vs-abi-drift.png"
            }.onFailure { e -> logOutput("[abi-drift] diff step failed: ${e.message}") }

            // (b) Open variant B: sections, top, symbols tabs -----------------------
            closeAllEditors()
            Thread.sleep(500)
            val editorB = openAbsoluteFile(elfB)
            val snapshotB = waitForPanelsResolved(editorB, 90.seconds)
            logOutput("[abi-drift] variant B panels: ${snapshotB.panelNames}")

            // Sections tab. Shrink the hex pane so the sections table gets enough rows to
            // show the whole list (rom_start/sw_isr_table/device_area through bss/noinit).
            runCatching {
                if ("Sections" in snapshotB.panelNames) {
                    editorB.selectTab("Sections")
                    waitForSelectedTab(editorB, "Sections")
                    editorB.setTopFraction(0.12f)
                    Thread.sleep(1_000)
                    copyScreenshot(driver, "abi-drift-sections",
                        screenshotDir.resolve("abi-drift-sections.png"))
                    screenshotsTaken += "abi-drift-sections.png"
                } else {
                    logOutput("[abi-drift] no Sections tab in: ${snapshotB.panelNames}")
                }
            }.onFailure { e -> logOutput("[abi-drift] sections tab failed: ${e.message}") }

            // Top tab (treemap)
            runCatching {
                if ("Top" in snapshotB.panelNames) {
                    editorB.selectTab("Top")
                    waitForSelectedTab(editorB, "Top")
                    copyScreenshot(driver, "abi-drift-top-treemap",
                        screenshotDir.resolve("abi-drift-top-treemap.png"))
                    screenshotsTaken += "abi-drift-top-treemap.png"
                } else {
                    logOutput("[abi-drift] no Top tab in: ${snapshotB.panelNames}")
                }
            }.onFailure { e -> logOutput("[abi-drift] top tab failed: ${e.message}") }

            // (c) Disassembly: navigate to bt_hrs_notify_rate via search, then fall back to main.
            // Must run BEFORE the symbols step so the search placeholder text is still visible
            // (the symbols step fills the field with "hrs_notify", removing the placeholder).
            runCatching {
                val disasmTab = snapshotB.panelNames.firstOrNull { it == DISASM_TAB }
                    ?: snapshotB.panelNames.firstOrNull { it == "Disassembly" }
                if (disasmTab != null) {
                    editorB.selectTab(disasmTab)
                    waitForSelectedTab(editorB, disasmTab)
                    Thread.sleep(3_000) // allow Capstone + search field to compose

                    // Try bt_hrs_notify_rate first (injected name); fall back to main
                    val searchQuery = run {
                        val probe = runCatching { editorB.nativeDisassemblyInstructions("bt_hrs_notify_rate", 4) }
                            .getOrNull()
                        if (probe != null && (probe.entryValue("resultIndex")?.toIntOrNull() ?: -1) >= 0) {
                            "bt_hrs_notify_rate"
                        } else {
                            "main"
                        }
                    }
                    logOutput("[abi-drift] disasm search query: $searchQuery")

                    val probe = editorB.nativeDisassemblyInstructions(searchQuery, 20)
                    val resultIndex = probe.entryValue("resultIndex")?.toIntOrNull() ?: -1

                    if (context.ide.isMajorBuildVersionAtLeast(261) && resultIndex >= 0) {
                        setClipboardText(searchQuery)
                        driver.withContext {
                            val field = ui.x { byText(SEARCH_PLACEHOLDER) }.waitFound(90.seconds)
                            field.click()
                            ui.keyboard {
                                hotKey(menuShortcutModifier, java.awt.event.KeyEvent.VK_V)
                            }
                            Thread.sleep(1_500)
                            ui.keyboard {
                                repeat(resultIndex) { down() }
                                enter()
                            }
                        }
                        // Wait for async decode and row composition.
                        Thread.sleep(6_000)
                    } else {
                        Thread.sleep(4_000)
                        logOutput("[abi-drift] disasm: skipped UI search (resultIndex=$resultIndex or IDE<261)")
                    }

                    quitSystemSettingsQuietly()
                    copyScreenshot(driver, "abi-drift-disasm-main-thumb2",
                        screenshotDir.resolve("abi-drift-disasm-main-thumb2.png"))
                    screenshotsTaken += "abi-drift-disasm-main-thumb2.png"
                    logOutput("[abi-drift] disasm capture done; resolvedName=${probe.entryValue("resolvedName")}")
                } else {
                    logOutput("[abi-drift] no Disassembly tab in: ${snapshotB.panelNames}")
                }
            }.onFailure { e -> logOutput("[abi-drift] disasm step failed: ${e.message}") }

            // Symbols (injected exports): use Disassembly (virtualized) tab + search for "hrs_notify".
            // NativeBinaryProvider has no standalone Symbols/Functions tab; the disassembly search
            // field is the correct way to filter to a named symbol. Runs AFTER the disasm step so
            // filling the field with "hrs_notify" does not break the disasm placeholder lookup above.
            // The Compose search field may retain the disasm query; if so, click it and clear with
            // Cmd+A + Delete before typing the new query via the placeholder.
            runCatching {
                val disasmSymTab = snapshotB.panelNames.firstOrNull { it == DISASM_TAB }
                    ?: snapshotB.panelNames.firstOrNull { it == "Disassembly" }
                if (disasmSymTab != null) {
                    editorB.selectTab(disasmSymTab)
                    waitForSelectedTab(editorB, disasmSymTab)
                    Thread.sleep(2_000) // allow panel to re-settle after tab re-selection
                    if (context.ide.isMajorBuildVersionAtLeast(261)) {
                        setClipboardText("hrs_notify")
                        driver.withContext {
                            // If the Compose search field still holds the disasm query (its text
                            // attribute shows the typed content, not the placeholder), clear it first.
                            // Try both known residual texts; if neither is found, the field is already
                            // showing the placeholder and we proceed directly.
                            val residualField = runCatching {
                                ui.x { byText("bt_hrs_notify_rate") }.waitFound(3.seconds)
                            }.getOrNull() ?: runCatching {
                                ui.x { byText("main") }.waitFound(1.seconds)
                            }.getOrNull()
                            if (residualField != null) {
                                residualField.click()
                                ui.keyboard {
                                    hotKey(menuShortcutModifier, java.awt.event.KeyEvent.VK_A)
                                    backspace()
                                }
                                Thread.sleep(500)
                            }
                            val field = ui.x { byText(SEARCH_PLACEHOLDER) }.waitFound(15.seconds)
                            field.click()
                            ui.keyboard {
                                hotKey(menuShortcutModifier, java.awt.event.KeyEvent.VK_V)
                            }
                            Thread.sleep(2_000)
                        }
                    }
                    copyScreenshot(driver, "abi-drift-symbols-new-exports",
                        screenshotDir.resolve("abi-drift-symbols-new-exports.png"))
                    screenshotsTaken += "abi-drift-symbols-new-exports.png"
                    logOutput("[abi-drift] symbols capture via Disassembly tab + search 'hrs_notify'")
                } else {
                    logOutput("[abi-drift] no Disassembly tab in: ${snapshotB.panelNames}")
                }
            }.onFailure { e -> logOutput("[abi-drift] symbols tab failed: ${e.message}") }

            // (d) Debug Info tab (line-table fallback for file:line navigation) -----
            runCatching {
                val debugInfoTab = snapshotB.panelNames.firstOrNull { it == "Debug Info" || it.startsWith("DWARF") }
                if (debugInfoTab != null) {
                    editorB.selectTab(debugInfoTab)
                    waitForSelectedTab(editorB, debugInfoTab)
                    Thread.sleep(1_000)
                    copyScreenshot(driver, "abi-drift-debug-info-lines",
                        screenshotDir.resolve("abi-drift-debug-info-lines.png"))
                    screenshotsTaken += "abi-drift-debug-info-lines.png"
                    logOutput("[abi-drift] debug info captured (Debug Info / DWARF tab)")
                } else {
                    logOutput("[abi-drift] no Debug Info / DWARF tab in: ${snapshotB.panelNames}")
                }
            }.onFailure { e -> logOutput("[abi-drift] debug info tab failed: ${e.message}") }

            // Write observations JSON
            writeDriftJson(diffResult, screenshotsTaken)
        }

        // Post-run assertions
        val diff = diffResult
        check(diff != null) { "Native diff step did not complete; diff result is null" }
        check(diff.error == null) { "Native diff returned an error: ${diff.error}" }
        check(screenshotsTaken.isNotEmpty()) { "No screenshots were taken during the test" }
        logOutput("[abi-drift] done; screenshots: $screenshotsTaken")
        logOutput("[abi-drift] diff: status=${diff.status} changedRowCount=${diff.changedRowCount}")
        logOutput("[abi-drift] diff sections: ${diff.sections}")
    }

    // ---- helpers ----------------------------------------------------------------

    private fun corpusScreenshotDir(): Path {
        val override = System.getenv("HEXANA_CORPUS_SCREENSHOT_DIR")?.trim()?.ifBlank { null }
        val dir = override?.let { Path.of(it) }
            ?: Path.of("build", "corpus-screenshots")
        return dir.toAbsolutePath().normalize().createDirectories()
    }

    private fun waitForPanelsResolved(
        editor: HexanaEditor,
        timeout: kotlin.time.Duration,
    ): org.jetbrains.hexana.ui.framework.HexanaEditorSnapshot {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.panelsResolved) return lastSnapshot
            Thread.sleep(250)
        }
        error("Timed out waiting for panelsResolved; last snapshot: $lastSnapshot")
    }

    private fun waitForSelectedTab(editor: HexanaEditor, tabName: String) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.selectedTabName == tabName) return
            Thread.sleep(250)
        }
        error("Timed out waiting for selected tab '$tabName'; snapshot: $lastSnapshot")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, target: Path) {
        // Defensive per-capture guard: close any balloon that slipped past the
        // session-wide disable, and let the fade-out animation finish.
        runCatching { driver.closeAllNotifications() }
        Thread.sleep(750)
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: run {
                logOutput("WARNING: driver returned null screenshot for '$screenshotName'")
                return
            }
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        if (!source.exists()) {
            logOutput("WARNING: screenshot path does not exist: $source")
            return
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Screenshot saved: $target")
    }

    private fun quitSystemSettingsQuietly() {
        runCatching {
            ProcessBuilder("osascript", "-e", "tell application \"System Settings\" to quit")
                .start()
                .waitFor()
        }
    }

    private fun writeDriftJson(diff: DiffResult?, screenshots: List<String>) {
        val jsonPath = Path.of(System.getProperty("java.io.tmpdir"), "hexana-abi-drift.json")
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"generatedAt\": \"${java.time.Instant.now()}\",")
        sb.appendLine("  \"diff\": {")
        if (diff != null) {
            sb.appendLine("    \"status\": ${diff.status.toJsonString()},")
            sb.appendLine("    \"changedRowCount\": ${diff.changedRowCount},")
            sb.appendLine("    \"sections\": ${diff.sections.toJsonArray()},")
            sb.append("    \"error\": ${diff.error?.toJsonString() ?: "null"}")
        } else {
            sb.append("    \"error\": \"diff step did not complete\"")
        }
        sb.appendLine()
        sb.appendLine("  },")
        sb.appendLine("  \"screenshots\": ${screenshots.toJsonArray()}")
        sb.appendLine("}")
        Files.writeString(jsonPath, sb.toString())
        logOutput("ABI drift observations written to $jsonPath")
    }

    private fun List<String>.entryValue(key: String): String? =
        firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    private fun String.toJsonString(): String {
        val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun List<String>.toJsonArray(): String {
        if (isEmpty()) return "[]"
        return "[${joinToString(", ") { it.toJsonString() }}]"
    }
}
