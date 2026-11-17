@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.ComposerFileAccess
import org.jetbrains.hexana.shared.ProcessRunner
import org.jetbrains.hexana.shared.ToolResolver
import org.jetbrains.hexana.shared.WasmComponentComposer

/**
 * Callback interface that TypeScript implements to execute external
 * CLI tools (e.g. `wasm-tools compose`, `wac plug`).
 *
 * Each method returns a simple success/error result so the composition
 * orchestration logic can remain in Kotlin.
 */
@JsExport
external interface JsProcessRunner {
    /**
     * Executes a CLI command synchronously.
     *
     * @param command  the executable path
     * @param args     command-line arguments
     * @return an empty string on success, or the error message on failure
     */
    fun exec(command: String, args: Array<String>): String
}

/**
 * Callback interface that TypeScript implements to resolve the path
 * to CLI tools and check file existence.
 */
@JsExport
external interface JsToolResolver {
    /**
     * Resolves the absolute path to a CLI tool, or `null` when not found.
     * The implementation checks common locations (e.g. `~/.cargo/bin/`)
     * and the system PATH.
     */
    fun resolveToolPath(toolName: String): String?

    /** Returns `true` when the file at [path] exists. */
    fun fileExists(path: String): Boolean
}

/**
 * Result of a component composition attempt.
 */
@JsExport
data class CompositionResult(
    /** The path to the (possibly composed) `.wasm` file. */
    val wasmPath: String,
    /** `true` when composition succeeded and [wasmPath] points to the composed output. */
    val composed: Boolean,
    /** Error message when [composed] is `false`, or `null` on success. */
    val error: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is CompositionResult && wasmPath == other.wasmPath && composed == other.composed && error == other.error

    override fun hashCode(): Int {
        var result = wasmPath.hashCode()
        result = 31 * result + composed.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

/**
 * Adapts a JS [JsProcessRunner] to the shared [ProcessRunner] interface.
 */
private fun JsProcessRunner.toProcessRunner(): ProcessRunner = object : ProcessRunner {
    override fun exec(command: String, args: List<String>): String =
        this@toProcessRunner.exec(command, args.toTypedArray())
}

/**
 * Adapts a JS [JsToolResolver] to the shared [ToolResolver] interface.
 */
private fun JsToolResolver.toToolResolver(): ToolResolver = object : ToolResolver {
    override fun resolveToolPath(toolName: String): String? = this@toToolResolver.resolveToolPath(toolName)
    override fun fileExists(path: String): Boolean = this@toToolResolver.fileExists(path)
}

/**
 * Adapts a [FileSystemAccess] to the shared [ComposerFileAccess] interface.
 */
private fun FileSystemAccess.toComposerFileAccess(): ComposerFileAccess = object : ComposerFileAccess {
    override fun readFile(path: String): ByteArray? = this@toComposerFileAccess.readFile(path)
}

/**
 * Attempts to compose component dependencies using `wasm-tools compose`
 * or `wac plug`.
 *
 * Delegates to the shared [WasmComponentComposer.compose] with JS-specific
 * adapter implementations for process execution, tool resolution, and
 * file access.
 *
 * @param mainWasmPath  path to the main component `.wasm` file
 * @param depNames      import names of the dependencies
 * @param depPaths      file paths of the corresponding dependencies (parallel to [depNames])
 * @param tempDir       temporary directory for composed output files
 * @param processRunner JS callback for executing CLI tools
 * @param toolResolver  JS callback for finding tools and checking files
 * @param fs            JS callback for reading files (reuses existing [FileSystemAccess])
 * @return a [CompositionResult] indicating the outcome
 */
@JsExport
fun composeComponents(
    mainWasmPath: String,
    depNames: Array<String>,
    depPaths: Array<String>,
    tempDir: String,
    processRunner: JsProcessRunner,
    toolResolver: JsToolResolver,
    fs: FileSystemAccess,
): CompositionResult {
    val deps = depNames.zip(depPaths).toMap()

    val result = WasmComponentComposer.compose(
        mainWasmPath = mainWasmPath,
        componentDeps = deps,
        tempDir = tempDir,
        processRunner = processRunner.toProcessRunner(),
        toolResolver = toolResolver.toToolResolver(),
        fileAccess = fs.toComposerFileAccess(),
    )

    return CompositionResult(
        wasmPath = result.wasmPath,
        composed = result.composed,
        error = result.error,
    )
}
