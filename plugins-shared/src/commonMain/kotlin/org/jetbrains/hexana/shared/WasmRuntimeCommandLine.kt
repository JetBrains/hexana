package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector

/**
 * Platform-agnostic input for building a Wasm runtime command line.
 *
 * Each field maps to a value that both IntelliJ and VS Code run
 * configurations supply (possibly with different names). The builder
 * functions in this file consume this data class and produce a
 * [WasmCommandLine] without touching platform-specific APIs.
 *
 * @property wasmFilePath absolute path to the `.wasm` file to run
 * @property wasmBytes raw bytes of [wasmFilePath], or `null` when the
 *           file has not been read yet (disables import-stub generation)
 * @property programArguments whitespace-separated program arguments
 * @property workingDirectory working directory override, blank means
 *           "use the platform default"
 * @property runtimeOptions extra CLI options for the runtime (e.g.
 *           `--cranelift-opt-level=2`), whitespace-separated
 * @property exportName function name to invoke (used by WAMR `-f` and
 *           GraalWasm `--entry-point`), blank means default entry
 * @property dependencies pre-resolved `importName → filePath` map;
 *           the builders classify them into core-module vs component deps
 * @property effectiveWasmPath when component composition has already been
 *           performed by the caller, set this to the composed binary path;
 *           when `null`, the builder uses [wasmFilePath] directly
 */
data class WasmRunInput(
    val wasmFilePath: String,
    val wasmBytes: ByteArray? = null,
    val programArguments: String = "",
    val workingDirectory: String = "",
    val runtimeOptions: String = "",
    val exportName: String = "",
    val dependencies: Map<String, String> = emptyMap(),
    val effectiveWasmPath: String? = null,
)

/**
 * Platform-agnostic abstraction for writing temporary stub files.
 *
 * Both IntelliJ (JVM `java.io.File`) and VS Code (Node.js `fs`) need
 * to materialise generated Wasm stubs on disk before adding them to the
 * command line. Implement this interface on each platform.
 */
interface StubFileWriter {
    /**
     * Creates a temporary directory for stub files and returns its
     * absolute path.
     */
    fun createTempDir(prefix: String): String

    /**
     * Writes [bytes] into a file at `<dir>/<fileName>` and returns the
     * absolute path of the created file.
     */
    fun writeFile(dir: String, fileName: String, bytes: ByteArray): String
}

/**
 * Result of building a Wasm runtime command line.
 *
 * The [arguments] list contains everything *after* the runtime
 * executable itself (e.g. `["run", "--preload", "env=…", "module.wasm"]`).
 * The caller is responsible for prepending the executable path and for
 * translating this into platform-specific process-launch APIs.
 *
 * @property arguments ordered argument list for the runtime CLI
 * @property workingDirectory effective working directory, or `null` to
 *           inherit the parent process's working directory
 */
data class WasmCommandLine(
    val arguments: List<String>,
    val workingDirectory: String? = null,
)

/**
 * Extended result for Wasmtime debug runs that carries composition
 * and DWARF injection metadata alongside the base command line.
 */
data class WasmtimeDebugCommandLine(
    val commandLine: WasmCommandLine,
    val compositionUsed: Boolean = false,
    val dwarfInjected: Boolean = false,
)

/**
 * Extended result for WAMR debug runs that carries the debug
 * connection address and optional stub directory path.
 */
data class WamrDebugCommandLine(
    val commandLine: WasmCommandLine,
    val debugHost: String,
    val debugPort: Int,
    val stubDir: String? = null,
)

// ---------------------------------------------------------------------------
// Dependency classification
// ---------------------------------------------------------------------------

/**
 * Classifies pre-resolved dependencies into core-module and component
 * buckets by reading the first bytes of each dependency file.
 *
 * @param readFile platform-specific callback that returns the raw bytes
 *                 of a file, or `null` when the file does not exist
 */
fun classifyDependencies(
    dependencies: Map<String, String>,
    readFile: (path: String) -> ByteArray?,
): Pair<Map<String, String>, Map<String, String>> {
    val coreModuleDeps = mutableMapOf<String, String>()
    val componentDeps = mutableMapOf<String, String>()
    for ((name, path) in dependencies) {
        val bytes = readFile(path) ?: continue
        when (WasmBinaryProviderSelector.select(bytes.toCommonByteBuffer())) {
            is BinaryProviderType.Wasm -> coreModuleDeps[name] = path
            is BinaryProviderType.WasmComponent -> componentDeps[name] = path
            else -> {}
        }
    }
    return coreModuleDeps to componentDeps
}

// ---------------------------------------------------------------------------
// Argument list splitter (shell-like)
// ---------------------------------------------------------------------------

/**
 * Splits a whitespace-separated option string into individual tokens,
 * respecting double-quoted and single-quoted segments, as well as
 * backslash-escaped characters within double-quoted segments.
 */
