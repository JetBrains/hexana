package org.jetbrains.hexana.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Launches a Wasm module via the Chicory runtime.
 *
 * Chicory is a pure-Java WebAssembly runtime that does not ship a
 * standalone CLI executable. This state uses a thin Java wrapper
 * (`HexanaChicoryRunner.java`) that is bundled as a plugin resource,
 * compiled on first use with `javac` from the configured JDK, and
 * executed with the Chicory JARs on the classpath.
 *
 * Two execution modes are supported:
 * - **Interpreter** (`CHICORY_INTERPRETER`): pure bytecode interpretation.
 * - **AOT** (`CHICORY_AOT`): ahead-of-time compilation to JVM bytecode
 *   via the "Redline" backend.
 *
 * The JDK used to run the wrapper is resolved as follows:
 * 1. Per-configuration `chicoryPath` (a JDK home directory).
 * 2. Application-level `chicoryPath` from [WasmRuntimeSettings].
 * 3. The IntelliJ-bundled JVM (`java.home`).
 *
 * This mirrors the approach used by [GraalWasmCommandLineState] for
 * external GraalVM execution.
 */
class ChicoryCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
    private val aot: Boolean,
) : CommandLineState(environment) {

    companion object {
        private const val RUNNER_CLASS = "org.jetbrains.hexana.runner.HexanaChicoryRunner"

        /** Chicory version used for downloading JARs from Maven Central. */
        internal const val CHICORY_VERSION = "1.7.5"
    }

    override fun startProcess(): ProcessHandler {
        val jdkHome = resolveJdkHome()
        val javaExe = resolveJavaExecutable(jdkHome)

        val chicoryJars = resolveChicoryJars()
        val classesDir = compileRunnerIfNeeded(jdkHome, chicoryJars)

        val jarDir = File(chicoryJars.first()).parent + File.separator + "*"
        val classpath = listOf(classesDir, jarDir).joinToString(File.pathSeparator)

        val wasmFile = File(configuration.wasmFilePath)

        val runnerArgs = mutableListOf<String>()
        if (aot) {
            runnerArgs.add("--aot")
        }
        val exportName = configuration.exportName.trim()
        if (exportName.isNotEmpty()) {
            runnerArgs.add("--entry-point")
            runnerArgs.add(exportName)
        }
        runnerArgs.add(configuration.wasmFilePath)
        val programArgs = configuration.programArguments.trim()
        if (programArgs.isNotEmpty()) {
            runnerArgs.addAll(programArgs.split("\\s+".toRegex()))
        }

        val workDir = configuration.workingDirectory.ifBlank { wasmFile.parent }

        val commandLine = GeneralCommandLine().apply {
            exePath = javaExe
            addParameters("-cp", classpath)
            addParameter(RUNNER_CLASS)
            addParameters(runnerArgs)
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
     * Resolves the JDK home directory to use for running the Chicory wrapper.
     *
     * Resolution order:
     * 1. Per-configuration path.
     * 2. Application-level setting.
     * 3. IntelliJ-bundled JVM.
     */
    private fun resolveJdkHome(): String {
        val perConfig = configuration.chicoryPath.ifBlank { null }
        if (perConfig != null) return perConfig
        val appLevel = WasmRuntimeSettings.getInstance().effectiveChicoryJdkPath()
        if (appLevel != null) return appLevel
        return System.getProperty("java.home")
    }
}

// ---------------------------------------------------------------------------
// External Chicory runner helpers
// ---------------------------------------------------------------------------

/**
 * Resolves the `java` (or `java.exe`) executable inside a JDK home.
 */
private fun resolveJavaExecutable(jdkHome: String): String {
    val exeName = if (SystemInfo.isWindows) "java.exe" else "java"
    val javaFile = File(jdkHome, "bin" + File.separator + exeName)
    if (javaFile.isFile) return javaFile.absolutePath
    // java.home may point to the JDK root or to the jre/ subdirectory;
    // also handle the case where bin/java is one level up.
    val parentBin = File(jdkHome).parentFile?.let { File(it, "bin" + File.separator + exeName) }
    if (parentBin != null && parentBin.isFile) return parentBin.absolutePath
    error(
        "Cannot find '$exeName' in ${javaFile.parent}. " +
                "Make sure the Chicory JDK path points to a valid JDK home directory."
    )
}

/**
 * Downloads the required Chicory JARs from Maven Central if not already cached.
 *
 * @return list of absolute paths to the downloaded JAR files
 */
private fun resolveChicoryJars(): List<String> {
    val version = ChicoryCommandLineState.CHICORY_VERSION
    val cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "hexana-chicory-runner", "libs", version)
    Files.createDirectories(cacheDir)

    val asmVersion = "9.9.1"
    val artifacts = listOf(
        "com.dylibso.chicory:runtime:$version",
        "com.dylibso.chicory:wasm:$version",
        "com.dylibso.chicory:wasi:$version",
        "com.dylibso.chicory:compiler:$version",
        "com.dylibso.chicory:annotations:$version",
        "com.dylibso.chicory:log:$version",
        "org.ow2.asm:asm:$asmVersion",
        "org.ow2.asm:asm-commons:$asmVersion",
        "org.ow2.asm:asm-util:$asmVersion",
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
                            "Make sure you have internet access or pre-cache the Chicory JARs.", e
                )
            }
        }

        jarPath.toString()
    }
}

/**
 * Compiles the bundled `HexanaChicoryRunner.java` runner source
 * to a cached temp directory using `javac` from the given JDK.
 *
 * The compilation result is cached: if the output `.class` file
 * already exists and the source hash matches, it is reused.
 *
 * @param jdkHome JDK home directory whose `javac` is used for compilation
 * @param chicoryJars paths to the Chicory JARs needed on the compile classpath
 * @return the classes output directory (to be added to `-cp`)
 */
private fun compileRunnerIfNeeded(jdkHome: String, chicoryJars: List<String>): String {
    val cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "hexana-chicory-runner")
    val classFile = cacheDir.resolve("org/jetbrains/hexana/runner/HexanaChicoryRunner.class")

    val sourceText = ChicoryCommandLineState::class.java
        .getResourceAsStream("/runner/HexanaChicoryRunner.java")
        ?.bufferedReader()?.readText()
        ?: error("Runner source not found in plugin resources")

    val sourceHash = sourceText.hashCode().toString()
    val hashFile = cacheDir.resolve("chicory-source.hash")
    if (Files.exists(classFile) && Files.exists(hashFile) && Files.readString(hashFile) == sourceHash) {
        return cacheDir.toString()
    }

    Files.createDirectories(cacheDir)

    val sourceDir = cacheDir.resolve("src/org/jetbrains/hexana/runner")
    Files.createDirectories(sourceDir)
    val sourceFile = sourceDir.resolve("HexanaChicoryRunner.java")
    Files.writeString(sourceFile, sourceText)

    val javacExe = if (SystemInfo.isWindows) "javac.exe" else "javac"
    val javac = File(jdkHome, "bin" + File.separator + javacExe).absolutePath

    val javacArgs = mutableListOf(javac)
    if (chicoryJars.isNotEmpty()) {
        javacArgs += listOf("-cp", chicoryJars.joinToString(File.pathSeparator))
    }
    javacArgs += listOf("-d", cacheDir.toString(), sourceFile.toString())

    val process = ProcessBuilder(javacArgs)
        .redirectErrorStream(true).start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("Failed to compile HexanaChicoryRunner (exit code $exitCode):\n$output")
    }

    Files.writeString(hashFile, sourceHash)

    return cacheDir.toString()
}
