package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.getRunContentManager
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.UiText.Companion.asString
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.tools.ide.util.common.logOutput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * UI driver tests for Wasm run configurations using the Wasmtime engine.
 *
 * Covers:
 * - Running a core module (with `_start`) from the hex view Run button
 * - Running an exported function from a core module
 * - Running a component model module (with `wasi:cli/run`) from the hex view Run button
 *
 * Wasmtime is downloaded automatically if not present.
 */
@Tag("hexana")
class HexanaRunConfigurationDriverTest {

    companion object {
        private const val HEXANA_PLUGIN_ID = "org.jetbrains.hexana"
        private const val CORE_MODULE_FILE = "hello_wasi.wasm"
        private const val ADD_MODULE_FILE = "add.wasm"
        private const val COMPONENT_MODULE_FILE = "hello_component.wasm"

        @JvmStatic
        @BeforeAll
        fun ensureWasmtime() {
            val wasmtimePath = resolveWasmtimePath()
            if (wasmtimePath != null) {
                logOutput("Wasmtime already available at: $wasmtimePath")
                return
            }
            logOutput("Wasmtime not found, downloading...")
            downloadWasmtime()
            val verifiedPath = resolveWasmtimePath()
                ?: error("Wasmtime installation failed: not found after download")
            logOutput("Wasmtime installed at: $verifiedPath")
        }

        private fun resolveWasmtimePath(): String? {
            val defaultPath = defaultWasmtimePath()
            if (File(defaultPath).exists()) return defaultPath
            return try {
                val process = ProcessBuilder("which", "wasmtime")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                if (process.exitValue() == 0 && output.isNotBlank() && File(output).exists()) output else null
            } catch (_: Exception) {
                null
            }
        }

        private fun defaultWasmtimePath(): String {
            val home = System.getProperty("user.home")
            return "$home/.wasmtime/bin/wasmtime"
        }

        private fun downloadWasmtime() {
            val arch = System.getProperty("os.arch").let {
                when {
                    it.contains("aarch64") || it.contains("arm64") -> "aarch64"
                    it.contains("x86_64") || it.contains("amd64") -> "x86_64"
                    else -> it
                }
            }
            val os = System.getProperty("os.name").lowercase().let {
                when {
                    it.contains("mac") || it.contains("darwin") -> "macos"
                    it.contains("linux") -> "linux"
                    it.contains("windows") -> "windows"
                    else -> error("Unsupported OS: $it")
                }
            }

            val version = "v43.0.0"
            val ext = if (os == "windows") "zip" else "tar.xz"
            val fileName = "wasmtime-$version-$arch-$os.$ext"
            val url = "https://github.com/bytecodealliance/wasmtime/releases/download/$version/$fileName"

            logOutput("Downloading wasmtime from: $url")
            val tempDir = Files.createTempDirectory("wasmtime-download")
            val archiveFile = tempDir.resolve(fileName)

            URI(url).toURL().openStream().use { input ->
                Files.copy(input, archiveFile)
            }

            val home = System.getProperty("user.home")
            val wasmtimeHome = Path.of(home, ".wasmtime")
            val binDir = wasmtimeHome.resolve("bin")
            Files.createDirectories(binDir)

            if (ext == "tar.xz") {
                val process = ProcessBuilder("tar", "xf", archiveFile.toString(), "-C", tempDir.toString())
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
                require(process.exitValue() == 0) {
                    "Failed to extract wasmtime archive: ${process.inputStream.bufferedReader().readText()}"
                }

                val extractedDir = Files.list(tempDir)
                    .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("wasmtime-") }
                    .findFirst()
                    .orElseThrow { error("Extracted wasmtime directory not found") }

                val wasmtimeBin = extractedDir.resolve("wasmtime")
                val targetBin = binDir.resolve("wasmtime")
                Files.copy(wasmtimeBin, targetBin, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                targetBin.toFile().setExecutable(true)
            } else {
                val process = ProcessBuilder("unzip", "-o", archiveFile.toString(), "-d", tempDir.toString())
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()

                val extractedDir = Files.list(tempDir)
                    .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("wasmtime-") }
                    .findFirst()
                    .orElseThrow { error("Extracted wasmtime directory not found") }

                val wasmtimeBin = extractedDir.resolve("wasmtime.exe")
                val targetBin = binDir.resolve("wasmtime.exe")
                Files.copy(wasmtimeBin, targetBin, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }

            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun runCoreModuleWithWasmtime() {
        val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-run-core-module")

        context.runIdeWithDriver(runTimeout = 15.minutes) { withScreenRecording() }
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                openFile(CORE_MODULE_FILE, waitForCodeAnalysis = false, isTextEditor = false)
                ideFrame {
                    editorTabs {
                        tab(CORE_MODULE_FILE, fullMatch = false).waitFound()
                    }
                }
                Thread.sleep(3_000)

                launchWasmtimeConfig(
                    driver = this,
                    wasmFileName = CORE_MODULE_FILE,
                    engine = "WASMTIME",
                )
                waitForProcessTermination(this)

                val output = readConsoleOutput()
                logOutput("Core module run output: $output")

                assertTrue(
                    output.contains("Hello from Wasm!"),
                    "Expected 'Hello from Wasm!' in output, got: $output"
                )
            }
    }

    @Test
    fun runExportedFunctionWithWasmtime() {
        val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-run-exported-function")

        context.runIdeWithDriver(runTimeout = 15.minutes) { withScreenRecording() }
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                openFile(ADD_MODULE_FILE, waitForCodeAnalysis = false, isTextEditor = false)
                ideFrame {
                    editorTabs {
                        tab(ADD_MODULE_FILE, fullMatch = false).waitFound()
                    }
                }
                Thread.sleep(3_000)

                launchWasmtimeConfig(
                    driver = this,
                    wasmFileName = ADD_MODULE_FILE,
                    engine = "WASMTIME",
                    wasmtimeOptions = "--invoke add",
                    exportName = "add",
                    programArgs = "2 3",
                )
                waitForProcessTermination(this)

                // wasmtime --invoke outputs the numeric result followed by a newline
                val output = readConsoleOutput()
                logOutput("Exported function run output: $output")

                assertTrue(
                    output.contains("5"),
                    "Expected '5' in output for add(2,3), got: $output"
                )
            }
    }

