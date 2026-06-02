package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.analysis.WasmProposal
import org.jetbrains.hexana.wasm.analysis.WasmProposalDetector

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
// Proposal detection
// ---------------------------------------------------------------------------

/**
 * Detects which WebAssembly proposals are used by the given binary.
 *
 * Returns an empty set when [wasmBytes] is `null` or cannot be parsed.
 */
fun detectProposals(wasmBytes: ByteArray?): Set<WasmProposal> {
    if (wasmBytes == null) return emptySet()
    return try {
        val buffer = wasmBytes.toCommonByteBuffer()
        val result = WasmParser().parse(buffer)
        val module = (result as? WasmParseSuccess)?.wasmModule as? WasmModule ?: return emptySet()
        buffer.position(0)
        WasmProposalDetector.detect(module, buffer)
    } catch (_: Exception) {
        emptySet()
    }
}

// ---------------------------------------------------------------------------
// Proposal flags for Wasmtime
// ---------------------------------------------------------------------------

/**
 * Returns Wasmtime CLI flags (`-W` / `-S`) needed to enable support
 * for the given set of detected proposals.
 *
 * Only proposals that require explicit opt-in are included — proposals
 * enabled by default in modern Wasmtime (e.g. SIMD, bulk memory,
 * reference types, multi-value, sign-extension, non-trapping float-to-int,
 * mutable globals, tail-call, GC, function-references, exception-handling,
 * multi-memory, memory64, relaxed-simd) are omitted because Wasmtime
 * already enables them.
 *
 * The Threads proposal is the notable exception: `wasm_threads` is
 * gated behind a crate feature that may not be compiled in, and the
 * WASI `threads` support must be explicitly opted in via `-S threads`.
 * Additionally, `shared-memory` must be enabled (`-W shared-memory`)
 * because Wasmtime's shared-memory gate is off by default and the
 * engine will refuse to instantiate modules with shared linear memories
 * without it.
 */
internal fun wasmtimeProposalFlags(proposals: Set<WasmProposal>): List<String> {
    val flags = mutableListOf<String>()
    if (WasmProposal.Threads in proposals) {
        flags.add("-W")
        flags.add("threads")
        flags.add("-W")
        flags.add("shared-memory")
        flags.add("-S")
        flags.add("threads")
    }
    return flags
}

// ---------------------------------------------------------------------------
// Proposal flags for WAMR (iwasm)
// ---------------------------------------------------------------------------

/**
 * Returns iwasm CLI flags needed to enable support for the given
 * set of detected proposals.
 *
 * WAMR has very limited CLI surface for proposals. Thread support
 * is a compile-time option (`WAMR_BUILD_LIB_WASI_THREADS=1`) and
 * cannot be toggled at runtime. The `--max-threads=N` flag only
 * exists in builds that already have threads compiled in, and the
 * default (4) is sufficient, so we do not emit any extra flags.
 */
internal fun wamrProposalFlags(proposals: Set<WasmProposal>): List<String> {
    return emptyList()
}

// ---------------------------------------------------------------------------
// Proposal flags for GraalWasm (external launcher)
// ---------------------------------------------------------------------------

/**
 * Returns GraalWasm external launcher flags needed to enable support
 * for the given set of detected proposals.
 *
 * GraalWasm uses `--wasm.<Option>=true` flags.  All proposal-related
 * options in GraalWasm 25.0.0 are experimental and require
 * `--experimental-options` when any flag is emitted.
 *
 * Options that default to `true` (no flags needed):
 *   `BulkMemoryAndRefTypes`, `SaturatingFloatToInt`, `SignExtensionOps`,
 *   `MultiValue`, `SIMD`.
 *
 * Options that default to `false` (flags emitted here):
 * - **Threads**: `--wasm.Threads=true` and `--wasm.UseUnsafeMemory=true`
 *   (GraalWasm requires unsafe memory for shared linear memories).
 * - **Memory64**: `--wasm.Memory64=true`.
 * - **MultiMemory**: `--wasm.MultiMemory=true`.
 * - **RelaxedSIMD**: `--wasm.RelaxedSIMD=true`.
 *
 * Options not present in GraalWasm 25.0.0 (no flags to emit):
 *   Exceptions, TypedFunctionReferences, GC, TailCall.
 *
 * Source of truth: `WasmOptions.java` in the GraalWasm 25.0.0 codebase.
 * See also https://www.graalvm.org/webassembly/docs/#experimental-options
 */
