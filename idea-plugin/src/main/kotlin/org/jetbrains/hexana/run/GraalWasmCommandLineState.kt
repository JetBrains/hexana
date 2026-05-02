package org.jetbrains.hexana.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.util.SystemInfo
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.wasm.sections.*
import java.io.File
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class GraalWasmCommandLineState(
    private val configuration: WasmRunConfiguration,
    private val environment: ExecutionEnvironment,
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val graalVmHome = resolveGraalVmHome()
        if (graalVmHome != null && executor != null) {
            return ExternalGraalWasmCommandLineState(configuration, environment, graalVmHome)
                .execute(executor, runner)!!
        }

        val consoleView = ConsoleViewImpl(environment.project, false)
        val handler = GraalWasmProcessHandler(configuration)
        consoleView.attachToProcess(handler)
        return DefaultExecutionResult(consoleView, handler)
    }

    private fun resolveGraalVmHome(): String? {
        val perConfig = configuration.graalVmPath.ifBlank { null }
        if (perConfig != null) return perConfig
        return WasmRuntimeSettings.getInstance().effectiveGraalVmPath()
    }
}

/**
 * Launches the `wasm` launcher from a custom GraalVM installation
 * as an external process, similar to how [WasmtimeCommandLineState]
 * launches the Wasmtime CLI.
 */
