package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.HexanaEditorTab
import org.jetbrains.hexana.ui.framework.HexanaProviderKind
import org.jetbrains.hexana.ui.framework.HexanaWatFindUsagesCaptureState
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNew018WatComponentCaptureTest {
    @Test
    fun captureComponentModelFindUsagesInEmbeddedWatEditor() {
        hexanaIde(
            testName = "hexana-whats-new-018-wat-component-find-usages-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            hexanaEditor(COMPONENT_WASM_FILE) {
                waitForComponentWat()
                selectTab(HexanaEditorTab.WAT.tabName)
                waitForPlainWatEditor()
                val invocation = showFindUsagesForComponentDeclaration()
                require(invocation.expectedUsages > 0) {
                    "Expected a referenced Component Model declaration, got $invocation"
                }
                val state = waitForCompletedFindUsages(invocation.expectedUsages)
                logOutput("What's New 0.18 WAT Find Usages semantic state: invocation=$invocation state=$state")
                copyScreenshot(driver, "wat-component-find-usages", "wat-component-find-usages.png")
            }
        }
    }

    private fun HexanaEditor.waitForComponentWat() {
        val deadline = System.nanoTime() + 45.seconds.inWholeNanoseconds
        var lastSnapshot = snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = snapshot()
            if (
                lastSnapshot.providerKind == HexanaProviderKind.COMPONENT_WASM.id &&
                lastSnapshot.panelsResolved &&
                HexanaEditorTab.WAT.tabName in lastSnapshot.panelNames
            ) {
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for the Component Model plain Wat tab; last snapshot: $lastSnapshot")
    }

    private fun HexanaEditor.waitForPlainWatEditor() {
        val deadline = System.nanoTime() + 45.seconds.inWholeNanoseconds
        var lastState = watState()
        while (System.nanoTime() < deadline) {
            lastState = watState()
            if (lastState.selectedTabName == HexanaEditorTab.WAT.tabName && lastState.isSuccess) return
            Thread.sleep(250)
        }
        error("Timed out waiting for the selected LightWatFile editor; last WAT state: $lastState")
    }

    private fun HexanaEditor.waitForCompletedFindUsages(expectedUsages: Int): HexanaWatFindUsagesCaptureState {
        val deadline = System.nanoTime() + 45.seconds.inWholeNanoseconds
        var lastState: HexanaWatFindUsagesCaptureState? = null
        while (System.nanoTime() < deadline) {
            lastState = watFindUsagesCaptureState()
            if (
                lastState.toolWindowVisible &&
                !lastState.searchInProgress &&
                lastState.usageCount == expectedUsages
            ) {
                return lastState
            }
            Thread.sleep(250)
        }
        error(
            "Timed out waiting for completed Component Model Find Usages with $expectedUsages results; " +
                "last semantic usage state: $lastState",
        )
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
        const val COMPONENT_WASM_FILE = "component_add.wasm"
    }
}
