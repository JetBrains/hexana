@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.shared.StubFileWriter
import org.jetbrains.hexana.shared.WasmRunInput
import org.jetbrains.hexana.shared.buildGraalWasmRunArgs
import org.jetbrains.hexana.shared.buildWamrDebugArgs
import org.jetbrains.hexana.shared.buildWamrRunArgs
import org.jetbrains.hexana.shared.buildWasmtimeDebugArgs
import org.jetbrains.hexana.shared.buildWasmtimeGdbArgs
import org.jetbrains.hexana.shared.buildWasmtimeRunArgs
import org.jetbrains.hexana.shared.looksLikeWamrDebugNotSupported

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
        wasmBuffer = wasmBytes?.toCommonByteBuffer(),
        programArguments = programArguments,
        runtimeOptions = runtimeOptions,
        effectiveWasmPath = effectiveWasmPath,
    )

    val result = buildWasmtimeRunArgs(input = input)

    return CommandLineResult(
        arguments = result.arguments.toTypedArray(),
        workingDirectory = result.workingDirectory,
    )
}

/**
 * Builds the argument list for a WAMR `iwasm` invocation.
 *
 * Unlike Wasmtime, WAMR does not support the Component Model.
 * Import stubs are written as `<module_name>.wasm` files in a
 * temporary directory that becomes the working directory so WAMR
 * can discover them by name.
 *
 * @param wasmFilePath path to the `.wasm` file to run
 * @param wasmBytes raw bytes of the wasm file, or `null`
 * @param programArguments whitespace-separated program arguments
 * @param runtimeOptions extra iwasm CLI options
 * @param exportName function name to invoke via `-f`, blank means default entry
 * @param stubWriter JS callback for writing temp stub files, or `null`
 * @return a [CommandLineResult] with the argument list and working directory
 */
@JsExport
fun buildWamrCommandLine(
    wasmFilePath: String,
    wasmBytes: ByteArray? = null,
    programArguments: String = "",
    runtimeOptions: String = "",
    exportName: String = "",
    stubWriter: JsStubFileWriter? = null,
): CommandLineResult {
    val input = WasmRunInput(
        wasmFilePath = wasmFilePath,
        wasmBuffer = wasmBytes?.toCommonByteBuffer(),
        programArguments = programArguments,
        runtimeOptions = runtimeOptions,
        exportName = exportName,
    )

    val result = buildWamrRunArgs(
        input = input,
        stubFileWriter = stubWriter?.toStubFileWriter(),
    )

    return CommandLineResult(
        arguments = result.arguments.toTypedArray(),
        workingDirectory = result.workingDirectory,
    )
}

/**
 * Builds the argument list for running a Wasm module via GraalVM.
 *
 * The returned arguments follow the same CLI convention as the
 * deprecated GraalVM `wasm` launcher and are consumed by the thin
 * `HexanaGraalWasmRunner` wrapper bundled with the extension.
 *
 * GraalWasm does not support the Component Model. The `--entry-point`
 * flag is used when a specific export function should be invoked.
 *
 * @param wasmFilePath path to the `.wasm` file to run
 * @param programArguments whitespace-separated program arguments
 * @param exportName function name to invoke via `--entry-point`, blank means default
 * @return a [CommandLineResult] with the argument list and working directory
 */
@JsExport
fun buildGraalWasmCommandLine(
    wasmFilePath: String,
    programArguments: String = "",
    exportName: String = "",
): CommandLineResult {
    val input = WasmRunInput(
        wasmFilePath = wasmFilePath,
        programArguments = programArguments,
        exportName = exportName,
    )

    val result = buildGraalWasmRunArgs(input)

    return CommandLineResult(
        arguments = result.arguments.toTypedArray(),
        workingDirectory = result.workingDirectory,
    )
}

// ── Debug command-line builders ─────────────────────────────────────

/**
 * JS-exported result of building a Wasmtime debug command line.
 *
 * @property arguments the wasmtime argument list (excluding the
 *                     `wasmtime` executable itself)
 * @property workingDirectory the working directory, or `null`
 */
@JsExport
data class WasmtimeDebugCommandLineResult(
    val arguments: Array<String>,
    val workingDirectory: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is WasmtimeDebugCommandLineResult && arguments.contentEquals(other.arguments) && workingDirectory == other.workingDirectory

    override fun hashCode(): Int = arguments.contentHashCode() + (workingDirectory?.hashCode() ?: 0)
}

/**
 * Builds the argument list for a Wasmtime debug invocation.
 *
 * This adds `-D debug-info -O opt-level=0` flags so that DWARF
 * metadata embedded in the Wasm binary is translated for native
 * debuggers (LLDB).
 *
 * @param wasmFilePath path to the `.wasm` file to debug
 * @param wasmBytes raw bytes of the wasm file, or `null`
 * @param programArguments whitespace-separated program arguments
 * @param runtimeOptions extra wasmtime CLI options
 * @param effectiveWasmPath path to the composed binary, or `null`
 * @return a [WasmtimeDebugCommandLineResult] with the argument list
 */
@JsExport
fun buildWasmtimeDebugCommandLine(
    wasmFilePath: String,
    wasmBytes: ByteArray? = null,
    programArguments: String = "",
    runtimeOptions: String = "",
    effectiveWasmPath: String? = null,
): WasmtimeDebugCommandLineResult {
    val input = WasmRunInput(
        wasmFilePath = wasmFilePath,
        wasmBuffer = wasmBytes?.toCommonByteBuffer(),
        programArguments = programArguments,
        runtimeOptions = runtimeOptions,
        effectiveWasmPath = effectiveWasmPath,
    )

    val result = buildWasmtimeDebugArgs(input)

    return WasmtimeDebugCommandLineResult(
        arguments = result.arguments.toTypedArray(),
        workingDirectory = result.workingDirectory,
    )
}

