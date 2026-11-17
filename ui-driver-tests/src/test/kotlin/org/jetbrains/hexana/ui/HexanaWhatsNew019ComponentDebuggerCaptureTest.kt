package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.DriverGifCapture
import org.jetbrains.hexana.ui.framework.GifCursor
import org.jetbrains.hexana.ui.framework.HexanaWasmDebuggerStep
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.withWasmRunDsl
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

@Tag("screenshot")
class HexanaWhatsNew019ComponentDebuggerCaptureTest {
    @Test
    fun captureComponentModelWasmDebugger() {
        hexanaIde(
            testName = "hexana-whats-new-019-component-debugger-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            withWasmRunDsl(COMPONENT_WASM_FILE) {
                try {
                    val timeline = DriverGifCapture(
                        output = repoRoot().resolve("docs/whatsnew/captures/0.19/component-wasm-debugger.gif"),
                        // Keep the complete timeline within the What's New animation decode budget.
                        maxWidth = 960,
                    )
                    capturePointerTravel(
                        timeline = timeline,
                        driver = driver,
                        screenshotName = "component-debugger-open-editor",
                        from = GifCursor(0.48f, 0.42f),
                        to = DEBUG_BUTTON_CURSOR,
                    )
                    captureHold(timeline, driver, "component-debugger-click-debug", DEBUG_BUTTON_CURSOR, frameCount = 2)
                    startWasmtimeComponentDebugPausedForCapture(
                        wasmFileName = COMPONENT_WASM_FILE,
                        moduleId = COMPONENT_MODULE_ID,
                        moduleRelativeOffset = COMPONENT_MODULE_OFFSET,
                    )
                    val paused = awaitWasmDebuggerTabState("the selected Component Model guest instruction") { state ->
                        state.sessionActive &&
                            state.sessionSuspended &&
                            state.tabRegistered &&
                            state.hasHighlightedInstruction &&
                            state.selectedModuleId == COMPONENT_MODULE_ID &&
                            state.selectedOffset == COMPONENT_MODULE_OFFSET &&
                            state.selectedInstruction.contains("global.get 2")
                    }

                    capturePointerTravel(
                        timeline = timeline,
                        driver = driver,
                        screenshotName = "component-debugger-paused",
                        from = DEBUG_BUTTON_CURSOR,
                        to = WASM_TAB_CURSOR,
                    )
                    selectWasmDebuggerTab()
                    val selected = awaitWasmDebuggerTabState("the selected Component Model Wasm tab") { state ->
                        state.tabSelected &&
                            state.selectedModuleId == COMPONENT_MODULE_ID &&
                            state.selectedOffset == paused.selectedOffset &&
                            state.selectedInstruction.contains("global.get 2")
                    }
                    captureHold(timeline, driver, "component-debugger-wasm-tab", WASM_TAB_CURSOR, frameCount = 2)
                    capturePointerTravel(
                        timeline = timeline,
                        driver = driver,
                        screenshotName = "component-debugger-step-over",
                        from = WASM_TAB_CURSOR,
                        to = STEP_OVER_CURSOR,
                    )

                    val stepped = stepInWasmDebuggerAndAwaitNewHighlight(
                        step = HexanaWasmDebuggerStep.OVER,
                        previousOffset = selected.selectedOffset,
                    )
                    check(stepped.selectedModuleId == COMPONENT_MODULE_ID) {
                        "Expected instruction stepping to stay in embedded module $COMPONENT_MODULE_ID; state: $stepped"
                    }
                    captureHold(timeline, driver, "component-debugger-stepped", STEP_OVER_CURSOR, frameCount = 3)
                    timeline.write()
                    logOutput("What's New 0.19 Component Model debugger state: $stepped")
                    copyScreenshot(driver, "component-wasm-debugger", "component-wasm-debugger.png")
                } finally {
                    stopWasmDebugSession()
                }
            }
        }
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

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repository root")
        }
        return path
    }

    private fun capturePointerTravel(
        timeline: DriverGifCapture,
        driver: Driver,
        screenshotName: String,
        from: GifCursor,
        to: GifCursor,
        frameCount: Int = 4,
    ) {
        repeat(frameCount) { frame ->
            val progress = frame.toFloat() / (frameCount - 1)
            timeline.captureFrame(
                driver = driver,
                screenshotName = "$screenshotName-$frame",
                durationMillis = FRAME_DURATION_MILLIS,
                cursor = GifCursor(
                    xFraction = from.xFraction + (to.xFraction - from.xFraction) * progress,
                    yFraction = from.yFraction + (to.yFraction - from.yFraction) * progress,
                ),
            )
        }
    }

    private fun captureHold(
        timeline: DriverGifCapture,
        driver: Driver,
        screenshotName: String,
        cursor: GifCursor,
        frameCount: Int,
    ) {
        repeat(frameCount) { frame ->
            timeline.captureFrame(
                driver = driver,
                screenshotName = "$screenshotName-$frame",
                durationMillis = FRAME_DURATION_MILLIS,
                cursor = cursor,
            )
        }
    }

    private companion object {
        const val COMPONENT_WASM_FILE = "hello_component.wasm"
        const val COMPONENT_MODULE_ID = 1
        const val COMPONENT_MODULE_OFFSET = 0x1656
        const val FRAME_DURATION_MILLIS = 120
        val DEBUG_BUTTON_CURSOR = GifCursor(0.28f, 0.12f)
        val WASM_TAB_CURSOR = GifCursor(0.32f, 0.66f)
        val STEP_OVER_CURSOR = GifCursor(0.09f, 0.66f)
    }
}
