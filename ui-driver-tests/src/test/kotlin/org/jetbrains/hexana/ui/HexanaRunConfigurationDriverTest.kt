package org.jetbrains.hexana.ui

import com.intellij.driver.sdk.*
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.DebugTestHelperRef
import org.jetbrains.hexana.ui.framework.HexanaRunConfigurationLaunchSnapshot
import org.jetbrains.hexana.ui.framework.HexanaRunConfigurationRuntimePreflight
import org.jetbrains.hexana.ui.framework.HexanaWasmBreakpointDebugResult
import org.jetbrains.hexana.ui.framework.awaitRunConfigurationConsoleResult
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.launchNodeJsDebugRunConfiguration
import org.jetbrains.hexana.ui.framework.launchWasmtimeDebugRunConfiguration
import org.jetbrains.hexana.ui.framework.NodeJsDebugTestHelperRef
import org.jetbrains.hexana.ui.framework.runNodeJsWasm
import org.jetbrains.hexana.ui.framework.runWasm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * UI driver tests for Wasm run configurations using the Wasmtime and Node.js engines.
 *
 * Covers:
 * - Running a core module (with `_start`) from the hex view Run button
 * - Running an exported function from a core module with Wasmtime and Node.js
 * - Running a component model module (with `wasi:cli/run`) from the hex view Run button
 * - Debugging a core module (with `_start`) via the Debug executor (Wasmtime)
 * - Debugging a core module via the Debug executor (Node.js / CDP)
 * - Debugging with DWARF breakpoints and variable inspection (Wasmtime and Node.js)
 *
 * Wasmtime is downloaded automatically if not present.
 * Node.js must be pre-installed on the system.
 */
@Tag("hexana")
class HexanaRunConfigurationDriverTest {

    companion object {
        private const val CORE_MODULE_FILE = "hello_wasi.wasm"
        private const val ADD_MODULE_FILE = "add.wasm"
        private const val COMPONENT_MODULE_FILE = "hello_component.wasm"
        private const val DEBUG_DWARF_WASM_FILE = "debug-add.wasm"
        private const val DEBUG_RUST_SOURCE_FILE = "debug_add.rs"
        private const val BREAKPOINT_LINE = 10 // `let sum = a + b;`
    }

    @Test
    fun runCoreModuleWithWasmtime() {
        val wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime()
        hexanaIde(testName = "hexana-run-core-module") {
            val result = runWasm(CORE_MODULE_FILE)

            assertRunSnapshot(
                launch = result.launchSnapshot,
                projectHome = context.resolvedProjectHome,
                wasmFileName = CORE_MODULE_FILE,
                expectedEngine = "WASMTIME",
                expectedMode = "RUN",
                expectedEffectiveWasmtimePath = wasmtimePath,
                expectedEffectiveNodePath = "",
                expectedWasmtimeOptions = "",
                expectedNodeOptions = "",
                expectedExportName = "",
                expectedProgramArgs = "",
                expectedDependencies = emptyList(),
                description = "core module run",
            )

            logOutput("Core module run output: ${result.consoleOutput}")

            assertTrue(
                result.consoleOutput.contains("Hello from Wasm!"),
                "Expected 'Hello from Wasm!' in output, got: ${result.consoleOutput}",
            )
        }
    }

    @Test
    fun runExportedFunctionWithWasmtime() {
        val wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime()
        hexanaIde(testName = "hexana-run-exported-function") {
            val result = runWasm(
                wasmFileName = ADD_MODULE_FILE,
                wasmtimeOptions = "--invoke add",
                exportName = "add",
                programArgs = "2 3",
            )

            assertRunSnapshot(
                launch = result.launchSnapshot,
                projectHome = context.resolvedProjectHome,
                wasmFileName = ADD_MODULE_FILE,
                expectedEngine = "WASMTIME",
                expectedMode = "RUN",
                expectedEffectiveWasmtimePath = wasmtimePath,
                expectedEffectiveNodePath = "",
                expectedWasmtimeOptions = "--invoke add",
                expectedNodeOptions = "",
                expectedExportName = "add",
                expectedProgramArgs = "2 3",
                expectedDependencies = emptyList(),
                description = "exported-function run",
            )

            logOutput("Exported function run output: ${result.consoleOutput}")

            assertTrue(
                result.consoleOutput.contains("5"),
                "Expected '5' in output for add(2,3), got: ${result.consoleOutput}",
            )
        }
    }

