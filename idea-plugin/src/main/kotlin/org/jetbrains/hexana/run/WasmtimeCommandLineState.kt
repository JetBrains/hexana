package org.jetbrains.hexana.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.execution.ParametersListUtil
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
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
        val resolvedDeps = resolveDeps(wasmBytes)

        val coreModuleDeps = mutableMapOf<String, String>()
        val componentDeps = mutableMapOf<String, String>()
        for ((name, path) in resolvedDeps) {
            val depFile = File(path)
            if (depFile.exists()) {
                when (WasmBinaryProviderSelector.select(depFile.readBytes().toCommonByteBuffer())) {
                    is BinaryProviderType.Wasm -> coreModuleDeps[name] = path
                    is BinaryProviderType.WasmComponent -> componentDeps[name] = path
                    else -> {}
                }
            }
        }

        val effectiveWasmPath = if (componentDeps.isNotEmpty()) {
            val result = WacComponentComposer.compose(configuration.wasmFilePath, componentDeps)
            if (!result.composed && result.error != null) {
                LOG.warn("Component composition failed: ${result.error}")
            }
            result.wasmPath
        } else {
            configuration.wasmFilePath
        }

        val commandLine = GeneralCommandLine().apply {
            exePath = wasmtimePath
            addParameter("run")

            val options = configuration.wasmtimeOptions.trim()
            if (options.isNotEmpty()) {
                addParameters(ParametersListUtil.parse(options))
            }

            for ((name, path) in coreModuleDeps) {
                addParameter("--preload")
                addParameter("$name=$path")
            }

            if (wasmBytes != null && WasmImportStubGenerator.hasImports(wasmBytes)) {
                addParameter("-W")
                addParameter("unknown-imports-default")

                if (WasmImportStubGenerator.hasNonFunctionImports(wasmBytes)) {
                    val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasmBytes)
                        .filter { it.moduleName !in coreModuleDeps.keys }
                    if (stubs.isNotEmpty()) {
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
            }

            addParameter(effectiveWasmPath)

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

    /**
     * Resolves dependencies from the configuration, falling back to
     * [WasmDependencyResolver] when the stored list is empty so that
     * runs triggered from the IDE toolbar (which bypass [doRun]) still
     * compose component dependencies via WAC.
     */
    private fun resolveDeps(wasmBytes: ByteArray?): Map<String, String> {
        val stored = configuration.dependencies
            .mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
        if (stored.isNotEmpty() || wasmBytes == null) return stored

        val project = environment.project
        val vFile = LocalFileSystem.getInstance().findFileByPath(configuration.wasmFilePath)
        return WasmDependencyResolver.resolveDependencies(wasmBytes, project, excludeFile = vFile)
    }

    companion object {
        private val LOG = Logger.getInstance(WasmtimeCommandLineState::class.java)

        val DEFAULT_WASMTIME_PATH: String =
            System.getProperty("user.home") + File.separator + ".wasmtime" + File.separator + "bin" + File.separator + "wasmtime"

        fun resolveDefaultWasmtimePath(): String {
            val defaultPath = DEFAULT_WASMTIME_PATH
            return if (File(defaultPath).exists()) defaultPath else "wasmtime"
        }
    }
}
