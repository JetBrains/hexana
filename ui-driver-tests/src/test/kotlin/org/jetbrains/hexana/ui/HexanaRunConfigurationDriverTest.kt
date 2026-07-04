package org.jetbrains.hexana.ui

import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaRunConfigurationLaunchSnapshot
import org.jetbrains.hexana.ui.framework.HexanaRunConfigurationRuntimePreflight
import org.jetbrains.hexana.ui.framework.HexanaWasmBreakpointDebugResult
import org.jetbrains.hexana.ui.framework.debugNodeJsWasm
import org.jetbrains.hexana.ui.framework.debugNodeJsWasmWithBreakpoint
import org.jetbrains.hexana.ui.framework.debugWasmWithBreakpoint
import org.jetbrains.hexana.ui.framework.debugWasm
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.runNodeJsWasm
import org.jetbrains.hexana.ui.framework.runWasm
import org.jetbrains.hexana.ui.framework.withWasmRunDsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

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
                nodeOptions = "",
                exportName = "add",
                programArgs = "2 3",
            )

            assertRunSnapshot(
                launch = result.launchSnapshot,
                projectHome = context.resolvedProjectHome,
                wasmFileName = ADD_MODULE_FILE,
                expectedEngine = "NODE_JS",
                expectedMode = "RUN",
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
            withWasmRunDsl(ADD_MODULE_FILE) {
                clearNodeJsWasmRunConfigurations(
                    wasmFileName = ADD_MODULE_FILE,
                    exportName = "add",
                )

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
                    nodeOptions = null,
                    exportName = "add",
                    programArgs = "2 3",
                )
                assertRunSnapshot(
                    launch = secondLaunch.launchSnapshot,
                    projectHome = context.resolvedProjectHome,
                    wasmFileName = ADD_MODULE_FILE,
                    expectedEngine = "NODE_JS",
                    expectedMode = "RUN",
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
        hexanaIde(testName = "hexana-debug-core-module") {
            val result = debugWasm(CORE_MODULE_FILE)

            assertRunSnapshot(
                launch = result.launchSnapshot,
                projectHome = context.resolvedProjectHome,
                wasmFileName = CORE_MODULE_FILE,
                expectedEngine = "WASMTIME",
                expectedMode = "DEBUG",
                expectedEffectiveWasmtimePath = wasmtimePath,
                expectedWasmtimeOptions = "",
                expectedNodeOptions = "",
                expectedExportName = "",
                expectedProgramArgs = "",
                expectedDependencies = emptyList(),
                expectedDebugSessionObserved = true,
                description = "core module debug",
            )

            logOutput("Core module debug output: ${result.consoleOutput}")

            assertTrue(
                result.consoleOutput.contains("Hello from Wasm!"),
                "Expected 'Hello from Wasm!' in debug output, got: ${result.consoleOutput}",
            )
        }
    }

    @Test
    fun debugCoreModuleWithNodeJs() {
        val nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs()
        hexanaIde(testName = "hexana-debug-core-module-nodejs") {
            val result = debugNodeJsWasm(
                wasmFileName = ADD_MODULE_FILE,
                nodeOptions = "",
                exportName = "add",
                programArgs = "2 3",
            )

            assertRunSnapshot(
                launch = result.launchSnapshot,
                projectHome = context.resolvedProjectHome,
                wasmFileName = ADD_MODULE_FILE,
                expectedEngine = "NODE_JS",
                expectedMode = "DEBUG",
                expectedEffectiveNodePath = nodePath,
                expectedWasmtimeOptions = "",
                expectedNodeOptions = "",
                expectedExportName = "add",
                expectedProgramArgs = "2 3",
                expectedDependencies = emptyList(),
                expectedDebugSessionObserved = true,
                description = "Node.js core module debug",
            )

            logOutput("Node.js core module debug output: ${result.consoleOutput}")

            assertTrue(
                result.consoleOutput.contains("5"),
                "Expected '5' in Node.js debug output for add(2,3), got: ${result.consoleOutput}",
            )
        }
    }

    @Test
    fun debugDwarfWasmBreakpointAndInspectVariablesWithNodeJs() {
        hexanaIde(testName = "hexana-debug-dwarf-breakpoint-nodejs") {
            val result = debugNodeJsWasmWithBreakpoint(
                wasmFileName = DEBUG_DWARF_WASM_FILE,
                sourceFileName = DEBUG_RUST_SOURCE_FILE,
                breakpointLine = BREAKPOINT_LINE,
                nodeOptions = "",
                exportName = "add",
                programArgs = "3 7",
            )

            logOutput("Node.js DWARF breakpoint debug result:\n${result.rawOutput}")
            result.assertStrict()
        }
    }

    @Test
    fun debugDwarfWasmBreakpointAndInspectVariables() {
        hexanaIde(testName = "hexana-debug-dwarf-breakpoint") {
            val result = debugWasmWithBreakpoint(
                wasmFileName = DEBUG_DWARF_WASM_FILE,
                sourceFileName = DEBUG_RUST_SOURCE_FILE,
                breakpointLine = BREAKPOINT_LINE,
                wasmtimeOptions = "--invoke add",
                exportName = "add",
                programArgs = "3 7",
            )

            logOutput("DWARF breakpoint debug result:\n${result.rawOutput}")
            result.assertStrict()
        }
    }

    private fun assertRunSnapshot(
        launch: HexanaRunConfigurationLaunchSnapshot,
        projectHome: Path,
        wasmFileName: String,
        expectedEngine: String,
        expectedMode: String,
        expectedEffectiveWasmtimePath: String? = null,
        expectedEffectiveNodePath: String? = null,
        expectedWasmtimeOptions: String,
        expectedNodeOptions: String,
        expectedExportName: String,
        expectedProgramArgs: String,
        expectedDependencies: List<String>,
        expectedDebugSessionObserved: Boolean? = null,
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
        if (expectedEffectiveWasmtimePath != null) {
            assertEquals(
                expectedEffectiveWasmtimePath,
                launch.effectiveWasmtimePath,
                "Expected $description to record the injected Wasmtime path",
            )
        }
        if (expectedEffectiveNodePath != null) {
            assertEquals(
                expectedEffectiveNodePath,
                launch.effectiveNodePath,
                "Expected $description to record the injected Node.js path",
            )
        }
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
        if (expectedDebugSessionObserved != null) {
            assertEquals(
                expectedDebugSessionObserved,
                launch.debugSessionObserved,
                "Expected $description to observe debug session=$expectedDebugSessionObserved",
            )
        }
    }
}
