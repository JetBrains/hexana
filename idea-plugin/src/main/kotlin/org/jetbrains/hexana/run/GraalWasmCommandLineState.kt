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
import org.jetbrains.hexana.shared.WasmRunInput
import org.jetbrains.hexana.shared.buildGraalWasmRunArgs
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.shared.detectProposals
import org.jetbrains.hexana.shared.graalWasmContextOptions
import org.jetbrains.hexana.wasm.sections.*
import java.io.File
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

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
 * Launches a Wasm module via `java` from a custom GraalVM (or any JDK)
 * installation as an external process.
 *
 * The deprecated GraalVM `wasm` launcher is no longer shipped with
 * modern GraalVM JDK distributions. This state uses a thin Java
 * wrapper (`HexanaGraalWasmRunner.java`) that is bundled as a plugin
 * resource, compiled on first use with `javac` from the configured
 * JDK, and executed with the GraalVM Polyglot JARs on the classpath.
 *
 * The compiled runner class is cached under the system temp directory
 * and reused across runs.
 */
private class ExternalGraalWasmCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
    private val graalVmHome: String,
) : CommandLineState(environment) {

    companion object {
        private const val RUNNER_CLASS = "org.jetbrains.hexana.runner.HexanaGraalWasmRunner"
    }

    override fun startProcess(): ProcessHandler {
        val javaExe = resolveJavaExecutable(graalVmHome)

        val polyglotJars = resolvePolyglotJars(graalVmHome)
        val classesDir = compileRunnerIfNeeded(graalVmHome, polyglotJars)

        // Use a wildcard for the JAR directory to keep the command line short.
        // All Polyglot JARs reside in the same cache directory.
        val jarDir = File(polyglotJars.first()).parent + File.separator + "*"
        val classpath = listOf(classesDir, jarDir).joinToString(File.pathSeparator)

        val wasmFile = File(configuration.wasmFilePath)
        val wasmBytes = if (wasmFile.exists()) wasmFile.readBytes() else null

        val input = WasmRunInput(
            wasmFilePath = configuration.wasmFilePath,
            wasmBytes = wasmBytes,
            programArguments = configuration.programArguments,
            workingDirectory = configuration.workingDirectory,
            exportName = configuration.exportName,
        )

        val cmdLine = buildGraalWasmRunArgs(input)

        // Generate import stub modules and write them to temp files
        val stubArgs = mutableListOf<String>()
        if (wasmBytes != null) {
            val stubs = org.jetbrains.hexana.shared.WasmImportStubGenerator.generateStubs(wasmBytes)
            val stubDir = Path.of(System.getProperty("java.io.tmpdir"), "hexana-graalwasm-runner", "stubs")
            Files.createDirectories(stubDir)
            for (stub in stubs) {
                val stubFile = stubDir.resolve("${stub.moduleName.replace('/', '_')}.wasm")
                Files.write(stubFile, stub.wasmBytes)
                stubArgs.add("--stub")
                stubArgs.add("${stub.moduleName}=${stubFile}")
            }
        }

        val commandLine = GeneralCommandLine().apply {
            exePath = javaExe
            addParameters("-cp", classpath)
            addParameter(RUNNER_CLASS)
            addParameters(stubArgs)
            addParameters(cmdLine.arguments)

            if (cmdLine.workingDirectory != null) {
                withWorkDirectory(cmdLine.workingDirectory)
            }
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        }

        val handler = OSProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}

// ---------------------------------------------------------------------------
// External GraalVM runner helpers
// ---------------------------------------------------------------------------

/**
 * Resolves the `java` (or `java.exe`) executable inside a JDK home.
 */
private fun resolveJavaExecutable(jdkHome: String): String {
    val exeName = if (SystemInfo.isWindows) "java.exe" else "java"
    val javaFile = File(jdkHome, "bin" + File.separator + exeName)
    require(javaFile.isFile) {
        "Cannot find '$exeName' in ${javaFile.parent}. " +
                "Make sure the GraalVM path points to a valid JDK home directory."
    }
    return javaFile.absolutePath
}

/**
 * Detects the GraalVM version from `java -version` output and downloads
 * the required Polyglot JARs from Maven Central if not already cached.
 *
 * @return list of absolute paths to the downloaded JAR files
 */
private fun resolvePolyglotJars(jdkHome: String): List<String> {
    val version = detectGraalVmVersion(jdkHome)
    val cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "hexana-graalwasm-runner", "libs", version)
    Files.createDirectories(cacheDir)

    val artifacts = listOf(
        "org.graalvm.polyglot:polyglot:$version",
        "org.graalvm.wasm:wasm-language:$version",
        "org.graalvm.sdk:collections:$version",
        "org.graalvm.sdk:nativeimage:$version",
        "org.graalvm.sdk:word:$version",
        "org.graalvm.truffle:truffle-api:$version",
        "org.graalvm.truffle:truffle-compiler:$version",
        "org.graalvm.truffle:truffle-runtime:$version",
    )

    return artifacts.map { coords ->
        val parts = coords.split(":")
        val groupPath = parts[0].replace('.', '/')
        val artifactId = parts[1]
        val ver = parts[2]
        val fileName = "$artifactId-$ver.jar"
        val jarPath = cacheDir.resolve(fileName)

        if (!Files.exists(jarPath)) {
            val url = "https://repo1.maven.org/maven2/$groupPath/$artifactId/$ver/$fileName"
            try {
                URI(url).toURL().openStream().use { input ->
                    Files.copy(input, jarPath)
                }
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to download $url: ${e.message}\n" +
                            "Make sure you have internet access or pre-cache the GraalVM Polyglot JARs.", e
                )
            }
        }

        jarPath.toString()
    }
}

