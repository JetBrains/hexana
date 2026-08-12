package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.ui
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaCdpDebuggerCaptureState
import org.jetbrains.hexana.ui.framework.cdpDebuggerCaptureState
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.withWasmRunDsl
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes

@Tag("screenshot")
class HexanaWhatsNew017WasmDebuggerCaptureTest {
    @Test
    fun capturePausedNodeJsWasmDebuggerTab() {
        hexanaIde(
            testName = "hexana-whats-new-017-wasm-debugger-tab-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            withWasmRunDsl(DEBUG_WASM_FILE) {
                try {
                    startNodeJsDebugWithBreakpointForCapture(
                        wasmFileName = DEBUG_WASM_FILE,
                        sourceFileName = DEBUG_SOURCE_FILE,
                        breakpointLine = BREAKPOINT_LINE,
                        exportName = "add",
                        programArgs = "3 7",
                    )
                    waitForDebuggerCaptureState()
                    driver.withContext {
                        ui.x { and(byClass("SingleHeightLabel"), byAccessibleName("Wasm")) }.waitFound(2.minutes).click()
                    }
                    copyScreenshot(driver, "wasm-debugger-tab", "wasm-debugger-tab.png")
                } finally {
                    resumeDebuggerForCapture()
                }
            }
        }
    }

    private fun org.jetbrains.hexana.ui.framework.HexanaWasmRunDsl.waitForDebuggerCaptureState() {
        val deadline = System.nanoTime() + 2.minutes.inWholeNanoseconds
        var lastState: HexanaCdpDebuggerCaptureState? = null
        while (System.nanoTime() < deadline) {
            lastState = cdpDebuggerCaptureState()
            if (
                lastState.sessionSuspended &&
                lastState.wasmFrame &&
                lastState.wasmTabRegistered &&
                lastState.disassemblyRowCount > 0 &&
                lastState.selectedDisassemblyRow >= 0
            ) {
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for paused Wasm debugger tab state: $lastState")
    }

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

    private companion object {
        const val DEBUG_WASM_FILE = "debug-add.wasm"
        const val DEBUG_SOURCE_FILE = "debug_add.rs"
        const val BREAKPOINT_LINE = 10
    }
}