internal fun graalWasmProposalFlags(proposals: Set<WasmProposal>): List<String> {
    val flags = mutableListOf<String>()
    if (WasmProposal.Threads in proposals) {
        flags.add("--wasm.Threads=true")
        flags.add("--wasm.UseUnsafeMemory=true")
    }
    if (WasmProposal.Memory64 in proposals) {
        flags.add("--wasm.Memory64=true")
    }
    if (WasmProposal.MultipleMemories in proposals) {
        flags.add("--wasm.MultiMemory=true")
    }
    if (WasmProposal.RelaxedSIMD in proposals) {
        flags.add("--wasm.RelaxedSIMD=true")
    }
    // ExtendedConstExpressions: not emitted because the proposal cannot be
    // reliably detected from binary inspection alone (const-expr validation
    // is not performed by the parser).
    if (flags.isNotEmpty()) {
        flags.add(0, "--experimental-options")
    }
    return flags
}

// ---------------------------------------------------------------------------
// Proposal context options for embedded GraalWasm
// ---------------------------------------------------------------------------

/**
 * Returns GraalWasm context option key-value pairs needed to enable
 * support for the given set of detected proposals when running
 * GraalWasm in-process via the Polyglot API.
 *
 * The caller should apply these via `Context.Builder.option(key, value)`
 * and, when the list is non-empty, also call
 * `Context.Builder.allowExperimentalOptions(true)`.
 *
 * Only proposals that default to `false` in GraalWasm 25.0.0 are
 * included.  See [graalWasmProposalFlags] for the full mapping.
 */