/**
 * Runs `java -version` from the given JDK home and extracts the version string.
 * GraalVM JDKs report a version like `24.1.1` or `23.0.1` in the first line.
 */
private fun detectGraalVmVersion(jdkHome: String): String {
    val javaExe = resolveJavaExecutable(jdkHome)
    val process = ProcessBuilder(javaExe, "-version")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()

    // GraalVM CE/EE prints lines like:
    //   openjdk version "21.0.2" 2024-01-16
    //   ...
    //   GraalVM CE 24.1.1 ...
    // Or for newer distributions:
    //   java version "24.0.1" ...
    // We look for a GraalVM-specific line first, then fall back to the JDK version.
    val graalLine = output.lineSequence().firstOrNull { it.contains("GraalVM") }
    if (graalLine != null) {
        val match = Regex("""(\d+\.\d+\.\d+)""").find(graalLine)
        if (match != null) return match.groupValues[1]
    }

    // Fall back: extract the JDK version from the first line
    val versionMatch = Regex(""""(\d+[\d.]*)"""").find(output)
    if (versionMatch != null) return versionMatch.groupValues[1]

    error("Cannot detect GraalVM/JDK version from:\n$output")
}

/**
 * Compiles the bundled `HexanaGraalWasmRunner.java` runner source
 * to a cached temp directory using `javac` from the given JDK.
 *
 * The compilation result is cached: if the output `.class` file
 * already exists it is reused.
 *
 * @param polyglotJars paths to the Polyglot JARs needed on the compile classpath
 * @return the classes output directory (to be added to `-cp`)
 */
private fun compileRunnerIfNeeded(jdkHome: String, polyglotJars: List<String>): String {
    val cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "hexana-graalwasm-runner")
    val classFile = cacheDir.resolve("org/jetbrains/hexana/runner/HexanaGraalWasmRunner.class")

    // Extract the runner source from plugin resources
    val sourceText = ExternalGraalWasmCommandLineState::class.java
        .getResourceAsStream("/runner/HexanaGraalWasmRunner.java")
        ?.bufferedReader()?.readText()
        ?: error("Runner source not found in plugin resources")

    // Use a hash of the source to invalidate the cache when the runner changes
    val sourceHash = sourceText.hashCode().toString()
    val hashFile = cacheDir.resolve("source.hash")
    if (Files.exists(classFile) && Files.exists(hashFile) && Files.readString(hashFile) == sourceHash) {
        return cacheDir.toString()
    }

    Files.createDirectories(cacheDir)

    val sourceDir = cacheDir.resolve("src/org/jetbrains/hexana/runner")
    Files.createDirectories(sourceDir)
    val sourceFile = sourceDir.resolve("HexanaGraalWasmRunner.java")
    Files.writeString(sourceFile, sourceText)

    val javacExe = if (SystemInfo.isWindows) "javac.exe" else "javac"
    val javac = File(jdkHome, "bin" + File.separator + javacExe).absolutePath

    val javacArgs = mutableListOf(javac)
    if (polyglotJars.isNotEmpty()) {
        javacArgs += listOf("-cp", polyglotJars.joinToString(File.pathSeparator))
    }
    javacArgs += listOf("-d", cacheDir.toString(), sourceFile.toString())

    val process = ProcessBuilder(javacArgs)
        .redirectErrorStream(true).start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("Failed to compile HexanaGraalWasmRunner (exit code $exitCode):\n$output")
    }

    Files.writeString(hashFile, sourceHash)

    return cacheDir.toString()
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

        val proposals = detectProposals(wasmBytes)
        val proposalOptions = graalWasmContextOptions(proposals)

        val contextBuilder = Context.newBuilder("wasm")
            .option("engine.WarnInterpreterOnly", "false")
            .out(stdoutPipe)
            .err(stderrPipe)
        if (proposalOptions.isNotEmpty()) {
            contextBuilder.allowExperimentalOptions(true)
            for ((key, value) in proposalOptions) {
                contextBuilder.option(key, value)
            }
        }
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
