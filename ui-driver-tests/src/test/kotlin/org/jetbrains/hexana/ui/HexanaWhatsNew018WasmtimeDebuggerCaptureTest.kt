package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.ui
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaWasmtimeDebuggerCaptureState
import org.jetbrains.hexana.ui.framework.HexanaWasmRunDsl
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
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNew018WasmtimeDebuggerCaptureTest {
    @Test
    fun captureWasmtimeInstructionDebuggerWithLiveOperands() {
        hexanaIde(
            testName = "hexana-whats-new-018-wasmtime-live-debugger-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            withWasmRunDsl(DEBUG_WASM_FILE) {
                try {
                    startWasmtimeDebugWithBreakpointForCapture(
                        wasmFileName = DEBUG_WASM_FILE,
                        sourceFileName = DEBUG_SOURCE_FILE,
                        breakpointLine = BREAKPOINT_LINE,
                        wasmtimeOptions = "--invoke add",
                        programArgs = "3 7",
                    )
                    driver.withContext {
                        ui.x { and(byClass("SingleHeightLabel"), byAccessibleName("Wasm")) }
                            .waitFound(2.minutes)
                            .click()
                    }
                    waitForWasmTabSelection()
                    repeat(4) { stepIndex -> stepIntoInstruction(stepIndex + 1) }
                    val state = waitForExactCaptureState()
                    logOutput("What's New 0.18 Wasmtime semantic state: $state")
                    copyScreenshot(driver, "wasmtime-live-debugger", "wasmtime-live-debugger.png")
                } finally {
                    terminateDebuggerForCapture()
                }
            }
        }
    }

    private fun HexanaWasmRunDsl.waitForWasmTabSelection() {
        waitForState("selected visible Wasm tab") { state ->
            state.sessionSuspended && state.wasmTabAvailable && state.wasmTabSelected &&
                state.disassemblyRowCount > 0 && state.selectedDisassemblyRow >= 0
        }
    }

    private fun HexanaWasmRunDsl.stepIntoInstruction(stepNumber: Int) {
        val before = wasmtimeDebuggerCaptureState()
        stepIntoInstructionForCapture()
        waitForState("instruction step $stepNumber from offset ${before.selectedOffset}") { state ->
            state.sessionSuspended && state.wasmTabSelected && state.selectedOffset != null &&
                state.selectedOffset != before.selectedOffset
        }
    }

    private fun HexanaWasmRunDsl.waitForExactCaptureState(): HexanaWasmtimeDebuggerCaptureState =
        waitForState("i32.add at 0x9b with concrete 3 and 7 operands") { state ->
            val liveThree = state.operands.any { operand ->
                operand.type == "i32" && operand.value.matches(Regex("3 \\(.+\\)"))
            }
            val liveSeven = state.operands.any { operand ->
                operand.type == "i32" && operand.value.matches(Regex("7 \\(.+\\)"))
            }
            state.sessionSuspended && state.wasmTabSelected &&
                state.selectedOffset == 0x9b && state.selectedInstruction?.trim() == "i32.add" &&
                liveThree && liveSeven
        }

    private fun HexanaWasmRunDsl.waitForState(
        description: String,
        predicate: (HexanaWasmtimeDebuggerCaptureState) -> Boolean,
    ): HexanaWasmtimeDebuggerCaptureState {
        val deadline = System.nanoTime() + 45.seconds.inWholeNanoseconds
        var lastState: HexanaWasmtimeDebuggerCaptureState? = null
        while (System.nanoTime() < deadline) {
            lastState = wasmtimeDebuggerCaptureState()
            if (predicate(lastState)) return lastState
            Thread.sleep(250)
        }
        error("Timed out waiting for $description; last semantic debugger state: $lastState")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val target = repoRoot().resolve("docs/whatsnew/captures/0.18").createDirectories().resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.18 screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repository root")
        }
        return path
    }

    private companion object {
        const val DEBUG_WASM_FILE = "debug-add.wasm"
        const val DEBUG_SOURCE_FILE = "debug_add.rs"
        const val BREAKPOINT_LINE = 10
    }
}
