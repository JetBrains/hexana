package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.DriverGifCapture
import org.jetbrains.hexana.ui.framework.GifCursor
import org.jetbrains.hexana.ui.framework.HexanaWasmRunDsl
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.withWasmRunDsl
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNew020DebuggerConsoleCaptureTest {
    @Test
    fun captureDebuggerConsoleFrameInfo() {
        hexanaIde(
            testName = "hexana-whats-new-020-debugger-console-capture",
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
                    val paused = awaitPausedWasmtimeSession()
                    val output = repoRoot().resolve("docs/whatsnew/captures/0.20/debugger-console.gif")
                    val timeline = DriverGifCapture(
                        output = output,
                        maxWidth = 960,
                    )

                    selectDebuggerConsoleTab()
                    timeline.captureFrame(
                        driver = driver,
                        screenshotName = "debugger-console-open",
                        durationMillis = FRAME_DURATION_MILLIS,
                        cursor = COMMAND_INPUT_CURSOR,
                    )

                    enterDebuggerConsoleCommand("frame info")
                    Thread.sleep(1_000)
                    timeline.captureFrame(
                        driver = driver,
                        screenshotName = "debugger-console-command-entered",
                        durationMillis = FRAME_DURATION_MILLIS,
                        cursor = COMMAND_INPUT_CURSOR,
                    )

                    submitDebuggerConsoleCommand(expectedOutput = "frame #")
                    Thread.sleep(1_000)
                    timeline.captureFrame(
                        driver = driver,
                        screenshotName = "debugger-console-output",
                        durationMillis = FRAME_DURATION_MILLIS,
                        cursor = CONSOLE_OUTPUT_CURSOR,
                    )
                    timeline.write()
                    logOutput("Hexana What's New 0.20 debugger-console GIF: $output")
                    logOutput("What's New 0.20 debugger-console paused state: $paused")
                } finally {
                    terminateDebuggerForCapture()
                }
            }
        }
    }

    private fun HexanaWasmRunDsl.awaitPausedWasmtimeSession() = waitForState(
        description = "a paused Wasmtime LLDB session",
    ) { state ->
        state.sessionSuspended && state.engine == "WASMTIME" && state.wasmTabAvailable
    }

    private fun HexanaWasmRunDsl.waitForState(
        description: String,
        predicate: (org.jetbrains.hexana.ui.framework.HexanaWasmtimeDebuggerCaptureState) -> Boolean,
    ): org.jetbrains.hexana.ui.framework.HexanaWasmtimeDebuggerCaptureState {
        val deadline = System.nanoTime() + 45.seconds.inWholeNanoseconds
        var lastState = wasmtimeDebuggerCaptureState()
        while (System.nanoTime() < deadline) {
            if (predicate(lastState)) return lastState
            Thread.sleep(250)
            lastState = wasmtimeDebuggerCaptureState()
        }
        error("Timed out waiting for $description; last semantic debugger state: $lastState")
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
        const val FRAME_DURATION_MILLIS = 1_000
        val COMMAND_INPUT_CURSOR = GifCursor(0.50f, 0.86f)
        val CONSOLE_OUTPUT_CURSOR = GifCursor(0.52f, 0.49f)
    }
}
