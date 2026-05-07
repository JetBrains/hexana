package org.jetbrains.hexana.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.hexana.shared.WasmRunInput
import org.jetbrains.hexana.shared.buildWasmtimeDebugArgs
import org.jetbrains.hexana.shared.classifyDependencies
import java.io.File

/**
 * A command-line state that launches wasmtime with the `-D debug-info` flag
 * so that DWARF metadata embedded in the Wasm binary is translated for
 * native debuggers (LLDB / GDB).
 *
 * This mirrors [WasmtimeCommandLineState] but unconditionally injects the
 * debug-info option into the wasmtime invocation.
 */
class WasmtimeDebugCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val wasmtimePath = configuration.wasmtimePath.ifBlank { WasmtimeCommandLineState.resolveDefaultWasmtimePath() }
        val result = buildWasmtimeArguments(configuration, environment.project)

        val commandLine = GeneralCommandLine().apply {
            exePath = wasmtimePath
            addParameters(result.arguments)

            val workDir = configuration.workingDirectory.ifBlank { null }
            if (workDir != null) {
                withWorkDirectory(workDir)
            }

            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        }

        val handler = OSProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    companion object {
        private val LOG = Logger.getInstance(WasmtimeDebugCommandLineState::class.java)

        /**
         * Builds the wasmtime argument list for a debug run.
         *
         * This is extracted as a companion function so that [WasmDebugRunner]
         * can reuse the same argument construction when wrapping wasmtime
         * inside LLDB.
         *
         * @return a [DebugBuildResult] containing the argument list and
         *         metadata about whether component composition was used
         *         (which strips DWARF debug info).
         */
        fun buildWasmtimeArguments(configuration: WasmRunConfiguration, project: Project): DebugBuildResult {
            val wasmFile = File(configuration.wasmFilePath)
            val wasmBytes = if (wasmFile.exists()) wasmFile.readBytes() else null
            val resolvedDeps = resolveDeps(configuration, wasmBytes, project)

            val (coreModuleDeps, componentDeps) = classifyDependencies(resolvedDeps) { path ->
                val f = File(path)
                if (f.exists()) f.readBytes() else null
            }

            // Component dependencies must be composed so the binary can
            // run. Composition tools (wasm-tools compose, wac plug) strip
            // DWARF custom sections, so after composition we re-inject
            // DWARF sections from the original source files into the
            // composed binary. We track whether composition happened and
            // whether DWARF injection succeeded.
            var compositionUsed = false
            var dwarfInjected = false
            val effectiveWasmPath = if (componentDeps.isNotEmpty()) {
                val result = WacComponentComposer.compose(configuration.wasmFilePath, componentDeps)
                if (!result.composed && result.error != null) {
                    LOG.warn("Component composition failed: ${result.error}")
                } else if (result.composed) {
                    compositionUsed = true

                    // Collect all source files that may contain DWARF info:
                    // the main wasm file and all component dependencies.
                    val sourceFiles = buildList {
                        add(File(configuration.wasmFilePath))
                        for ((_, path) in componentDeps) {
                            add(File(path))
                        }
                    }

                    dwarfInjected = WasmDwarfInjector.injectDwarfSections(
                        composedFile = File(result.wasmPath),
                        sourceFiles = sourceFiles,
                    )
                    if (dwarfInjected) {
                        LOG.info(
                            "Debug run: DWARF debug info was transferred " +
                                    "into the composed binary."
                        )
                    } else {
                        LOG.info(
                            "Debug run: component composition was performed " +
                                    "but no DWARF debug info was found in " +
                                    "source files to transfer."
                        )
                    }
                }
                result.wasmPath
            } else {
                configuration.wasmFilePath
            }

            val input = WasmRunInput(
                wasmFilePath = configuration.wasmFilePath,
                wasmBytes = wasmBytes,
                programArguments = configuration.programArguments,
                runtimeOptions = configuration.wasmtimeOptions,
                effectiveWasmPath = effectiveWasmPath,
            )

            val cmdLine = buildWasmtimeDebugArgs(input, coreModuleDeps, JvmStubFileWriter)

            return DebugBuildResult(
                arguments = cmdLine.arguments,
                compositionUsed = compositionUsed,
                dwarfInjected = dwarfInjected,
            )
        }



        /**
         * Result of building wasmtime debug arguments.
         *
         * @property arguments the command-line arguments for wasmtime
         * @property compositionUsed `true` when component dependencies
         *           were composed, which strips DWARF debug info
         * @property dwarfInjected `true` when DWARF debug sections from
         *           source files were successfully injected into the
         *           composed binary
         */
        data class DebugBuildResult(
            val arguments: List<String>,
            val compositionUsed: Boolean,
            val dwarfInjected: Boolean = false,
        )
    }
}
