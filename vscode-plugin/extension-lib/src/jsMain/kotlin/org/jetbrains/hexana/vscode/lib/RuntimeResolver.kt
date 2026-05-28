@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.WasmRuntimeResolver

/**
 * Resolves the executable command for a given Wasm runtime.
 *
 * Delegates to the shared [WasmRuntimeResolver.resolveRuntimeExecutable].
 * Platform-specific details (reading VS Code settings, environment
 * variables) remain in the TypeScript host.
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
    nodePath: String = "",
): String? = WasmRuntimeResolver.resolveRuntimeExecutable(runtime, isWindows, wasmtimePath, wamrPath, graalVmHome, nodePath)

/**
 * Builds a unified runtime command line by dispatching to the
 * correct shared builder.
 *
 * Delegates to the shared [WasmRuntimeResolver.buildRuntimeCommandLine].
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
    val result = WasmRuntimeResolver.buildRuntimeCommandLine(
        runtime = runtime,
        wasmFilePath = wasmFilePath,
        wasmBytes = wasmBytes,
        exportName = exportName,
        runtimeOptions = invokeArgs,
        programArgs = programArgs,
        effectiveWasmPath = effectiveWasmPath,
        stubFileWriter = stubWriter?.let { jsWriter ->
            object : org.jetbrains.hexana.shared.StubFileWriter {
                override fun createTempDir(prefix: String): String = jsWriter.createTempDir(prefix)
                override fun writeFile(dir: String, fileName: String, bytes: ByteArray): String =
                    jsWriter.writeFile(dir, fileName, bytes)
            }
        },
    )
    return CommandLineResult(
        arguments = result.arguments.toTypedArray(),
        workingDirectory = result.workingDirectory,
    )
}