    @Test
    fun runExportedFunctionWithNodeJs() {
        val nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs()
        hexanaIde(testName = "hexana-run-exported-function-nodejs") {
            val result = runNodeJsWasm(
                wasmFileName = ADD_MODULE_FILE,
                exportName = "add",
                programArgs = "2 3",
            )

            assertRunSnapshot(
                launch = result.launchSnapshot,
                projectHome = context.resolvedProjectHome,
                wasmFileName = ADD_MODULE_FILE,
                expectedEngine = "NODE_JS",
                expectedMode = "RUN",
                expectedEffectiveWasmtimePath = "",
                expectedEffectiveNodePath = nodePath,
                expectedWasmtimeOptions = "",
                expectedNodeOptions = "",
                expectedExportName = "add",
                expectedProgramArgs = "2 3",
                expectedDependencies = emptyList(),
                description = "Node.js exported-function run",
            )

            logOutput("Exported function run output: ${result.consoleOutput}")

            assertTrue(
                result.consoleOutput.contains("5"),
                "Expected '5' in output for add(2,3), got: ${result.consoleOutput}",
            )
        }
    }

    @Test
    fun runExportedFunctionWithNodeJsPreservesSavedOptionsWithoutOverride() {
        val nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs()
        hexanaIde(testName = "hexana-run-exported-function-nodejs-preserve-options") {
            val savedNodeOptions = "--no-warnings"
            val firstLaunch = runNodeJsWasm(
                wasmFileName = ADD_MODULE_FILE,
                nodeOptions = savedNodeOptions,
                exportName = "add",
                programArgs = "2 3",
            )
            assertRunSnapshot(
                launch = firstLaunch.launchSnapshot,
                projectHome = context.resolvedProjectHome,
                wasmFileName = ADD_MODULE_FILE,
                expectedEngine = "NODE_JS",
                expectedMode = "RUN",
                expectedEffectiveWasmtimePath = "",
                expectedEffectiveNodePath = nodePath,
                expectedWasmtimeOptions = "",
                expectedNodeOptions = savedNodeOptions,
                expectedExportName = "add",
                expectedProgramArgs = "2 3",
                expectedDependencies = emptyList(),
                description = "initial Node.js exported-function run",
            )

            val secondLaunch = runNodeJsWasm(
                wasmFileName = ADD_MODULE_FILE,
                exportName = "add",
                programArgs = "2 3",
            )
            assertRunSnapshot(
                launch = secondLaunch.launchSnapshot,
                projectHome = context.resolvedProjectHome,
                wasmFileName = ADD_MODULE_FILE,
                expectedEngine = "NODE_JS",
                expectedMode = "RUN",
                expectedEffectiveWasmtimePath = "",
                expectedEffectiveNodePath = nodePath,
                expectedWasmtimeOptions = "",
                expectedNodeOptions = savedNodeOptions,
                expectedExportName = "add",
                expectedProgramArgs = "2 3",
                expectedDependencies = emptyList(),
                description = "relaunch of Node.js exported-function run",
            )

            assertEquals(
                firstLaunch.launchSnapshot.configurationName,
                secondLaunch.launchSnapshot.configurationName,
                "Expected the relaunch without override to reuse the existing Node.js configuration",
            )
        }
    }

    @Test
    fun runComponentModuleWithWasmtime() {
        val wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime()
        hexanaIde(testName = "hexana-run-component-module") {
            val result = runWasm(COMPONENT_MODULE_FILE)

            assertRunSnapshot(
                launch = result.launchSnapshot,
                projectHome = context.resolvedProjectHome,
                wasmFileName = COMPONENT_MODULE_FILE,
                expectedEngine = "WASMTIME",
                expectedMode = "RUN",
                expectedEffectiveWasmtimePath = wasmtimePath,
                expectedEffectiveNodePath = "",
                expectedWasmtimeOptions = "",
                expectedNodeOptions = "",
                expectedExportName = "",
                expectedProgramArgs = "",
                expectedDependencies = emptyList(),
                description = "component-module run",
            )

            logOutput("Component module run output: ${result.consoleOutput}")

            assertTrue(
                result.consoleOutput.contains("Hello from Wasm!"),
                "Expected 'Hello from Wasm!' in output, got: ${result.consoleOutput}",
            )
        }
    }

