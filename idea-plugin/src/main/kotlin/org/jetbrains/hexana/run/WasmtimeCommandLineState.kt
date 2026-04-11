package org.jetbrains.hexana.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import java.io.File
import java.nio.file.Files

class WasmtimeCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val wasmtimePath = configuration.wasmtimePath.ifBlank { resolveDefaultWasmtimePath() }

        val wasmFile = File(configuration.wasmFilePath)
        val wasmBytes = if (wasmFile.exists()) wasmFile.readBytes() else null

        val commandLine = GeneralCommandLine().apply {
            exePath = wasmtimePath
            addParameter("run")

            val options = configuration.wasmtimeOptions.trim()
            if (options.isNotEmpty()) {
                addParameters(options.split("\\s+".toRegex()))
            }

            if (wasmBytes != null && WasmImportStubGenerator.hasImports(wasmBytes)) {
                addParameter("-W")
                addParameter("unknown-imports-default")

                if (WasmImportStubGenerator.hasNonFunctionImports(wasmBytes)) {
                    val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasmBytes)
                    val tempDir = Files.createTempDirectory("hexana-wasm-stubs").toFile()
                    tempDir.deleteOnExit()
                    for (stub in stubs) {
                        val stubFile = File(tempDir, "${stub.moduleName}.wasm")
                        stubFile.writeBytes(stub.wasmBytes)
                        stubFile.deleteOnExit()
                        addParameter("--preload")
                        addParameter("${stub.moduleName}=${stubFile.absolutePath}")
                    }
                }
            }

            addParameter(configuration.wasmFilePath)

            val args = configuration.programArguments.trim()
            if (args.isNotEmpty()) {
                addParameters(args.split("\\s+".toRegex()))
            }

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
        val DEFAULT_WASMTIME_PATH: String =
            System.getProperty("user.home") + File.separator + ".wasmtime" + File.separator + "bin" + File.separator + "wasmtime"

        fun resolveDefaultWasmtimePath(): String {
            val defaultPath = DEFAULT_WASMTIME_PATH
            return if (File(defaultPath).exists()) defaultPath else "wasmtime"
        }
    }
}