/**
 * JS-exported result of building a WAMR debug command line.
 *
 * @property arguments the iwasm argument list (excluding the
 *                     `iwasm` executable itself)
 * @property workingDirectory the working directory, or `null`
 * @property debugHost the host for the WAMR GDB remote stub
 * @property debugPort the port for the WAMR GDB remote stub
 */
@JsExport
data class WamrDebugCommandLineResult(
    val arguments: Array<String>,
    val workingDirectory: String?,
    val debugHost: String,
    val debugPort: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is WamrDebugCommandLineResult && arguments.contentEquals(other.arguments) &&
            workingDirectory == other.workingDirectory && debugHost == other.debugHost && debugPort == other.debugPort

    override fun hashCode(): Int = arguments.contentHashCode() + (workingDirectory?.hashCode() ?: 0) +
        debugHost.hashCode() + debugPort
}

/**
 * Builds the argument list for a WAMR (iwasm) debug invocation.
 *
 * The `-g=<host>:<port>` flag tells iwasm to start a GDB remote
 * stub and wait for a debugger to connect.
 *
 * @param wasmFilePath path to the `.wasm` file to debug
 * @param wasmBytes raw bytes of the wasm file, or `null`
 * @param programArguments whitespace-separated program arguments
 * @param runtimeOptions extra iwasm CLI options
 * @param exportName function name to invoke via `-f`, blank means default
 * @param host debug server host (default `"127.0.0.1"`)
 * @param port debug server port (default `1234`)
 * @param stubWriter JS callback for writing temp stub files, or `null`
 * @return a [WamrDebugCommandLineResult] with the argument list and
 *         debug connection address
 */
@JsExport
fun buildWamrDebugCommandLine(
    wasmFilePath: String,
    wasmBytes: ByteArray? = null,
    programArguments: String = "",
    runtimeOptions: String = "",
    exportName: String = "",
    host: String = "127.0.0.1",
    port: Int = 1234,
    stubWriter: JsStubFileWriter? = null,
): WamrDebugCommandLineResult {
    val input = WasmRunInput(
        wasmFilePath = wasmFilePath,
        wasmBuffer = wasmBytes?.toCommonByteBuffer(),
        programArguments = programArguments,
        runtimeOptions = runtimeOptions,
        exportName = exportName,
    )

    val result = buildWamrDebugArgs(
        input = input,
        host = host,
        port = port,
        stubFileWriter = stubWriter?.toStubFileWriter(),
    )

    return WamrDebugCommandLineResult(
        arguments = result.commandLine.arguments.toTypedArray(),
        workingDirectory = result.commandLine.workingDirectory,
        debugHost = result.debugHost,
        debugPort = result.debugPort,
    )
}

/**
 * JS-exported result of building a Wasmtime gdbstub command line.
 *
 * @property arguments the wasmtime argument list (excluding the
 *                     `wasmtime` executable itself)
 * @property workingDirectory the working directory, or `null`
 * @property debugHost the host wasmtime listens on for the gdbstub
 * @property debugPort the port wasmtime listens on for the gdbstub
 */
@JsExport
data class WasmtimeGdbCommandLineResult(
    val arguments: Array<String>,
    val workingDirectory: String?,
    val debugHost: String,
    val debugPort: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is WasmtimeGdbCommandLineResult && arguments.contentEquals(other.arguments) &&
            workingDirectory == other.workingDirectory && debugHost == other.debugHost && debugPort == other.debugPort

    override fun hashCode(): Int = arguments.contentHashCode() + (workingDirectory?.hashCode() ?: 0) +
        debugHost.hashCode() + debugPort
}

/**
 * Builds the argument list for a Wasmtime guest-debugging run using the
 * built-in gdbstub (`wasmtime run -g <host>:<port> module.wasm`).
 *
 * Exposes the shared [buildWasmtimeGdbArgs] builder to TypeScript so the
 * VS Code extension produces the same command line as the IntelliJ plugin:
 * no `-D debug-info` / JIT loader — the gdbstub exposes DWARF from the
 * wasm binary directly and LLDB attaches via `process connect --plugin wasm`.
 *
 * @param wasmFilePath path to the `.wasm` file to debug
 * @param wasmBytes raw bytes of the wasm file, or `null`
 * @param programArguments whitespace-separated program arguments
 * @param runtimeOptions extra wasmtime CLI options (including `--invoke`)
 * @param host address wasmtime should listen on (default `"127.0.0.1"`)
 * @param port port wasmtime should listen on
 * @param effectiveWasmPath path to the composed binary, or `null`
 */
@JsExport
fun buildWasmtimeGdbCommandLine(
    wasmFilePath: String,
    wasmBytes: ByteArray? = null,
    programArguments: String = "",
    runtimeOptions: String = "",
    host: String = "127.0.0.1",
    port: Int = 4712,
    effectiveWasmPath: String? = null,
): WasmtimeGdbCommandLineResult {
    val input = WasmRunInput(
        wasmFilePath = wasmFilePath,
        wasmBuffer = wasmBytes?.toCommonByteBuffer(),
        programArguments = programArguments,
        runtimeOptions = runtimeOptions,
        effectiveWasmPath = effectiveWasmPath,
    )

    val result = buildWasmtimeGdbArgs(input = input, port = port, host = host)

    return WasmtimeGdbCommandLineResult(
        arguments = result.arguments.toTypedArray(),
        workingDirectory = result.workingDirectory,
        debugHost = host,
        debugPort = port,
    )
}

/**
 * Detects whether iwasm output indicates the `-g` debug option is
 * not supported by the current build.
 */
@JsExport
fun isWamrDebugNotSupported(output: String): Boolean =
    looksLikeWamrDebugNotSupported(output)
