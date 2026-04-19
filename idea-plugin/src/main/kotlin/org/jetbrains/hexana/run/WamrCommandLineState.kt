package org.jetbrains.hexana.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.nio.file.Files

/**
 * A command-line state that launches [iwasm](https://github.com/bytecodealliance/wasm-micro-runtime)
 * to run a Wasm module.
 *
 * WAMR (WebAssembly Micro Runtime) uses `iwasm` as its CLI executable.
 * Unlike Wasmtime, WAMR does not support the Component Model or
 * `--preload` for multi-module linking, so dependency handling is
 * intentionally omitted here.
 */
class WamrCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val iwasm = configuration.wamrPath.ifBlank { resolveDefaultWamrPath() }

        val wasmFile = File(configuration.wasmFilePath)
        val wasmBytes = if (wasmFile.exists()) wasmFile.readBytes() else null

        // WAMR resolves imported sub-modules by looking for
        // <module_name>.wasm files on the filesystem relative to
        // the current working directory. Write stub modules so
        // that wasm-bindgen placeholders and similar synthetic
        // imports are satisfied.
        val stubDir = generateImportStubFiles(wasmBytes)

        val commandLine = GeneralCommandLine().apply {
            exePath = iwasm

            val options = configuration.wamrOptions.trim()
            if (options.isNotEmpty()) {
                addParameters(ParametersListUtil.parse(options))
            }

            // iwasm uses `-f <function_name>` to invoke a specific
            // exported function instead of the default entry point.
            val functionName = configuration.exportName.trim()
            if (functionName.isNotEmpty()) {
                addParameter("-f")
                addParameter(functionName)
            }

            addParameter(configuration.wasmFilePath)

            val args = configuration.programArguments.trim()
            if (args.isNotEmpty()) {
                addParameters(args.split("\\s+".toRegex()))
            }

            // When stub modules were generated, use the stub
            // directory as the working directory so WAMR can find
            // the <module_name>.wasm files. Fall back to the
            // user-configured directory, which is also where WAMR
            // would look by default.
            val workDir = stubDir?.absolutePath
                ?: configuration.workingDirectory.ifBlank { null }
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
        private val LOG = Logger.getInstance(WamrCommandLineState::class.java)

        val DEFAULT_WAMR_PATH: String = "iwasm"

        fun resolveDefaultWamrPath(): String {
            // Try common installation locations
            val candidates = listOf(
                System.getProperty("user.home") + File.separator + ".wamr" + File.separator + "bin" + File.separator + "iwasm",
                "/usr/local/bin/iwasm",
            )
            for (candidate in candidates) {
                if (File(candidate).exists()) return candidate
            }
            return DEFAULT_WAMR_PATH
        }

        /**
         * If the given Wasm binary has imports, generates stub `.wasm`
         * modules and writes them into a temporary directory. Returns
         * the directory, or `null` when no stubs are needed.
         *
         * The files are named `<module_name>.wasm` so that WAMR's
         * built-in module reader can locate them by name.
         */
        fun generateImportStubFiles(wasmBytes: ByteArray?): File? {
            if (wasmBytes == null || !WasmImportStubGenerator.hasImports(wasmBytes)) return null

            val stubs = WasmImportStubGenerator.generateStubs(wasmBytes)
            if (stubs.isEmpty()) return null

            val tempDir = Files.createTempDirectory("hexana-wamr-stubs").toFile()
            tempDir.deleteOnExit()
            for (stub in stubs) {
                val stubFile = File(tempDir, "${stub.moduleName}.wasm")
                stubFile.writeBytes(stub.wasmBytes)
                stubFile.deleteOnExit()
                LOG.info("Generated WAMR stub module: ${stubFile.absolutePath}")
            }
            return tempDir
        }
    }
}