    @Test
    fun runComponentModuleWithWasmtime() {
        val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-run-component-module")

        context.runIdeWithDriver(runTimeout = 15.minutes) { withScreenRecording() }
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                openFile(COMPONENT_MODULE_FILE, waitForCodeAnalysis = false, isTextEditor = false)
                ideFrame {
                    editorTabs {
                        tab(COMPONENT_MODULE_FILE, fullMatch = false).waitFound()
                    }
                }
                Thread.sleep(3_000)

                launchWasmtimeConfig(
                    driver = this,
                    wasmFileName = COMPONENT_MODULE_FILE,
                    engine = "WASMTIME",
                )
                waitForProcessTermination(this)

                val output = readConsoleOutput()
                logOutput("Component module run output: $output")

                assertTrue(
                    output.contains("Hello from Wasm!"),
                    "Expected 'Hello from Wasm!' in output, got: $output"
                )
            }
    }

    private fun launchWasmtimeConfig(
        driver: Driver,
        wasmFileName: String,
        engine: String,
        wasmtimeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ) {
        val project = driver.singleProject()
        val helper = driver.utility(RunConfigHelperRef::class)
        helper.createAndRunWasmtime(
            project,
            wasmFileName,
            engine,
            wasmtimeOptions,
            exportName,
            programArgs,
        )
    }

    private fun waitForProcessTermination(driver: Driver) {
        val project = driver.singleProject()
        val rcm = driver.getRunContentManager(project)
        waitFor(
            message = "Waiting for process to terminate",
            timeout = 30.seconds,
            interval = 1.seconds,
        ) {
            val content = rcm.getSelectedContent()
            content?.getProcessHandler()?.isProcessTerminated() == true
        }
    }

    private fun Driver.readConsoleOutput(): String {
        var output = ""
        ideFrame {
            output = x { byClass("ConsoleViewImpl") }
                .x { byAccessibleName("Editor") }
                .getAllTexts().asString()
        }
        return output
    }

    // ---- Remote stubs ----

    @Remote(
        "org.jetbrains.hexana.run.WasmRunConfigTestHelper",
        plugin = HEXANA_PLUGIN_ID,
    )
    interface RunConfigHelperRef {
        fun createAndRunWasmtime(
            project: com.intellij.driver.sdk.Project,
            wasmFileName: String,
            engine: String,
            wasmtimeOptions: String,
            exportName: String,
            programArgs: String,
        )
    }
}
