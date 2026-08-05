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
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNew016GlueWatCaptureTest {
    @Test
    fun captureGlueComponentVirtualizedWat() {
        val fixture = prepareGlueComponentFixture()
        hexanaIde(
            testName = "hexana-whats-new-016-glue-component-virtualized-wat-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            val editor = openAbsoluteFile(fixture)
            waitForEditorTab(editor)
            waitForVirtualizedWatPanel(editor)
            editor.selectTab(HexanaEditorTab.WAT_VIRTUALIZED.tabName)
            waitForSelectedVirtualizedWat(editor)
            editor.setTopFraction(0.25f)
            driver.withContext {
                ui.x { byText("Search by name or offset") }.waitFound(30.seconds)
            }
            copyScreenshot(driver)
        }
    }

    private fun org.jetbrains.hexana.ui.framework.HexanaIde.waitForEditorTab(editor: HexanaEditor) {
        driver.ideFrame {
            editorTabs {
                tab(editor.fileName, fullMatch = false).waitFound(30.seconds)
            }
        }
    }

    private fun waitForVirtualizedWatPanel(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (
                lastSnapshot.providerKind == HexanaProviderKind.COMPONENT_WASM.id &&
                lastSnapshot.panelsResolved &&
                HexanaEditorTab.WAT_VIRTUALIZED.tabName in lastSnapshot.panelNames
            ) {
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for Component Model virtualized WAT panel; snapshot: $lastSnapshot")
    }

    private fun waitForSelectedVirtualizedWat(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (
                lastSnapshot.panelsResolved &&
                lastSnapshot.selectedTabName == HexanaEditorTab.WAT_VIRTUALIZED.tabName
            ) {
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for selected Component Model virtualized WAT panel; snapshot: $lastSnapshot")
    }

    private fun copyScreenshot(driver: Driver) {
        val screenshot = driver.takeScreenshot("glue-component-virtualized-wat")
            ?: error("Driver did not return a screenshot path for glue-component-virtualized-wat")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }

        val target = repoRoot()
            .resolve("docs/whatsnew/captures/0.16")
            .createDirectories()
            .resolve("glue-component-virtualized-wat.png")
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.16 screenshot: $target")
    }

    private fun prepareGlueComponentFixture(): Path =
        Files.createTempFile("hexana-whatsnew-016-glue-component-", ".wasm").also { fixture ->
            fixture.writeBytes(HexFormat.of().parseHex(GLUE_COMPONENT_HEX))
        }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
        }
        return path
    }

    private companion object {
        // From WasmComponentWatVirtualizedTest: a wasm-tools-parsed glue-only component.
        // It has one function import, a record type, a function type, and one export; no core modules.
        const val GLUE_COMPONENT_HEX =
            "0061736d0d000100070501400001000a17010012696d706f72742d66756e632d68616e646c6501000" +
                "71d02720207726571756573747908726573706f6e736573400101787900730b0c01000668616e646c65030100"
    }
}
