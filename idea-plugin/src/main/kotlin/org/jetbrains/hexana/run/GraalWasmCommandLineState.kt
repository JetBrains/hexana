package org.jetbrains.hexana.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
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
        val consoleView = ConsoleViewImpl(environment.project, false)
        val handler = GraalWasmProcessHandler(configuration)
        consoleView.attachToProcess(handler)
        return DefaultExecutionResult(consoleView, handler)
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
            val wasmBytes = wasmFile.readBytes()

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
