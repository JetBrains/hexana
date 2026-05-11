@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

/**
 * Resolves the executable command for a given Wasm runtime.
 *
 * This is a pure-logic function that maps a runtime identifier to its
 * default executable name, respecting optional custom paths provided
 * by the caller.  Platform-specific details (reading VS Code settings,
 * environment variables) remain in the TypeScript host.
 *
 * @param runtime     runtime identifier: `"wasmtime"`, `"wamr"`, or `"graalwasm"`
 * @param isWindows   `true` on Windows (appends `.exe` to bare names)
 * @param wasmtimePath custom Wasmtime executable path (empty = use default)
 * @param wamrPath     custom WAMR (iwasm) executable path (empty = use default)
 * @param graalVmHome  GraalVM home directory (empty = not configured)
 * @return the resolved executable path, or `null` when the runtime
 *         requires a home directory that was not provided (GraalWasm)
 */
@JsExport
fun resolveRuntimeExecutable(
    runtime: String,
    isWindows: Boolean,
    wasmtimePath: String = "",
    wamrPath: String = "",
    graalVmHome: String = "",
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
        "graalwasm" -> {
            val home = graalVmHome.trim()
            if (home.isEmpty()) return null
            val exeName = if (isWindows) "wasm.exe" else "wasm"
            "$home/bin/$exeName"
        }
        else -> if (isWindows) "wasmtime.exe" else "wasmtime"
    }
}

/**
 * Builds a unified runtime command line by dispatching to the
 * correct shared builder.
 *
 * This replaces the TypeScript `buildCommandLine` dispatcher that
 * switched on the runtime string and forwarded to individual
 * `extensionLib.build*CommandLine` calls.
 *
 * @param runtime          runtime identifier: `"wasmtime"`, `"wamr"`, or `"graalwasm"`
 * @param wasmFilePath     path to the `.wasm` file
 * @param wasmBytes        raw bytes of the file, or `null`
 * @param exportName       function to invoke (used by WAMR/GraalWasm)
 * @param invokeArgs       extra invoke arguments / runtime options
 * @param programArgs      program arguments
 * @param effectiveWasmPath composed binary path override, or `null`
 * @param stubWriter       JS callback for temp stub file writing, or `null`
 * @return [CommandLineResult] with the argument list and working directory
 */
@JsExport
fun buildRuntimeCommandLine(
    runtime: String,
    wasmFilePath: String,
    wasmBytes: ByteArray? = null,
    exportName: String = "",
    invokeArgs: String = "",
    programArgs: String = "",
    effectiveWasmPath: String? = null,
    stubWriter: JsStubFileWriter? = null,
): CommandLineResult {
    return when (runtime) {
        "wasmtime" -> buildWasmtimeCommandLine(
            wasmFilePath = wasmFilePath,
            wasmBytes = wasmBytes,
            programArguments = programArgs,
            runtimeOptions = invokeArgs,
            effectiveWasmPath = effectiveWasmPath,
            stubWriter = stubWriter,
        )
        "wamr" -> buildWamrCommandLine(
            wasmFilePath = wasmFilePath,
            wasmBytes = wasmBytes,
            programArguments = programArgs,
            runtimeOptions = "",
            exportName = exportName,
            stubWriter = stubWriter,
        )
        "graalwasm" -> buildGraalWasmCommandLine(
            wasmFilePath = wasmFilePath,
            programArguments = programArgs,
            exportName = exportName,
        )
        else -> buildWasmtimeCommandLine(
            wasmFilePath = wasmFilePath,
            wasmBytes = wasmBytes,
            programArguments = programArgs,
            runtimeOptions = invokeArgs,
            effectiveWasmPath = effectiveWasmPath,
            stubWriter = stubWriter,
        )
    }
}
