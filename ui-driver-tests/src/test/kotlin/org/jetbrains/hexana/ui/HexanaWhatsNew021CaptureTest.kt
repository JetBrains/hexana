package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.ui
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

/** Release art is opt-in: the normal UI suite excludes the screenshot tag. */
@Tag("screenshot")
class HexanaWhatsNew021CaptureTest {
    /** Review evidence of the real bundled page, rather than the feature source images. */
    @Test
    fun captureWhatsNewPage() {
        val project = Files.createTempDirectory("hexana-021-page-")
        hexanaIde("hexana-021-whatsnew-page", projectDir = project, configure = { showWhatsNewOnNewVersion(false) }) {
            maximizeWindow()
            closeProjectToolWindow()
            closeServicesToolWindow()
            driver.invokeAction("hexana.showWhatsNew")
            driver.ui.x { contains(byText("What's New in Hexana 0.21")) }.waitFound(120.seconds)
            clearNotifications()
            val page = driver.ui.x { byClass("ComposePanel") }.waitFound(30.seconds)
            val output = Path.of(checkNotNull(System.getenv("AIR_ARTIFACTS_DIR")))
                .resolve("whatsnew-021-ide-page").createDirectories()
            for (index in 0..2) {
                if (index > 0) page.robot.rotateMouseWheel(page.component, 8)
                Thread.sleep(1_500)
                val screenshot = driver.takeScreenshot("whatsnew-021-page-$index")
                    ?: error("No page screenshot at scroll step $index")
                Files.copy(Path.of(screenshot.toString()), output.resolve("page-%02d.png".format(index)), StandardCopyOption.REPLACE_EXISTING)
            }
            logOutput("What's New page capture sequence: $output")
        }
    }

    @Test
    fun captureHexDiff() {
        val project = Files.createTempDirectory("hexana-021-hex-")
        val before = compileNativeFixture(project, "before", "return a + b;")
        val after = compileNativeFixture(project, "after", "return a * b;")
        hexanaIde("hexana-021-hex-diff", projectDir = project, configure = { showWhatsNewOnNewVersion(false) }) {
            maximizeWindow()
            closeProjectToolWindow()
            closeServicesToolWindow()
            openPlatformHexDiff(before, after)
            driver.ui.x { contains(byText("bytes differ")) }.waitFound(120.seconds)
            clearNotifications()
            Thread.sleep(2_000)
            copyScreenshot(driver, "hex-diff")
        }
    }

    @Test
    fun captureNativeFunctionDiff() {
        val project = Files.createTempDirectory("hexana-021-functions-")
        val before = compileNativeFixture(project, "before", "return a + b;")
        val after = compileNativeFixture(project, "after", "return a * b;")
        hexanaIde("hexana-021-native-function-diff", projectDir = project, configure = { showWhatsNewOnNewVersion(false) }) {
            maximizeWindow()
            closeProjectToolWindow()
            closeServicesToolWindow()
            openNativeDiff(before, after)
            driver.ui.x { byText("Functions") }.waitFound(120.seconds).click()
            // Compose merges the clickable row cells into one semantics text node.
            driver.ui.x { contains(byText("hexana_add")) }.waitFound(120.seconds).click()
            driver.ui.x { contains(byText("imul")) }.waitFound(120.seconds)
            clearNotifications()
            Thread.sleep(2_000)
            copyScreenshot(driver, "native-function-diff")
        }
    }

    @Test
    fun captureWasmGcGraph() {
        val project = Files.createTempDirectory("hexana-021-wasmgc-")
        val wasm = project.resolve("kotlin-hello.wasm")
        Files.copy(repoRoot().resolve("wasmParser/src/commonTest/resources/kotlin/hello.wasm"), wasm)
        hexanaIde("hexana-021-wasmgc-graph", projectDir = project, configure = { showWhatsNewOnNewVersion(false) }) {
            maximizeWindow()
            closeProjectToolWindow()
            closeServicesToolWindow()
            val editor = openFile(wasm.fileName.toString())
            val deadline = System.nanoTime() + 120.seconds.inWholeNanoseconds
            while ("Type Graph" !in editor.snapshot().panelNames) {
                check(System.nanoTime() < deadline) { "Type Graph unavailable: ${editor.snapshot()}" }
                Thread.sleep(250)
            }
            editor.setTopFraction(0.14f)
            val targets = editor.wasmTypeGraphDemoTargets()
            val type = targets.first { it.startsWith("typeIdx=") }.substringAfter('=').toInt()
            editor.showWasmTypeGraph(type)
            editor.selectTab("Type Graph")
            check(editor.snapshot().selectedTabName == "Type Graph")
            val graphDeadline = System.nanoTime() + 120.seconds.inWholeNanoseconds
            while (editor.wasmUiProbeValue("typeGraph")?.contains("focus=$type") != true) {
                check(System.nanoTime() < graphDeadline) { "Graph did not focus type $type: ${editor.wasmUiProbeValue("typeGraph")}" }
                Thread.sleep(250)
            }
            clearNotifications()
            Thread.sleep(3_000)
            copyScreenshot(driver, "wasmgc-type-graph")
        }
    }

    private fun compileNativeFixture(project: Path, name: String, body: String): Path {
        val source = project.resolve("$name.c")
        Files.writeString(source, "__attribute__((noinline)) int hexana_add(int a, int b) { $body }\n")
        val output = project.resolve("$name.o")
        val process = ProcessBuilder("gcc", "-c", "-O0", "-fno-stack-protector", "-o", output.toString(), source.toString()).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "Native fixture compilation failed: $log" }
        return output
    }

    private fun copyScreenshot(driver: Driver, name: String) {
        val screenshot = driver.takeScreenshot("whatsnew-021-$name") ?: error("No screenshot for $name")
        val target = repoRoot().resolve("docs/whatsnew/captures/0.21").createDirectories().resolve("$name.png")
        Files.copy(Path.of(screenshot.toString()), target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Release 0.21 capture: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) path = path.parent ?: error("Missing repository root")
        return path
    }
}
