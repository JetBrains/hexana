package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaComponentDiffState
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNewCaptureTest {
    @Test
    fun captureFeatureScreenshots() {
        hexanaIde(testName = "hexana-whats-new-feature-capture") {
            closeProjectToolWindow()

            val componentEditor = openComponentDiff("component_add.wasm", "hello_component.wasm")
            driver.ideFrame {
                editorTabs {
                    tab("component_add.wasm ↔ hello_component.wasm", fullMatch = false).waitFound(30.seconds)
                }
            }
            waitForComponentDiff(componentEditor)
            copyScreenshot(driver, "wasm-component-model-diff", "wasm-component-model.png")

            val archiveEditor = openFile("native/static-archive-showcase.lib")
            driver.ideFrame {
                editorTabs {
                    tab("static-archive-showcase.lib", fullMatch = false).waitFound(30.seconds)
                }
            }
            archiveEditor.selectTab("Members")
            waitForSelectedTab(archiveEditor, "Members")
            val archiveState = archiveEditor.archiveState()
            assertTrue(archiveState.memberCount >= 2, "Expected at least two archive members: $archiveState")
            assertTrue(archiveState.openableCount >= 2, "Expected at least two openable archive members: $archiveState")
            assertTrue(archiveState.members.any { it.format == "COFF" }, "Expected a COFF member: $archiveState")
            assertEquals("Members", archiveEditor.snapshot().selectedTabName)
            copyScreenshot(driver, "static-archives", "static-archives.png")
        }
    }

    private fun waitForComponentDiff(editor: org.jetbrains.hexana.ui.framework.HexanaEditor) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastState: HexanaComponentDiffState? = null
        while (System.nanoTime() < deadline) {
            lastState = editor.componentDiffState()
            val state = lastState
            if (state.totalPairings > 0 && state.differingCount > 0) {
                assertTrue(
                    state.pairings.any { it.status == "ADDED" || it.status == "MODIFIED" || it.status == "REMOVED" },
                    "Expected a visible changed component submodule: $state",
                )
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for Component Model diff; last state was '$lastState'")
    }

    private fun waitForSelectedTab(
        editor: org.jetbrains.hexana.ui.framework.HexanaEditor,
        tabName: String,
    ) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSelected: String? = null
        while (System.nanoTime() < deadline) {
            lastSelected = editor.snapshot().selectedTabName
            if (lastSelected == tabName) return
            Thread.sleep(250)
        }
        error("Timed out waiting for selected Hexana tab '$tabName'; last selected tab was '$lastSelected'")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }

        val target = repoRoot()
            .resolve("docs/whatsnew/captures/0.12")
            .createDirectories()
            .resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New feature screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
        }
        return path
    }
}
