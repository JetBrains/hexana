package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer

/**
 * Platform-agnostic runtime executable resolution.
 *
 * Maps a runtime identifier (`"wasmtime"`, `"wamr"`, `"graalwasm"`,
 * `"wazero"`, `"chicory-interpreter"`, `"chicory-aot"`) to its default executable
 * name, respecting optional custom paths provided by the caller.
 * Platform-specific details (reading settings, environment variables,
 * checking file existence) remain in the host layer.
 */
object WasmRuntimeResolver {

    /**
     * Resolves the executable command for a given Wasm runtime.
     *
     * For `graalwasm`, the returned path points to the `java` executable
     * inside the configured JDK home. The deprecated GraalVM `wasm`
     * launcher is no longer shipped with modern GraalVM distributions;
     * instead, the thin `HexanaGraalWasmRunner` wrapper is compiled on
     * first use and executed via `java`.
     *
     * @param runtime      runtime identifier: `"wasmtime"`, `"wamr"`, `"graalwasm"`,
     *                      `"wazero"`, `"chicory-interpreter"`, or `"chicory-aot"`
     * @param isWindows    `true` on Windows (appends `.exe` to bare names)
     * @param wasmtimePath custom Wasmtime executable path (empty = use default)
     * @param wamrPath     custom WAMR (iwasm) executable path (empty = use default)
     * @param graalVmHome  GraalVM home directory (empty = not configured)
     * @param chicoryPath  custom Chicory CLI executable/JAR path (empty = use default)
     * @return the resolved executable path, or `null` when the runtime
     *         requires a home directory that was not provided (GraalWasm)
     */
    fun resolveRuntimeExecutable(
        runtime: String,
        isWindows: Boolean,
        wasmtimePath: String = "",
        wamrPath: String = "",
        wazeroPath: String = "",
        graalVmHome: String = "",
        nodePath: String = "",
        chicoryPath: String = "",
    ): String? {
        return when (runtime) {
            "wasmtime" -> {
                val custom = wasmtimePath.trim()
                if (custom.isNotEmpty()) custom
                else if (isWindows) "wasmtime.exe" else "wasmtime"
            }
            "wamr" -> {
                val custom = wamrPath.trim()
                if (custom.isNotEmpty()) custom
                else if (isWindows) "iwasm.exe" else "iwasm"
            }
            "wazero" -> {
                val custom = wazeroPath.trim()
                if (custom.isNotEmpty()) custom
                else if (isWindows) "wazero.exe" else "wazero"
            }
            "graalwasm" -> {
                val home = graalVmHome.trim()
                if (home.isEmpty()) return null
                val exeName = if (isWindows) "java.exe" else "java"
                val sep = if (isWindows) "\\" else "/"
                "${home}${sep}bin${sep}${exeName}"
            }
            "nodejs" -> {
                val custom = nodePath.trim()
                if (custom.isNotEmpty()) custom
                else if (isWindows) "node.exe" else "node"
            }
            "chicory-interpreter", "chicory-aot" -> {
                val custom = chicoryPath.trim()
                if (custom.isNotEmpty()) custom
                else if (isWindows) "chicory.bat" else "chicory"
            }
            else -> if (isWindows) "wasmtime.exe" else "wasmtime"
        }
    }

    /**
     * Builds a unified runtime command line by dispatching to the
     * correct shared builder.
     *
     * @param runtime          runtime identifier: `"wasmtime"`, `"wamr"`, `"graalwasm"`,
     *                          `"wazero"`, `"chicory-interpreter"`, or `"chicory-aot"`
     * @param wasmFilePath     path to the `.wasm` file
     * @param wasmBytes        raw bytes of the file, or `null`
     * @param exportName       function to invoke (used by WAMR/GraalWasm)
     * @param runtimeOptions   extra runtime CLI options
     * @param programArgs      program arguments
     * @param effectiveWasmPath composed binary path override, or `null`
     * @param stubFileWriter   callback for temp stub file writing, or `null`
     * @return [WasmCommandLine] with the argument list and working directory
     */
    fun buildRuntimeCommandLine(
        runtime: String,
        wasmFilePath: String,
        wasmBytes: ByteArray? = null,
        exportName: String = "",
        runtimeOptions: String = "",
        programArgs: String = "",
        effectiveWasmPath: String? = null,
        stubFileWriter: StubFileWriter? = null,
    ): WasmCommandLine {
        // For non-Wasmtime runtimes, strip any Wasmtime-specific `--invoke`
        // flag from runtimeOptions because WAMR and GraalWasm use their own
        // flags (`-f` / `--entry-point`) derived from the exportName parameter.
        val effectiveOptions = if (runtime != "wasmtime") {
            stripWasmtimeInvoke(runtimeOptions)
        } else {
            runtimeOptions
        }

        val input = WasmRunInput(
            wasmFilePath = wasmFilePath,
            wasmBuffer = wasmBytes?.toCommonByteBuffer(),
            programArguments = programArgs,
            runtimeOptions = effectiveOptions,
            exportName = exportName,
            effectiveWasmPath = effectiveWasmPath,
        )
        return when (runtime) {
            "wasmtime" -> buildWasmtimeRunArgs(input)
            "wamr" -> buildWamrRunArgs(input, stubFileWriter = stubFileWriter)
            "graalwasm" -> buildGraalWasmRunArgs(input)
            "wazero" -> buildWazeroRunArgs(input, stubFileWriter = stubFileWriter)
            "nodejs" -> buildNodeJsRunArgs(input)
            "chicory-interpreter" -> buildChicoryRunArgs(input, aot = false)
            "chicory-aot" -> buildChicoryRunArgs(input, aot = true)
            else -> buildWasmtimeRunArgs(input)
        }
    }
}

/**
 * Strips the Wasmtime-specific `--invoke <name>` or `--invoke "name(args)"`
 * pattern from a runtime options string.
 *
 * This is needed when the caller supplies a single options string that was
 * originally built for Wasmtime but is now being dispatched to WAMR or
 * GraalWasm, which use their own flags for specifying the export to invoke.
 */
internal fun stripWasmtimeInvoke(options: String): String {
    val trimmed = options.trim()
    if (!trimmed.contains("--invoke")) return trimmed

    // Match: --invoke "name(args)" (quoted form)
    val quotedPattern = Regex("""--invoke\s+"[^"]+"""")
    val afterQuoted = quotedPattern.replace(trimmed, "").trim()
    if (afterQuoted != trimmed) return afterQuoted

    // Match: --invoke name (simple form)
    val simplePattern = Regex("""--invoke\s+\S+""")
    return simplePattern.replace(trimmed, "").trim()
}
