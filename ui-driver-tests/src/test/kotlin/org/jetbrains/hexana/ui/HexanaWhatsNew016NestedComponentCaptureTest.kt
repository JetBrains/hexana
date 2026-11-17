package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNew016NestedComponentCaptureTest {
    @Test
    fun captureNestedComponentEditor() {
        val fixture = repoRoot().resolve("wasmParser/src/commonTest/resources/component/composed.wasm")
        require(fixture.exists()) { "Missing recursively nested Component Model fixture: $fixture" }

        hexanaIde(
            testName = "hexana-whats-new-016-nested-component-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()

            val root = openAbsoluteFile(fixture)
            waitForEditorTab(root)
            waitForComponentPanel(root, "Components")
            root.selectTab("Components")
            waitForSelectedTab(root, "Components")

            val nested = openNestedComponent(root, componentIndex = 0)
            check(nested.fileName.startsWith("composed_component_0@")) {
                "Expected the nested editor name to include the root offset, got '${nested.fileName}'"
            }
            waitForEditorTab(nested)
            waitForComponentPanel(nested, "Components")
            nested.selectTab("Components")
            waitForSelectedTab(nested, "Components")

            val nestedAgain = openNestedComponent(nested, componentIndex = 0)
            check(
                nestedAgain.fileName.startsWith("composed_component_0@") &&
                    "_component_0@" in nestedAgain.fileName,
            ) {
                "Expected the recursively nested editor name to retain both offsets, got '${nestedAgain.fileName}'"
            }
            waitForEditorTab(nestedAgain)
            waitForComponentPanel(nestedAgain, "WAT (virtualized)")

            nested.activate()
            nested.setTopFraction(0.25f)
            waitForSelectedTab(nested, "Components")

            copyScreenshot(driver, "nested-component-editor", "nested-component-editor.png")
        }
    }

    private fun org.jetbrains.hexana.ui.framework.HexanaIde.waitForEditorTab(editor: HexanaEditor) {
        driver.ideFrame {
            editorTabs {
                tab(editor.fileName, fullMatch = false).waitFound(30.seconds)
            }
        }
    }

    private fun waitForComponentPanel(editor: HexanaEditor, requiredTab: String) {
        val deadline = System.nanoTime() + 2.minutes.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (
                lastSnapshot.providerKind == HexanaProviderKind.COMPONENT_WASM.id &&
                lastSnapshot.panelsResolved &&
                requiredTab in lastSnapshot.panelNames
            ) {
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for Component Model tab '$requiredTab'; snapshot: $lastSnapshot")
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

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }

        val target = repoRoot()
            .resolve("docs/whatsnew/captures/0.16")
            .createDirectories()
            .resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.16 screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
        }
        return path
    }
}