fun splitOptions(options: String): List<String> {
    val trimmed = options.trim()
    if (trimmed.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    var inDoubleQuotes = false
    var inSingleQuotes = false
    while (i < trimmed.length) {
        val ch = trimmed[i]
        when {
            ch == '\\' && inDoubleQuotes && i + 1 < trimmed.length -> {
                // Inside double quotes, backslash escapes the next character
                current.append(trimmed[i + 1])
                i += 2
                continue
            }
            ch == '"' && !inSingleQuotes -> inDoubleQuotes = !inDoubleQuotes
            ch == '\'' && !inDoubleQuotes -> inSingleQuotes = !inSingleQuotes
            ch.isWhitespace() && !inDoubleQuotes && !inSingleQuotes -> {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                }
            }
            else -> current.append(ch)
        }
        i++
    }
    if (current.isNotEmpty()) {
        result.add(current.toString())
    }
    return result
}

// ---------------------------------------------------------------------------
// Wasmtime
// ---------------------------------------------------------------------------

/**
 * Builds the argument list for a Wasmtime `run` invocation.
 *
 * The caller must supply a [StubFileWriter] when import stubs should be
 * materialised on disk. When [stubFileWriter] is `null`, stub-related
 * `--preload` flags are omitted.
 */
fun buildWasmtimeRunArgs(
    input: WasmRunInput,
    coreModuleDeps: Map<String, String> = emptyMap(),
    stubFileWriter: StubFileWriter? = null,
): WasmCommandLine {
    val args = mutableListOf<String>()
    args.add("run")

    val options = input.runtimeOptions.trim()
    if (options.isNotEmpty()) {
        args.addAll(splitOptions(options))
    }

    for ((name, path) in coreModuleDeps) {
        args.add("--preload")
        args.add("$name=$path")
    }

    val wasmBytes = input.wasmBytes
    if (wasmBytes != null && WasmImportStubGenerator.hasImports(wasmBytes)) {
        args.add("-W")
        args.add("unknown-imports-default")

        if (stubFileWriter != null && WasmImportStubGenerator.hasNonFunctionImports(wasmBytes)) {
            val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasmBytes)
                .filter { it.moduleName !in coreModuleDeps.keys }
            if (stubs.isNotEmpty()) {
                val tempDir = stubFileWriter.createTempDir("hexana-wasm-stubs")
                for (stub in stubs) {
                    val stubPath = stubFileWriter.writeFile(tempDir, "${stub.moduleName}.wasm", stub.wasmBytes)
                    args.add("--preload")
                    args.add("${stub.moduleName}=$stubPath")
                }
            }
        }
    }

    args.add(input.effectiveWasmPath ?: input.wasmFilePath)

    val programArgs = input.programArguments.trim()
    if (programArgs.isNotEmpty()) {
        args.addAll(programArgs.split("\\s+".toRegex()))
    }

    val workDir = input.workingDirectory.ifBlank { null }
    return WasmCommandLine(arguments = args, workingDirectory = workDir)
}

/**
 * Builds the argument list for a Wasmtime debug run
 * (`run -D debug-info -O opt-level=0`).
 *
 * This mirrors [buildWasmtimeRunArgs] but unconditionally injects the
 * debug-info and optimisation-level options.
 */
fun buildWasmtimeDebugArgs(
    input: WasmRunInput,
    coreModuleDeps: Map<String, String> = emptyMap(),
    stubFileWriter: StubFileWriter? = null,
): WasmCommandLine {
    val args = mutableListOf<String>()
    args.add("run")

    // Enable DWARF debug-info translation for native debuggers
    args.add("-D")
    args.add("debug-info")

    // Disable optimizations for better variable inspection
    args.add("-O")
    args.add("opt-level=0")

    val options = input.runtimeOptions.trim()
    if (options.isNotEmpty()) {
        args.addAll(splitOptions(options))
    }

    for ((name, path) in coreModuleDeps) {
        args.add("--preload")
        args.add("$name=$path")
    }

    val wasmBytes = input.wasmBytes
    if (wasmBytes != null && WasmImportStubGenerator.hasImports(wasmBytes)) {
        args.add("-W")
        args.add("unknown-imports-default")

        if (stubFileWriter != null && WasmImportStubGenerator.hasNonFunctionImports(wasmBytes)) {
            val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasmBytes)
                .filter { it.moduleName !in coreModuleDeps.keys }
            if (stubs.isNotEmpty()) {
                val tempDir = stubFileWriter.createTempDir("hexana-wasm-stubs")
                for (stub in stubs) {
                    val stubPath = stubFileWriter.writeFile(tempDir, "${stub.moduleName}.wasm", stub.wasmBytes)
                    args.add("--preload")
                    args.add("${stub.moduleName}=$stubPath")
                }
            }
        }
    }

    args.add(input.effectiveWasmPath ?: input.wasmFilePath)

    val programArgs = input.programArguments.trim()
    if (programArgs.isNotEmpty()) {
        args.addAll(programArgs.split("\\s+".toRegex()))
    }

    val workDir = input.workingDirectory.ifBlank { null }
    return WasmCommandLine(arguments = args, workingDirectory = workDir)
}

