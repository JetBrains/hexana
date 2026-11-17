package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.ui
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.HexanaEditorTab
import org.jetbrains.hexana.ui.framework.HexanaProviderKind
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
class HexanaWhatsNew015CaptureTest {
    @Test
    fun captureTopSizeVisualization() {
        hexanaIde(
            testName = "hexana-whats-new-015-top-size-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            val fixture = repoRoot().resolve("wasmParser/src/commonTest/resources/skiko.wasm")
            require(fixture.exists()) { "Missing Top capture fixture: $fixture" }
            val editor = openAbsoluteFile(fixture)
            waitForEditorTab(editor)
            waitForResolvedPanels(editor, HexanaEditorTab.TOP.tabName)
            editor.selectTab(HexanaEditorTab.TOP.tabName)
            editor.setTopFraction(0.15f)
            waitForSelectedTab(editor, HexanaEditorTab.TOP.tabName)
            waitForTopSizeData(editor)
            copyScreenshot(driver, "top-size-visualization", "top-size-visualization.png")
        }
    }

    @Test
    fun captureExceptionsSurface() {
        val fixture = repoRoot().resolve("ui-driver-tests/testData/projects/hexana_wasm_project/exceptions-surface.wasm")
        require(fixture.exists()) { "Missing Exceptions capture fixture: $fixture" }
        hexanaIde(
            testName = "hexana-whats-new-015-exceptions-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            val editor = openAbsoluteFile(fixture)
            waitForEditorTab(editor)
            waitForResolvedPanels(editor, "Exceptions")
            editor.selectTab("Exceptions")
            editor.setTopFraction(0.25f)
            waitForSelectedTab(editor, "Exceptions")
            copyScreenshot(driver, "exceptions-surface", "exceptions-surface.png")
        }
    }

    @Test
    fun captureInlineDispatchCandidates() {
        val fixture = repoRoot().resolve("ui-driver-tests/testData/projects/hexana_wasm_project/dispatch-candidates-named.wasm")
        require(fixture.exists()) { "Missing dispatch capture fixture: $fixture" }
        hexanaIde(
            testName = "hexana-whats-new-015-inline-dispatch-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            val editor = openAbsoluteFile(fixture)
            waitForEditorTab(editor)
            waitForResolvedPanels(editor, HexanaEditorTab.WAT_VIRTUALIZED.tabName)
            editor.selectTab(HexanaEditorTab.WAT_VIRTUALIZED.tabName)
            editor.setTopFraction(0.15f)
            waitForSelectedTab(editor, HexanaEditorTab.WAT_VIRTUALIZED.tabName)
            editor.navigateToWasmFunction(6)
            driver.withContext {
                val showCandidates = ui.x { byText("▶ show 4 candidates") }.waitFound(30.seconds)
                showCandidates.click()
                ui.x { byText("▼ hide 4 candidates") }.waitFound(30.seconds)
            }
            copyScreenshot(driver, "call-indirect-candidates", "call-indirect-candidates.png")
        }
    }

    private fun org.jetbrains.hexana.ui.framework.HexanaIde.waitForEditorTab(editor: HexanaEditor) {
        driver.ideFrame {
            editorTabs {
                tab(editor.fileName, fullMatch = false).waitFound(30.seconds)
            }
        }
    }

    private fun waitForResolvedPanels(editor: HexanaEditor, requiredTab: String) {
        val deadline = System.nanoTime() + 2.minutes.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (
                lastSnapshot.providerKind == HexanaProviderKind.CORE_WASM.id &&
                lastSnapshot.panelsResolved &&
                requiredTab in lastSnapshot.panelNames
            ) {
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for resolved tab '$requiredTab'; snapshot: $lastSnapshot")
    }

    private fun waitForSelectedTab(editor: HexanaEditor, tabName: String) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.panelsResolved && lastSnapshot.selectedTabName == tabName) return
            Thread.sleep(250)
        }
        error("Timed out waiting for selected tab '$tabName'; snapshot: $lastSnapshot")
    }

    private fun waitForTopSizeData(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 2.minutes.inWholeNanoseconds
        var lastState: List<String> = emptyList()
        while (System.nanoTime() < deadline) {
            lastState = editor.topSizeState()
            val values = lastState.mapNotNull { entry ->
                entry.substringBefore('=', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
                    ?.let { key -> key to entry.substringAfter('=') }
            }.toMap()
            if (
                values["totalSize"]?.toLongOrNull()?.let { it > 0 } == true &&
                values["topLevelCount"]?.toIntOrNull()?.let { it > 0 } == true
            ) {
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for Top size data; last state: $lastState")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }

        val target = repoRoot()
            .resolve("docs/whatsnew/captures/0.15")
            .createDirectories()
            .resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.15 screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
        }
        return path
    }
}
