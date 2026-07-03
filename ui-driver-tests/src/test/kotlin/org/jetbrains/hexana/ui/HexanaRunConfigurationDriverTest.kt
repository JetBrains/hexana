package org.jetbrains.hexana.ui

import com.intellij.driver.sdk.*
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.DebugTestHelperRef
import org.jetbrains.hexana.ui.framework.HexanaRunConfigurationRuntimePreflight
import org.jetbrains.hexana.ui.framework.HexanaWasmBreakpointDebugResult
import org.jetbrains.hexana.ui.framework.awaitRunConfigurationConsoleResult
import org.jetbrains.hexana.ui.framework.launchNodeJsDebugRunConfiguration
import org.jetbrains.hexana.ui.framework.launchWasmtimeDebugRunConfiguration
import org.jetbrains.hexana.ui.framework.launchNodeJsRunConfiguration
import org.jetbrains.hexana.ui.framework.launchWasmtimeRunConfiguration
import org.jetbrains.hexana.ui.framework.NodeJsDebugTestHelperRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
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

                val launch = launchWasmtimeRunConfiguration(
                    wasmFileName = CORE_MODULE_FILE,
                    wasmtimePath = wasmtimePath,
                )
                assertEquals(
                    context.resolvedProjectHome.resolve(CORE_MODULE_FILE).toString().replace('\\', '/'),
                    launch.wasmFilePath.replace('\\', '/'),
                    "Expected production launch to target $CORE_MODULE_FILE",
                )
                assertEquals(
                    context.resolvedProjectHome.toString().replace('\\', '/'),
                    launch.workingDirectory.replace('\\', '/'),
                    "Expected production launch to record the project root as working directory",
                )
                assertEquals("WASMTIME", launch.engine, "Expected production launch to use WASMTIME")
                assertEquals("RUN", launch.mode, "Expected production launch to use RUN mode")
                assertEquals(launch.configurationName, launch.selectedConfigurationName, "Expected selected configuration to match the production-created configuration")
                assertTrue(
                    launch.selectedConfigurationType.contains("WasmRunConfiguration"),
                    "Expected a WasmRunConfiguration selected by the production launch, got ${launch.selectedConfigurationType}",
                )
                assertEquals(wasmtimePath, launch.effectiveWasmtimePath, "Expected production launch probe to record the injected Wasmtime path")
                assertEquals("", launch.wasmtimeOptions, "Expected the core module run to omit Wasmtime options")
                assertEquals("", launch.exportName, "Expected the core module run to omit an export name")
                assertEquals("", launch.programArguments, "Expected the core module run to omit program arguments")
                val output = awaitRunConfigurationConsoleResult().consoleOutput
                logOutput("Core module run output: $output")

                assertTrue(
                    output.contains("Hello from Wasm!"),
                    "Expected 'Hello from Wasm!' in output, got: $output"
                )
            }
    }

    @Test
    fun runExportedFunctionWithWasmtime() {
        val wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime()
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

                val launch = launchWasmtimeRunConfiguration(
                    wasmFileName = ADD_MODULE_FILE,
                    wasmtimePath = wasmtimePath,
                    wasmtimeOptions = "--invoke add",
                    exportName = "add",
                    programArgs = "2 3",
                )
                assertEquals(
                    context.resolvedProjectHome.resolve(ADD_MODULE_FILE).toString().replace('\\', '/'),
                    launch.wasmFilePath.replace('\\', '/'),
                    "Expected exported-function run to target $ADD_MODULE_FILE",
                )
                assertEquals(
                    context.resolvedProjectHome.toString().replace('\\', '/'),
                    launch.workingDirectory.replace('\\', '/'),
                    "Expected exported-function run to record the project root as working directory",
                )
                assertEquals("WASMTIME", launch.engine, "Expected exported-function run to use WASMTIME")
                assertEquals("RUN", launch.mode, "Expected exported-function run to use RUN mode")
                assertEquals(launch.configurationName, launch.selectedConfigurationName, "Expected exported-function run to select its production-created configuration")
                assertTrue(
                    launch.selectedConfigurationType.contains("WasmRunConfiguration"),
                    "Expected a WasmRunConfiguration selected by the exported-function launch, got ${launch.selectedConfigurationType}",
                )
                assertEquals(wasmtimePath, launch.effectiveWasmtimePath, "Expected exported-function run to record the injected Wasmtime path")
                assertEquals("--invoke add", launch.wasmtimeOptions, "Expected exported-function run to preserve Wasmtime options")
                assertEquals("", launch.nodeOptions, "Expected exported-function run to leave Node.js options blank")
                assertEquals("add", launch.exportName, "Expected exported-function run to preserve the export name")
                assertEquals("2 3", launch.programArguments, "Expected exported-function run to preserve program arguments")
                assertEquals(emptyList<String>(), launch.dependencies, "Expected add.wasm to resolve no dependencies")
                val output = awaitRunConfigurationConsoleResult().consoleOutput
                logOutput("Exported function run output: $output")

                assertTrue(
                    output.contains("5"),
                    "Expected '5' in output for add(2,3), got: $output"
                )
            }
    }

    @Test
    fun runExportedFunctionWithNodeJs() {
        val nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs()
        val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-run-exported-function-nodejs")

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

                val launch = launchNodeJsRunConfiguration(
                    wasmFileName = ADD_MODULE_FILE,
                    nodePath = nodePath,
                    nodeOptions = "",
                    exportName = "add",
                    programArgs = "2 3",
                )
                assertEquals(
                    context.resolvedProjectHome.resolve(ADD_MODULE_FILE).toString().replace('\\', '/'),
                    launch.wasmFilePath.replace('\\', '/'),
                    "Expected exported-function run to target $ADD_MODULE_FILE",
                )
                assertEquals(
                    context.resolvedProjectHome.toString().replace('\\', '/'),
                    launch.workingDirectory.replace('\\', '/'),
                    "Expected exported-function run to record the project root as working directory",
                )
                assertEquals("NODE_JS", launch.engine, "Expected exported-function run to use NODE_JS")
                assertEquals("RUN", launch.mode, "Expected exported-function run to use RUN mode")
                assertEquals(launch.configurationName, launch.selectedConfigurationName, "Expected exported-function run to select its production-created configuration")
                assertTrue(
                    launch.selectedConfigurationType.contains("WasmRunConfiguration"),
                    "Expected a WasmRunConfiguration selected by the exported-function launch, got ${launch.selectedConfigurationType}",
                )
                assertEquals(nodePath, launch.effectiveNodePath, "Expected exported-function run to record the injected Node.js path")
                assertEquals("", launch.wasmtimeOptions, "Expected exported-function run to leave Wasmtime options blank")
                assertEquals("", launch.nodeOptions, "Expected exported-function run to omit Node.js options")
                assertEquals("add", launch.exportName, "Expected exported-function run to preserve the export name")
                assertEquals("2 3", launch.programArguments, "Expected exported-function run to preserve program arguments")
                assertEquals(emptyList<String>(), launch.dependencies, "Expected add.wasm to resolve no dependencies")
                val output = awaitRunConfigurationConsoleResult().consoleOutput
                logOutput("Exported function run output: $output")

                assertTrue(
                    output.contains("5"),
                    "Expected '5' in output for add(2,3), got: $output"
                )
            }
    }

    @Test
    fun runExportedFunctionWithNodeJsPreservesSavedOptionsWithoutOverride() {
        val nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs()
        val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-run-exported-function-nodejs-preserve-options")

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

                val savedNodeOptions = "--no-warnings"
                val firstLaunch = launchNodeJsRunConfiguration(
                    wasmFileName = ADD_MODULE_FILE,
                    nodePath = nodePath,
                    nodeOptions = savedNodeOptions,
                    exportName = "add",
                    programArgs = "2 3",
                )
                assertEquals(savedNodeOptions, firstLaunch.nodeOptions, "Expected the initial Node.js launch to save its explicit options")

                val secondLaunch = launchNodeJsRunConfiguration(
                    wasmFileName = ADD_MODULE_FILE,
                    nodePath = nodePath,
                    exportName = "add",
                    programArgs = "2 3",
                )
                assertEquals(savedNodeOptions, secondLaunch.nodeOptions, "Expected the relaunch without override to preserve saved Node.js options")
                assertEquals(
                    firstLaunch.configurationName,
                    secondLaunch.configurationName,
                    "Expected the relaunch without override to reuse the existing Node.js configuration",
                )
            }
    }

    @Test
    fun runComponentModuleWithWasmtime() {
        val wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime()
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

                val launch = launchWasmtimeRunConfiguration(
                    wasmFileName = COMPONENT_MODULE_FILE,
                    wasmtimePath = wasmtimePath,
                )
                assertEquals(
                    context.resolvedProjectHome.resolve(COMPONENT_MODULE_FILE).toString().replace('\\', '/'),
                    launch.wasmFilePath.replace('\\', '/'),
                    "Expected component-module run to target $COMPONENT_MODULE_FILE",
                )
                assertEquals(
                    context.resolvedProjectHome.toString().replace('\\', '/'),
                    launch.workingDirectory.replace('\\', '/'),
                    "Expected component-module run to record the project root as working directory",
                )
                assertEquals("WASMTIME", launch.engine, "Expected component-module run to use WASMTIME")
                assertEquals("RUN", launch.mode, "Expected component-module run to use RUN mode")
                assertEquals(launch.configurationName, launch.selectedConfigurationName, "Expected component-module run to select its production-created configuration")
                assertTrue(
                    launch.selectedConfigurationType.contains("WasmRunConfiguration"),
                    "Expected a WasmRunConfiguration selected by the component-module launch, got ${launch.selectedConfigurationType}",
                )
                assertEquals(wasmtimePath, launch.effectiveWasmtimePath, "Expected component-module run to record the injected Wasmtime path")
                assertEquals("", launch.wasmtimeOptions, "Expected the component-module run to omit Wasmtime options")
                assertEquals("", launch.exportName, "Expected the component-module run to omit an export name")
                assertEquals("", launch.programArguments, "Expected the component-module run to omit program arguments")
                assertEquals(emptyList<String>(), launch.dependencies, "Expected hello_component.wasm to resolve no dependencies")
                val output = awaitRunConfigurationConsoleResult().consoleOutput
                logOutput("Component module run output: $output")

                assertTrue(
                    output.contains("Hello from Wasm!"),
                    "Expected 'Hello from Wasm!' in output, got: $output"
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
}
