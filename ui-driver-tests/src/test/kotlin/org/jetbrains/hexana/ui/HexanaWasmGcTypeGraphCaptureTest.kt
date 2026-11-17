package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

/**
 * Captures the three WasmGC Type Graph screenshots on a real Kotlin/Wasm GC
 * binary (the hello.wasm test fixture): the annotated type section, the
 * type-info popup in a function body, and the focused type graph.
 */
@Tag("screenshot")
class HexanaWasmGcTypeGraphCaptureTest {
    @Test
    fun captureWasmGcTypeGraph() {
        val project = Files.createTempDirectory("hexana-wasmgc-type-graph-")
        val wasm = project.resolve("kotlin-hello.wasm")
        val fixture = repoRoot().resolve("wasmParser/src/commonTest/resources/kotlin/hello.wasm")
        require(fixture.exists()) { "Missing Kotlin/Wasm fixture: $fixture" }
        Files.copy(fixture, wasm)

        hexanaIde(
            testName = "hexana-wasmgc-type-graph-capture",
            projectDir = project,
            configure = {
                showWhatsNewOnNewVersion(false)
                patchContext { context ->
                    val optionsDir = context.paths.configDir.resolve("options").createDirectories()
                    Files.writeString(
                        optionsDir.resolve("laf.xml"),
                        """
                        <application>
                          <component name="LafManager" autodetect="false">
                            <laf themeId="ExperimentalLight" />
                          </component>
                        </application>
                        """.trimIndent()
                    )
                    context
                }
            },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            closeServicesToolWindow()
            val editor = openFile(wasm.fileName.toString())
            waitForPanel(editor, "Type Graph")
            // Give the bottom panel (WAT / graph) most of the editor height.
            editor.setTopFraction(0.14f)
            editor.selectTab("WAT (virtualized)")
            waitForSelectedTab(editor, "WAT (virtualized)")

            val targets = editor.wasmTypeGraphDemoTargets()
            logOutput("WasmGC demo targets: $targets")
            val typeIdx = targets.value("typeIdx")?.toIntOrNull()
                ?: error("No demo typeIdx in probe payload: $targets")
            val demoFuncIdx = targets.value("demoFuncIdx")?.toIntOrNull() ?: -1

            // 1. Annotated type section: definition rows with type links plus
            // the per-type annotation row (subtypes / referenced-by / rec group).
            editor.navigateWatToType(typeIdx)
            Thread.sleep(2_500)
            clearNotifications()
            Thread.sleep(500)
            copyScreenshot(driver, "wasmgc-type-annotations", "59-wasmgc-type-annotations.png")

            // 2. Type-info popup over a function body that references the type.
            if (demoFuncIdx >= 0) {
                editor.navigateToWasmFunction(demoFuncIdx)
                Thread.sleep(1_500)
            }
            editor.showWasmTypeInfoPopup(typeIdx)
            Thread.sleep(2_500)
            clearNotifications()
            Thread.sleep(500)
            copyScreenshot(driver, "wasmgc-type-info-popup", "60-wasmgc-type-info-popup.png")

            // 3. Focused type graph with rec-group frames and dashed field edges.
            editor.showWasmTypeGraph(typeIdx)
            editor.selectTab("Type Graph")
            waitForSelectedTab(editor, "Type Graph")
            Thread.sleep(3_000)
            clearNotifications()
            Thread.sleep(500)
            copyScreenshot(driver, "wasmgc-type-graph", "61-wasmgc-type-graph.png")
        }
    }

    private fun List<String>.value(key: String): String? =
        firstOrNull { it.startsWith("$key=") }?.substringAfter("=")

    private fun waitForPanel(editor: HexanaEditor, panelName: String) {
        val deadline = System.nanoTime() + 120.seconds.inWholeNanoseconds
        var last = editor.snapshot()
        while (System.nanoTime() < deadline) {
            last = editor.snapshot()
            if (last.panelsResolved && panelName in last.panelNames) return
            Thread.sleep(250)
        }
        error("Timed out waiting for the $panelName panel; snapshot=$last")
    }

    private fun waitForSelectedTab(editor: HexanaEditor, tabName: String) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var last = editor.snapshot()
        while (System.nanoTime() < deadline) {
            last = editor.snapshot()
            if (last.selectedTabName == tabName) return
            Thread.sleep(250)
        }
        error("Timed out waiting for the selected $tabName tab; snapshot=$last")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val target = repoRoot().resolve("doc/images/idea").createDirectories().resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("WasmGC Type Graph screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists() && !path.resolve("settings.gradle").exists()) {
            path = path.parent ?: error("Could not locate repository root")
        }
        return path
    }
}
