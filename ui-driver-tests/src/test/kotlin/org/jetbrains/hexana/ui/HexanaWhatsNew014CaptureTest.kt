package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditorTab
import org.jetbrains.hexana.ui.framework.HexanaProviderKind
import org.jetbrains.hexana.ui.framework.HexanaNativeDiffState
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
class HexanaWhatsNew014CaptureTest {
    @Test
    fun captureNativeBinaryDiff() {
        hexanaIde(
            testName = "hexana-whats-new-014-native-diff-capture",
            configure = {
                showWhatsNewOnNewVersion(false)
            },
        ) {
            closeProjectToolWindow()
            val fixtures = prepareNativeDiffFixtures()
            val editor = openNativeDiff(fixtures.baseline, fixtures.expanded)

            driver.ideFrame {
                editorTabs {
                    tab("baseline.elf ↔ expanded.so", fullMatch = false).waitFound(30.seconds)
                }
            }

            val state = waitForReadyNativeDiff(editor)
            check(state.changedRowCount > 0) { "Expected changed native diff rows: $state" }
            check(state.sections.any { it == "MODIFIED:.text" }) {
                "Expected a modified .text section: ${state.sections}"
            }
            check(state.sections.any { it == "ADDED:.dynsym" }) {
                "Expected an added .dynsym section: ${state.sections}"
            }
            copyScreenshot(driver, "native-binary-diff", "native-binary-diff.png")
        }
    }

    @Test
    fun captureLargeWasmFunctions() {
        val largeWasm = checkNotNull(HexanaUiDriverTestSupport.largeWasmFixture()) {
            "Large Wasm fixture is unavailable"
        }
        hexanaIde(
            testName = "hexana-whats-new-014-large-wasm-capture",
            configure = {
                showWhatsNewOnNewVersion(false)
            },
        ) {
            closeProjectToolWindow()
            val editor = openAbsoluteFile(largeWasm)
            driver.ideFrame {
                editorTabs {
                    tab(largeWasm.fileName.toString(), fullMatch = false).waitFound(30.seconds)
                }
            }
            editor.selectTab(HexanaEditorTab.FUNCTIONS.tabName)
            waitForLargeWasmAnalysis(editor)
            copyScreenshot(driver, "large-wasm-functions", "large-wasm-functions.png")
        }
    }

    @Test
    fun captureComponentDependencies() {
        hexanaIde(
            testName = "hexana-whats-new-014-component-dependencies-capture",
            configure = {
                showWhatsNewOnNewVersion(false)
            },
        ) {
            closeProjectToolWindow()
            val component = repoRoot().resolve("wasmParser/src/commonTest/resources/component/calculator.wasm")
            require(component.exists()) { "Missing Component Model fixture: $component" }
            val editor = openAbsoluteFile(component)
            driver.ideFrame {
                editorTabs {
                    tab(component.fileName.toString(), fullMatch = false).waitFound(30.seconds)
                }
            }
            editor.selectTab(HexanaEditorTab.DEPENDENCIES.tabName)
            editor.setTopFraction(0.25f)
            waitForComponentDependencies(editor)
            copyScreenshot(driver, "component-dependencies", "component-dependencies.png")
        }
    }

    @Test
    fun captureSemanticHighlighting() {
        hexanaIde(
            testName = "hexana-whats-new-014-semantic-highlighting-capture",
            configure = {
                showWhatsNewOnNewVersion(false)
            },
        ) {
            closeProjectToolWindow()
            val wasm = repoRoot().resolve("wasmParser/src/commonTest/resources/c/utf8-c.wasm")
            require(wasm.exists()) { "Missing semantic-highlighting fixture: $wasm" }
            val editor = openAbsoluteFile(wasm)
            driver.ideFrame {
                editorTabs {
                    tab(wasm.fileName.toString(), fullMatch = false).waitFound(30.seconds)
                }
            }
            waitForCoreWasm(editor)
            copyScreenshot(driver, "semantic-highlighting", "semantic-highlighting.png")
        }
    }

    private fun waitForReadyNativeDiff(editor: org.jetbrains.hexana.ui.framework.HexanaEditor): HexanaNativeDiffState {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastState: HexanaNativeDiffState? = null
        while (System.nanoTime() < deadline) {
            lastState = editor.nativeDiffState()
            if (lastState.status == "ready") return lastState
            if (lastState.status == "error") {
                error("Native diff failed to load: ${lastState.error}")
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for native diff readiness; last state was $lastState")
    }

    private fun waitForLargeWasmAnalysis(editor: org.jetbrains.hexana.ui.framework.HexanaEditor) {
        val deadline = System.nanoTime() + 2.minutes.inWholeNanoseconds
        var lastTabs: List<String> = emptyList()
        while (System.nanoTime() < deadline) {
            val snapshot = editor.snapshot()
            lastTabs = snapshot.panelNames
            if (
                snapshot.selectedTabName == HexanaEditorTab.FUNCTIONS.tabName &&
                snapshot.panelNames.any { it.startsWith("Garbage: ") }
            ) {
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for resolved large-Wasm analysis; tabs: $lastTabs")
    }

    private fun waitForComponentDependencies(editor: org.jetbrains.hexana.ui.framework.HexanaEditor) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (
                lastSnapshot.providerKind == HexanaProviderKind.COMPONENT_WASM.id &&
                lastSnapshot.selectedTabName == HexanaEditorTab.DEPENDENCIES.tabName &&
                HexanaEditorTab.DEPENDENCIES.tabName in lastSnapshot.panelNames
            ) {
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for Component Model dependencies; snapshot: $lastSnapshot")
    }

    private fun waitForCoreWasm(editor: org.jetbrains.hexana.ui.framework.HexanaEditor) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.providerKind == HexanaProviderKind.CORE_WASM.id) return
            Thread.sleep(250)
        }
        error("Timed out waiting for Core Wasm editor; snapshot: $lastSnapshot")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }

        val target = repoRoot()
            .resolve("docs/whatsnew/captures/0.14")
            .createDirectories()
            .resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.14 screenshot: $target")
    }

    private fun prepareNativeDiffFixtures(): NativeDiffFixtures {
        val fixtureRoot = repoRoot().resolve("parsing/native-fixtures/test-data/elf")
        val baselineSource = fixtureRoot.resolve("sample.elf")
        val expandedSource = fixtureRoot.resolve("libsample.so")
        require(baselineSource.exists()) { "Missing ELF fixture: $baselineSource" }
        require(expandedSource.exists()) { "Missing ELF fixture: $expandedSource" }

        val directory = Files.createTempDirectory("hexana-whatsnew-014-native-diff-")
        val baseline = directory.resolve("baseline.elf")
        val expanded = directory.resolve("expanded.so")
        Files.copy(baselineSource, baseline, StandardCopyOption.REPLACE_EXISTING)
        Files.copy(expandedSource, expanded, StandardCopyOption.REPLACE_EXISTING)
        return NativeDiffFixtures(baseline, expanded)
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
        }
        return path
    }

    private data class NativeDiffFixtures(
        val baseline: Path,
        val expanded: Path,
    )
}