private class ExternalGraalWasmCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
    private val graalVmHome: String,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val exeName = if (SystemInfo.isWindows) "wasm.exe" else "wasm"
        val wasmLauncher = File(graalVmHome, "bin" + File.separator + exeName).absolutePath

        val commandLine = GeneralCommandLine().apply {
            exePath = wasmLauncher

            val targetExport = configuration.exportName.ifBlank { null }
            if (targetExport != null) {
                addParameter("--entry-point")
                addParameter(targetExport)
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
}

class GraalWasmProcessHandler(
    private val configuration: WasmRunConfiguration,
) : ProcessHandler() {

    @Volatile
    private var wasmContext: Context? = null

    override fun startNotify() {
        super.startNotify()
        Thread({
            try {
                runWasmModule()
                notifyProcessTerminated(0)
            } catch (e: Exception) {
                notifyTextAvailable("Error: ${e.message}\n", ProcessOutputType.STDERR)
                notifyProcessTerminated(1)
            }
        }, "GraalWasm-Runner").start()
    }

    private fun runWasmModule() {
        val wasmFile = File(configuration.wasmFilePath)
        if (!wasmFile.exists()) {
            notifyTextAvailable("Error: Wasm file not found: ${configuration.wasmFilePath}\n", ProcessOutputType.STDERR)
            return
        }

        val wasmBytes = wasmFile.readBytes()
        val binaryType = WasmBinaryProviderSelector.select(wasmBytes.toCommonByteBuffer())
        if (binaryType is BinaryProviderType.WasmComponent) {
            notifyTextAvailable(
                "Error: GraalWasm does not support the WebAssembly Component Model.\n" +
                        "The file '${wasmFile.name}' is a component (binary version 0x0D). " +
                        "Please use the Wasmtime engine to run component modules.\n",
                ProcessOutputType.STDERR,
            )
            return
        }

        val resolvedDeps = configuration.dependencies
            .mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
        val coreModuleDeps = resolvedDeps.filter { (_, path) ->
            val depFile = File(path)
            if (depFile.exists()) {
                val depType = WasmBinaryProviderSelector.select(depFile.readBytes().toCommonByteBuffer())
                depType is BinaryProviderType.Wasm
            } else {
                false
            }
        }
        val resolvedCoreModuleNames = coreModuleDeps.keys

        val stdoutPipe = PipedOutputStream()
        val stdoutInput = PipedInputStream(stdoutPipe)
        val stderrPipe = PipedOutputStream()
        val stderrInput = PipedInputStream(stderrPipe)

        val stdoutReader = Thread({
            stdoutInput.bufferedReader().forEachLine { line ->
                notifyTextAvailable(line + "\n", ProcessOutputType.STDOUT)
            }
        }, "GraalWasm-stdout")

        val stderrReader = Thread({
            stderrInput.bufferedReader().forEachLine { line ->
                notifyTextAvailable(line + "\n", ProcessOutputType.STDERR)
            }
        }, "GraalWasm-stderr")

        stdoutReader.isDaemon = true
        stderrReader.isDaemon = true
        stdoutReader.start()
        stderrReader.start()

        val programArgs = configuration.programArguments.trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }

        val targetExport = configuration.exportName.ifBlank { null }

        val contextBuilder = Context.newBuilder("wasm")
            .option("engine.WarnInterpreterOnly", "false")
            .out(stdoutPipe)
            .err(stderrPipe)
        if (targetExport == null) {
            contextBuilder.arguments("wasm", programArgs.toTypedArray())
        }
        val context = contextBuilder.build()

        wasmContext = context
        context.use { ctx ->
            val moduleMap = mutableMapOf<String, Any>()

            for ((name, path) in coreModuleDeps) {
                val depFile = File(path)
                if (depFile.exists()) {
                    val depSource = Source.newBuilder("wasm", depFile)
                        .name(name)
                        .build()
                    val depModule = ctx.eval(depSource)
                    val depInstance = depModule.newInstance()
                    moduleMap[name] = depInstance.getMember("exports")
                }
            }

            val stubs = WasmImportStubGenerator.generateStubs(wasmBytes)
                .filter { it.moduleName !in resolvedCoreModuleNames }
            for (stub in stubs) {
                val stubSource = Source.newBuilder("wasm", org.graalvm.polyglot.io.ByteSequence.create(stub.wasmBytes), stub.moduleName)
                    .build()
                val stubModule = ctx.eval(stubSource)
                val stubInstance = stubModule.newInstance()
                moduleMap[stub.moduleName] = stubInstance.getMember("exports")
            }

            val importObject = ProxyObject.fromMap(moduleMap)

            val source = Source.newBuilder("wasm", wasmFile)
                .name(wasmFile.nameWithoutExtension)
                .build()
            val mainModule = ctx.eval(source)
            val mainInstance = mainModule.newInstance(importObject)
            val exports = mainInstance.getMember("exports")

            if (targetExport != null) {
                val func = exports.getMember(targetExport)
                if (func != null && func.canExecute()) {
                    val typedArgs = convertArgsToWasmTypes(wasmBytes, targetExport, programArgs)
                    val result = func.execute(*typedArgs.toTypedArray())
                    if (result != null && !result.isNull) {
                        notifyTextAvailable(result.toString() + "\n", ProcessOutputType.STDOUT)
                    }
                } else {
                    notifyTextAvailable("Error: Export '$targetExport' not found or not callable\n", ProcessOutputType.STDERR)
                }
            } else {
                val startFunc = exports.getMember("_start")
                if (startFunc != null && startFunc.canExecute()) {
                    startFunc.execute()
                }
            }
        }
        wasmContext = null

        stdoutPipe.close()
        stderrPipe.close()
        stdoutReader.join()
        stderrReader.join()
    }

    override fun destroyProcessImpl() {
        wasmContext?.close(true)
        notifyProcessTerminated(0)
    }

    override fun detachProcessImpl() {
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}

private fun convertArgsToWasmTypes(
    wasmBytes: ByteArray,
    exportName: String,
    args: List<String>,
): List<Any> {
    if (args.isEmpty()) return emptyList()

    val provider = WasmBinaryProvider(wasmBytes.toCommonByteBuffer())
    val export = provider.exports
        .filterIsInstance<WasmFunctionExportRecordEntry>()
        .firstOrNull { it.name == exportName }
        ?: return args

    val importedFunctionCount = provider.imports.count { it.type is WasmTypeIndexExternalType }
    val localIndex = export.index.toInt() - importedFunctionCount
    if (localIndex < 0) return args

    val signature = provider.functions.getOrNull(localIndex) as? WasmSectionFunctionTypeRecord
        ?: return args

    return args.mapIndexed { i, arg ->
        val paramType = signature.params.getOrNull(i)
        convertArg(arg, paramType)
    }
}

private fun convertArg(arg: String, type: WasmValueType<*>?): Any = when (type) {
    is WasmI32Type -> arg.toIntOrNull() ?: arg
    is WasmI64Type -> arg.toLongOrNull() ?: arg
    is WasmF32Type -> arg.toFloatOrNull() ?: arg
    is WasmF64Type -> arg.toDoubleOrNull() ?: arg
    else -> arg
}