// ---------------------------------------------------------------------------
// WAMR (iwasm)
// ---------------------------------------------------------------------------

/**
 * Builds the argument list for a WAMR `iwasm` invocation.
 *
 * Unlike Wasmtime, WAMR does not support the Component Model or
 * `--preload` for multi-module linking. Import stubs are written as
 * `<module_name>.wasm` files in a temporary directory that becomes the
 * working directory so WAMR can discover them.
 */
fun buildWamrRunArgs(
    input: WasmRunInput,
    stubFileWriter: StubFileWriter? = null,
): WasmCommandLine {
    val args = mutableListOf<String>()

    val wasmBytes = input.wasmBytes
    val stubDir = if (stubFileWriter != null) {
        generateWamrStubFiles(wasmBytes, stubFileWriter)
    } else null

    val options = input.runtimeOptions.trim()
    if (options.isNotEmpty()) {
        args.addAll(splitOptions(options))
    }

    // iwasm uses `-f <function_name>` to invoke a specific exported function
    val functionName = input.exportName.trim()
    if (functionName.isNotEmpty()) {
        args.add("-f")
        args.add(functionName)
    }

    args.add(input.wasmFilePath)

    val programArgs = input.programArguments.trim()
    if (programArgs.isNotEmpty()) {
        args.addAll(programArgs.split("\\s+".toRegex()))
    }

    // When stub modules were generated, use the stub directory as the
    // working directory so WAMR can find the <module_name>.wasm files.
    val workDir = stubDir
        ?: input.workingDirectory.ifBlank { null }

    return WasmCommandLine(arguments = args, workingDirectory = workDir)
}

/**
 * Builds the argument list for a WAMR debug run
 * (`iwasm -g=<host>:<port>`).
 */
fun buildWamrDebugArgs(
    input: WasmRunInput,
    host: String = "127.0.0.1",
    port: Int = 1234,
    stubFileWriter: StubFileWriter? = null,
): WamrDebugCommandLine {
    val args = mutableListOf<String>()

    val wasmBytes = input.wasmBytes
    val stubDir = if (stubFileWriter != null) {
        generateWamrStubFiles(wasmBytes, stubFileWriter)
    } else null

    // Enable debug server
    args.add("-g=$host:$port")

    val options = input.runtimeOptions.trim()
    if (options.isNotEmpty()) {
        args.addAll(splitOptions(options))
    }

    val functionName = input.exportName.trim()
    if (functionName.isNotEmpty()) {
        args.add("-f")
        args.add(functionName)
    }

    args.add(input.wasmFilePath)

    val programArgs = input.programArguments.trim()
    if (programArgs.isNotEmpty()) {
        args.addAll(programArgs.split("\\s+".toRegex()))
    }

    val workDir = stubDir
        ?: input.workingDirectory.ifBlank { null }

    return WamrDebugCommandLine(
        commandLine = WasmCommandLine(arguments = args, workingDirectory = workDir),
        debugHost = host,
        debugPort = port,
        stubDir = stubDir,
    )
}

/**
 * Generates WAMR stub files in a temporary directory and returns the
 * directory path, or `null` when no stubs are needed.
 *
 * This is also exposed publicly as [generateWamrStubFiles] for callers
 * that need the stub directory without building the full argument list.
 */
fun generateWamrStubFiles(
    wasmBytes: ByteArray?,
    stubFileWriter: StubFileWriter,
): String? {
    if (wasmBytes == null || !WasmImportStubGenerator.hasImports(wasmBytes)) return null
    val stubs = WasmImportStubGenerator.generateStubs(wasmBytes)
    if (stubs.isEmpty()) return null
    val tempDir = stubFileWriter.createTempDir("hexana-wamr-stubs")
    for (stub in stubs) {
        stubFileWriter.writeFile(tempDir, "${stub.moduleName}.wasm", stub.wasmBytes)
    }
    return tempDir
}

// ---------------------------------------------------------------------------
// GraalWasm (external launcher)
// ---------------------------------------------------------------------------

/**
 * Builds the argument list for an external GraalVM `wasm` launcher.
 */
fun buildGraalWasmRunArgs(input: WasmRunInput): WasmCommandLine {
    val args = mutableListOf<String>()

    val targetExport = input.exportName.ifBlank { null }
    if (targetExport != null) {
        args.add("--entry-point")
        args.add(targetExport)
    }

    args.add(input.wasmFilePath)

    val programArgs = input.programArguments.trim()
    if (programArgs.isNotEmpty()) {
        args.addAll(programArgs.split("\\s+".toRegex()))
    }

    val workDir = input.workingDirectory.ifBlank { null }
    return WasmCommandLine(arguments = args, workingDirectory = workDir)
}
