@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.StubFileWriter
import org.jetbrains.hexana.shared.WasmRunInput
import org.jetbrains.hexana.shared.buildWasmtimeRunArgs

/**
 * JS-exported result of building a Wasm runtime command line.
 */
@JsExport
data class CommandLineResult(
    val arguments: Array<String>,
    val workingDirectory: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is CommandLineResult && arguments.contentEquals(other.arguments) && workingDirectory == other.workingDirectory

    override fun hashCode(): Int = arguments.contentHashCode() + (workingDirectory?.hashCode() ?: 0)
}

/**
 * Callback interface that TypeScript implements to provide temporary
 * file writing for import stub generation.
 */
@JsExport
external interface JsStubFileWriter {
    fun createTempDir(prefix: String): String
    fun writeFile(dir: String, fileName: String, bytes: ByteArray): String
}

/**
 * Adapts a JS [JsStubFileWriter] to the shared [StubFileWriter] interface.
 */
private fun JsStubFileWriter.toStubFileWriter(): StubFileWriter = object : StubFileWriter {
    override fun createTempDir(prefix: String): String = this@toStubFileWriter.createTempDir(prefix)
    override fun writeFile(dir: String, fileName: String, bytes: ByteArray): String =
        this@toStubFileWriter.writeFile(dir, fileName, bytes)
}

/**
 * Builds the argument list for a Wasmtime `run` invocation.
 *
 * This exposes the shared [buildWasmtimeRunArgs] builder to TypeScript
 * so that the VS Code extension can produce the same command line as
 * the IntelliJ plugin without duplicating the logic.
 *
 * @param wasmFilePath path to the `.wasm` file to run
 * @param wasmBytes raw bytes of the wasm file, or `null`
 * @param programArguments whitespace-separated program arguments
 * @param runtimeOptions extra wasmtime CLI options
 * @param effectiveWasmPath path to the composed binary, or `null`
 * @param hasImports whether the module has imports
 * @param stubWriter JS callback for writing temp stub files, or `null`
 * @return a [CommandLineResult] with the argument list and working directory
 */
@JsExport
fun buildWasmtimeCommandLine(
    wasmFilePath: String,
    wasmBytes: ByteArray? = null,
    programArguments: String = "",
    runtimeOptions: String = "",
    effectiveWasmPath: String? = null,
    stubWriter: JsStubFileWriter? = null,
): CommandLineResult {
    val input = WasmRunInput(
        wasmFilePath = wasmFilePath,
        wasmBytes = wasmBytes,
        programArguments = programArguments,
        runtimeOptions = runtimeOptions,
        effectiveWasmPath = effectiveWasmPath,
    )

    val result = buildWasmtimeRunArgs(
        input = input,
        stubFileWriter = stubWriter?.toStubFileWriter(),
    )

    return CommandLineResult(
        arguments = result.arguments.toTypedArray(),
        workingDirectory = result.workingDirectory,
    )
}