    @Test
    fun debugCoreModuleWithWasmtime() {
        val wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime()
        val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-debug-core-module")

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

                val launch = launchWasmtimeDebugRunConfiguration(
                    wasmFileName = CORE_MODULE_FILE,
                    wasmtimePath = wasmtimePath,
                )
                assertEquals(
                    context.resolvedProjectHome.resolve(CORE_MODULE_FILE).toString().replace('\\', '/'),
                    launch.wasmFilePath.replace('\\', '/'),
                    "Expected debug launch to target $CORE_MODULE_FILE",
                )
                assertEquals(
                    context.resolvedProjectHome.toString().replace('\\', '/'),
                    launch.workingDirectory.replace('\\', '/'),
                    "Expected debug launch to record the project root as working directory",
                )
                assertEquals("WASMTIME", launch.engine, "Expected debug launch to use WASMTIME")
                assertEquals("DEBUG", launch.mode, "Expected debug launch to use DEBUG mode")
                assertTrue(launch.debugSessionObserved, "Expected the debug launch to observe a debugger session")
                assertEquals(launch.configurationName, launch.selectedConfigurationName, "Expected debug launch to select its production-created configuration")
                assertTrue(
                    launch.selectedConfigurationType.contains("WasmRunConfiguration"),
                    "Expected a WasmRunConfiguration selected by the debug launch, got ${launch.selectedConfigurationType}",
                )
                assertEquals(wasmtimePath, launch.effectiveWasmtimePath, "Expected debug launch to record the injected Wasmtime path")
                assertEquals("", launch.wasmtimeOptions, "Expected the debug launch to omit Wasmtime options")
                assertEquals("", launch.exportName, "Expected the debug launch to omit an export name")
                assertEquals("", launch.programArguments, "Expected the debug launch to omit program arguments")
                assertEquals(emptyList<String>(), launch.dependencies, "Expected hello_wasi.wasm to resolve no dependencies")
                val output = awaitRunConfigurationConsoleResult(5.minutes).consoleOutput
                logOutput("Core module debug output: $output")

                assertTrue(
                    output.contains("Hello from Wasm!"),
                    "Expected 'Hello from Wasm!' in debug output, got: $output"
                )
            }
    }

    @Test
    fun debugCoreModuleWithNodeJs() {
        val nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs()
        val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-debug-core-module-nodejs")

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

                val launch = launchNodeJsDebugRunConfiguration(
                    wasmFileName = ADD_MODULE_FILE,
                    nodePath = nodePath,
                    nodeOptions = "",
                    exportName = "add",
                    programArgs = "2 3",
                )
                assertEquals(
                    context.resolvedProjectHome.resolve(ADD_MODULE_FILE).toString().replace('\\', '/'),
                    launch.wasmFilePath.replace('\\', '/'),
                    "Expected Node.js debug launch to target $ADD_MODULE_FILE",
                )
                assertEquals(
                    context.resolvedProjectHome.toString().replace('\\', '/'),
                    launch.workingDirectory.replace('\\', '/'),
                    "Expected Node.js debug launch to record the project root as working directory",
                )
                assertEquals("NODE_JS", launch.engine, "Expected Node.js debug launch to use NODE_JS")
                assertEquals("DEBUG", launch.mode, "Expected Node.js debug launch to use DEBUG mode")
                assertTrue(launch.debugSessionObserved, "Expected the Node.js debug launch to observe a debugger session")
                assertEquals(
                    launch.configurationName,
                    launch.selectedConfigurationName,
                    "Expected Node.js debug launch to select its production-created configuration",
                )
                assertTrue(
                    launch.selectedConfigurationType.contains("WasmRunConfiguration"),
                    "Expected a WasmRunConfiguration selected by the Node.js debug launch, got ${launch.selectedConfigurationType}",
                )
                assertEquals(nodePath, launch.effectiveNodePath, "Expected Node.js debug launch to record the injected Node.js path")
                assertEquals("", launch.wasmtimeOptions, "Expected Node.js debug launch to leave Wasmtime options blank")
                assertEquals("", launch.nodeOptions, "Expected Node.js debug launch to omit Node.js options")
                assertEquals("add", launch.exportName, "Expected Node.js debug launch to preserve the export name")
                assertEquals("2 3", launch.programArguments, "Expected Node.js debug launch to preserve program arguments")
                assertEquals(emptyList<String>(), launch.dependencies, "Expected add.wasm to resolve no dependencies")
                val output = awaitRunConfigurationConsoleResult(5.minutes).consoleOutput
                logOutput("Node.js core module debug output: $output")

                assertTrue(
                    output.contains("5"),
                    "Expected '5' in Node.js debug output for add(2,3), got: $output"
                )
            }
    }

    @Test
    fun debugDwarfWasmBreakpointAndInspectVariablesWithNodeJs() {
        val nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs()
        val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-debug-dwarf-breakpoint-nodejs")

        context.runIdeWithDriver(runTimeout = 15.minutes) { withScreenRecording() }
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                // Open the DWARF wasm file first so the editor is ready
                openFile(DEBUG_DWARF_WASM_FILE, waitForCodeAnalysis = false, isTextEditor = false)
                ideFrame {
                    editorTabs {
                        tab(DEBUG_DWARF_WASM_FILE, fullMatch = false).waitFound()
                    }
                }
                Thread.sleep(3_000)

                // Call the helper that sets a breakpoint in the Rust source,
                // launches debug via Node.js CDP, waits for the breakpoint hit,
                // collects variables, and resumes.
                val project = singleProject()
                val helper = utility(NodeJsDebugTestHelperRef::class)
                val result = helper.debugNodeJsWithBreakpointAndInspectVariables(
                    project,
                    wasmFileName = DEBUG_DWARF_WASM_FILE,
                    sourceFileName = DEBUG_RUST_SOURCE_FILE,
                    breakpointLine = BREAKPOINT_LINE,
                    engine = "NODE_JS",
                    nodeOptions = "",
                    exportName = "add",
                    programArgs = "3 7",
                    nodePath = nodePath,
                )

                logOutput("Node.js DWARF breakpoint debug result:\n$result")
                val breakpointResult = HexanaWasmBreakpointDebugResult.parse(result)
                logOutput("Parsed breakpoint variables: ${breakpointResult}")
                breakpointResult.assertStrict()
            }
    }

    @Test
    fun debugDwarfWasmBreakpointAndInspectVariables() {
        val wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime()
        val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-debug-dwarf-breakpoint")

        context.runIdeWithDriver(runTimeout = 15.minutes) { withScreenRecording() }
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                // Open the DWARF wasm file first so the editor is ready
                openFile(DEBUG_DWARF_WASM_FILE, waitForCodeAnalysis = false, isTextEditor = false)
                ideFrame {
                    editorTabs {
                        tab(DEBUG_DWARF_WASM_FILE, fullMatch = false).waitFound()
                    }
                }
                Thread.sleep(3_000)

                // Call the helper that sets a breakpoint in the Rust source,
                // launches debug, waits for the breakpoint hit, collects
                // variables, and resumes.
                val project = singleProject()
                val helper = utility(DebugTestHelperRef::class)
                val result = helper.debugWithBreakpointAndInspectVariables(
                    project,
                    wasmFileName = DEBUG_DWARF_WASM_FILE,
                    sourceFileName = DEBUG_RUST_SOURCE_FILE,
                    breakpointLine = BREAKPOINT_LINE,
                    engine = "WASMTIME",
                    wasmtimeOptions = "--invoke add",
                    exportName = "add",
                    programArgs = "3 7",
                    wasmtimePath = wasmtimePath,
                )

                logOutput("DWARF breakpoint debug result:\n$result")
                val breakpointResult = HexanaWasmBreakpointDebugResult.parse(result)
                logOutput("Parsed breakpoint variables: ${breakpointResult}")
                breakpointResult.assertStrict()
            }
    }

    private fun assertRunSnapshot(
        launch: HexanaRunConfigurationLaunchSnapshot,
        projectHome: Path,
        wasmFileName: String,
        expectedEngine: String,
        expectedMode: String,
        expectedEffectiveWasmtimePath: String,
        expectedEffectiveNodePath: String,
        expectedWasmtimeOptions: String,
        expectedNodeOptions: String,
        expectedExportName: String,
        expectedProgramArgs: String,
        expectedDependencies: List<String>,
        description: String,
    ) {
        assertEquals(
            projectHome.resolve(wasmFileName).toString().replace('\\', '/'),
            launch.wasmFilePath.replace('\\', '/'),
            "Expected $description to target $wasmFileName",
        )
        assertEquals(
            projectHome.toString().replace('\\', '/'),
            launch.workingDirectory.replace('\\', '/'),
            "Expected $description to record the project root as working directory",
        )
        assertEquals(expectedEngine, launch.engine, "Expected $description to use $expectedEngine")
        assertEquals(expectedMode, launch.mode, "Expected $description to use $expectedMode mode")
        assertEquals(
            launch.configurationName,
            launch.selectedConfigurationName,
            "Expected $description to select its production-created configuration",
        )
        assertTrue(
            launch.selectedConfigurationType.contains("WasmRunConfiguration"),
            "Expected a WasmRunConfiguration selected by the $description launch, got ${launch.selectedConfigurationType}",
        )
        assertEquals(
            expectedEffectiveWasmtimePath,
            launch.effectiveWasmtimePath,
            "Expected $description to record the injected Wasmtime path",
        )
        assertEquals(
            expectedEffectiveNodePath,
            launch.effectiveNodePath,
            "Expected $description to record the injected Node.js path",
        )
        assertEquals(
            expectedWasmtimeOptions,
            launch.wasmtimeOptions,
            "Expected $description to preserve Wasmtime options",
        )
        assertEquals(
            expectedNodeOptions,
            launch.nodeOptions,
            "Expected $description to preserve Node.js options",
        )
        assertEquals(
            expectedExportName,
            launch.exportName,
            "Expected $description to preserve the export name",
        )
        assertEquals(
            expectedProgramArgs,
            launch.programArguments,
            "Expected $description to preserve program arguments",
        )
        assertEquals(
            expectedDependencies,
            launch.dependencies,
            "Expected $description to resolve the expected dependencies",
        )
    }
}