fun graalWasmContextOptions(proposals: Set<WasmProposal>): List<Pair<String, String>> {
    val options = mutableListOf<Pair<String, String>>()
    if (WasmProposal.Threads in proposals) {
        options.add("wasm.Threads" to "true")
        options.add("wasm.UseUnsafeMemory" to "true")
    }
    if (WasmProposal.Memory64 in proposals) {
        options.add("wasm.Memory64" to "true")
    }
    if (WasmProposal.MultipleMemories in proposals) {
        options.add("wasm.MultiMemory" to "true")
    }
    if (WasmProposal.RelaxedSIMD in proposals) {
        options.add("wasm.RelaxedSIMD" to "true")
    }
    return options
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
 * Unresolved imports are handled exclusively via
 * `-W unknown-imports-default`, which lets wasmtime supply default
 * values for every import type (functions, memories, tables, globals).
 * `--preload` stubs are **not** used because wasmtime registers both
 * the preloaded export and its own linker-level definition for the
 * same import name, causing a "defined twice" linker error.
 */
fun buildWasmtimeRunArgs(
    input: WasmRunInput,
    coreModuleDeps: Map<String, String> = emptyMap(),
): WasmCommandLine {
    val args = mutableListOf<String>()
    args.add("run")

    // Inject proposal-specific flags before user options so that user
    // options can override them if needed.
    val proposals = detectProposals(input.wasmBytes)
    args.addAll(wasmtimeProposalFlags(proposals))

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
        // Wasmtime's `-W unknown-imports-default` supplies default values
        // for ALL unresolved import types (functions, memories, tables,
        // globals). Using `--preload` stubs is incompatible: wasmtime
        // registers both the preloaded export and its own default for the
        // same import name, causing a "defined twice" linker error.
        args.add("-W")
        args.add("unknown-imports-default")
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
): WasmCommandLine {
    val args = mutableListOf<String>()
    args.add("run")

    // Enable DWARF debug-info translation for native debuggers
    args.add("-D")
    args.add("debug-info")

    // Disable optimizations for better variable inspection
    args.add("-O")
    args.add("opt-level=0")

    // Inject proposal-specific flags before user options so that user
    // options can override them if needed.
    val proposals = detectProposals(input.wasmBytes)
    args.addAll(wasmtimeProposalFlags(proposals))

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
        // See buildWasmtimeRunArgs — same rationale applies.
        args.add("-W")
        args.add("unknown-imports-default")
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

    // Inject proposal-specific flags before user options so that user
    // options can override them if needed.
    val proposals = detectProposals(wasmBytes)
    args.addAll(wamrProposalFlags(proposals))

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

    // Inject proposal-specific flags before user options so that user
    // options can override them if needed.
    val proposals = detectProposals(wasmBytes)
    args.addAll(wamrProposalFlags(proposals))

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
 * Builds the argument list for running a Wasm module via GraalVM.
 *
 * The returned arguments follow the same CLI convention as the
 * deprecated GraalVM `wasm` launcher and are consumed by the thin
 * `HexanaGraalWasmRunner` wrapper bundled with the plugin.
 */
fun buildGraalWasmRunArgs(input: WasmRunInput): WasmCommandLine {
    val args = mutableListOf<String>()

    // Inject proposal-specific flags before other options.
    val proposals = detectProposals(input.wasmBytes)
    args.addAll(graalWasmProposalFlags(proposals))

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

// ---------------------------------------------------------------------------
// Chicory
// ---------------------------------------------------------------------------

/**
 * Builds the argument list for running a Wasm module via the Chicory CLI.
 *
 * Chicory is a JVM-based WebAssembly runtime with two execution modes:
 * - **Interpreter** (`--chicory-mode=interpret`): pure-Java bytecode
 *   interpretation, no native dependencies.
 * - **AOT** (`--chicory-mode=aot`): ahead-of-time compilation to JVM
 *   bytecode via the Chicory "Redline" backend for near-native speed.
 *
 * The Chicory CLI (`chicory run …`) accepts the Wasm file path and
 * optional flags for the function to invoke and extra runtime options.
 *
 * @param input shared run input
 * @param aot   `true` to use AOT/Redline mode, `false` for interpreter
 */
fun buildChicoryRunArgs(input: WasmRunInput, aot: Boolean): WasmCommandLine {
    val args = mutableListOf<String>()
    args.add("run")

    // Select execution mode
    if (aot) {
        args.add("--chicory-mode=aot")
    } else {
        args.add("--chicory-mode=interpret")
    }

    val options = input.runtimeOptions.trim()
    if (options.isNotEmpty()) {
        args.addAll(splitOptions(options))
    }

    // Chicory uses `--invoke <function_name>` to call a specific export
    val functionName = input.exportName.trim()
    if (functionName.isNotEmpty()) {
        args.add("--invoke")
        args.add(functionName)
    }

    args.add(input.wasmFilePath)

    val programArgs = input.programArguments.trim()
    if (programArgs.isNotEmpty()) {
        args.addAll(programArgs.split("\\s+".toRegex()))
    }

    val workDir = input.workingDirectory.ifBlank { null }
    return WasmCommandLine(arguments = args, workingDirectory = workDir)
}

// ---------------------------------------------------------------------------
// Node.js
// ---------------------------------------------------------------------------

/**
 * Builds the argument list for running a Wasm module via Node.js.
 *
 * Node.js does not have a built-in CLI flag to run `.wasm` files
 * directly, so we pass an inline script via `--eval` that reads the
 * binary, instantiates it through the WebAssembly API, and invokes
 * the requested export.
 *
 * The inline script:
 * 1. Reads the `.wasm` file from disk.
 * 2. Compiles and instantiates it (with a minimal WASI shim).
 * 3. Calls the target export (defaulting to `_start`).
 * 4. Prints the result to stdout when it is not `undefined`.
 */
fun buildNodeJsRunArgs(input: WasmRunInput): WasmCommandLine {
    val args = mutableListOf<String>()

    val options = input.runtimeOptions.trim()
    if (options.isNotEmpty()) {
        args.addAll(splitOptions(options))
    }

    val exportName = input.exportName.ifBlank { "_start" }
    val wasmPath = input.wasmFilePath.replace("\\", "\\\\").replace("'", "\\'")

    val programArgs = input.programArguments.trim()
    val argsArray = if (programArgs.isNotEmpty()) {
        programArgs.split("\\s+".toRegex()).joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
    } else ""

    val script = buildString {
        append("const fs=require('fs');")
        append("const buf=fs.readFileSync('$wasmPath');")
        append("(async()=>{")
        // Compile the module first to discover its imports, then build
        // stub objects for every imported module automatically.
        append("const mod=await WebAssembly.compile(buf);")
        append("const imps=WebAssembly.Module.imports(mod);")
        append("const importObj={};")
        append("for(const i of imps){")
        append("if(!importObj[i.module])importObj[i.module]={};")
        append("if(i.kind==='function'){")
        append("if(i.name==='proc_exit')importObj[i.module][i.name]=(c)=>process.exit(c);")
        append("else importObj[i.module][i.name]=()=>0;")
        append("}else if(i.kind==='memory'){")
        append("importObj[i.module][i.name]=new WebAssembly.Memory({initial:1});")
        append("}else if(i.kind==='table'){")
        append("importObj[i.module][i.name]=new WebAssembly.Table({initial:1,element:'anyfunc'});")
        append("}else if(i.kind==='global'){")
        append("importObj[i.module][i.name]=new WebAssembly.Global({value:'i32',mutable:true},0);")
        append("}}")
        append("const instance=await WebAssembly.instantiate(mod,importObj);")
        append("const fn=instance.exports['$exportName'];")
        append("if(!fn)throw new Error('Export not found: $exportName');")
        if (argsArray.isNotEmpty()) {
            append("const r=fn($argsArray);")
        } else {
            append("const r=fn();")
        }
        append("if(r!==undefined)console.log(r);")
        append("})()")
    }

    args.add("--eval")
    args.add(script)

    val workDir = input.workingDirectory.ifBlank { null }
    return WasmCommandLine(arguments = args, workingDirectory = workDir)
}
