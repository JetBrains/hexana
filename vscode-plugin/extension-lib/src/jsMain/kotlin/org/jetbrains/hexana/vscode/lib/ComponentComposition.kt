@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.shared.componentImportNames
import org.jetbrains.hexana.shared.detectBinaryType
import org.jetbrains.hexana.shared.isRuntimeImport

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

/** Maximum number of iterative compose passes to prevent infinite loops. */
private const val MAX_COMPOSE_PASSES = 10

/**
 * Attempts to compose component dependencies using `wasm-tools compose`
 * or `wac plug`.
 *
 * Because `wasm-tools compose` (and `wac plug`) only satisfy the
 * **direct** imports of the input component, transitive dependencies
 * require iterative composition: after each pass the output is checked
 * for remaining non-runtime component imports, and composition is
 * repeated until all imports are satisfied or no further progress can
 * be made.
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

    // Try wasm-tools compose first
    val wasmToolsPath = toolResolver.resolveToolPath("wasm-tools")
    if (wasmToolsPath != null) {
        val result = composeIteratively(
            tempDir, mainWasmPath, deps, fs,
        ) { input, d, output -> composeWithWasmTools(wasmToolsPath, input, d, output, processRunner, toolResolver) }
        if (result.composed) return result
    }

    // Fall back to wac plug
    val wacPath = toolResolver.resolveToolPath("wac")
    if (wacPath != null) {
        val result = composeIteratively(
            tempDir, mainWasmPath, deps, fs,
        ) { input, d, output -> composeWithWac(wacPath, input, d, output, processRunner, toolResolver) }
        if (result.composed) return result
    }

    val toolNames = listOfNotNull(
        if (wasmToolsPath != null) "wasm-tools" else null,
        if (wacPath != null) "wac" else null,
    )
    val errorMsg = if (toolNames.isEmpty()) {
        "No composition tool found. Install 'wasm-tools' (cargo install wasm-tools) or 'wac' (cargo install wac-cli)."
    } else {
        "Component composition failed with ${toolNames.joinToString(" and ")}."
    }
    return CompositionResult(wasmPath = mainWasmPath, composed = false, error = errorMsg)
}

/**
 * Runs the given compose function iteratively until all non-runtime
 * component imports are satisfied or no further progress can be made.
 */
private fun composeIteratively(
    tempDir: String,
    mainWasmPath: String,
    deps: Map<String, String>,
    fs: FileSystemAccess,
    composeFn: (input: String, deps: Map<String, String>, output: String) -> CompositionResult,
): CompositionResult {
    var currentInput = mainWasmPath
    var remainingDeps = deps.toMap()
    var lastResult: CompositionResult? = null

    for (pass in 1..MAX_COMPOSE_PASSES) {
        val outputFile = "$tempDir/composed_pass$pass.wasm"
        val result = composeFn(currentInput, remainingDeps, outputFile)
        if (!result.composed) return result

        lastResult = result
        val unresolvedImports = findUnresolvedComponentImports(result.wasmPath, fs)
        if (unresolvedImports.isEmpty()) return result

        val nextDeps = remainingDeps.filterKeys { it in unresolvedImports }
        if (nextDeps.isEmpty() || nextDeps == remainingDeps) return result

        currentInput = result.wasmPath
        remainingDeps = nextDeps
    }

    return lastResult ?: CompositionResult(
        wasmPath = mainWasmPath, composed = false, error = "max compose passes reached",
    )
}

/**
 * Parses the given wasm file and returns the set of non-runtime component
 * import names that are still unresolved.
 */
private fun findUnresolvedComponentImports(wasmPath: String, fs: FileSystemAccess): Set<String> {
    val bytes = fs.readFile(wasmPath) ?: return emptySet()
    if (detectBinaryType(bytes) != BinaryProviderType.WasmComponent) return emptySet()
    return componentImportNames(bytes)
        .filter { !isRuntimeImport(it) }
        .toSet()
}

private fun composeWithWasmTools(
    wasmToolsPath: String,
    mainWasmPath: String,
    deps: Map<String, String>,
    outputFile: String,
    processRunner: JsProcessRunner,
    toolResolver: JsToolResolver,
): CompositionResult {
    val args = mutableListOf("compose", mainWasmPath)
    for (depPath in deps.values) {
        args.add("-d")
        args.add(depPath)
    }
    args.add("-o")
    args.add(outputFile)
    return runComposeTool(wasmToolsPath, args.toTypedArray(), mainWasmPath, outputFile, processRunner, toolResolver)
}

private fun composeWithWac(
    wacPath: String,
    mainWasmPath: String,
    deps: Map<String, String>,
    outputFile: String,
    processRunner: JsProcessRunner,
    toolResolver: JsToolResolver,
): CompositionResult {
    val args = mutableListOf("plug", mainWasmPath)
    for (depPath in deps.values) {
        args.add("--plug")
        args.add(depPath)
    }
    args.add("-o")
    args.add(outputFile)
    return runComposeTool(wacPath, args.toTypedArray(), mainWasmPath, outputFile, processRunner, toolResolver)
}

private fun runComposeTool(
    command: String,
    args: Array<String>,
    mainWasmPath: String,
    outputFile: String,
    processRunner: JsProcessRunner,
    toolResolver: JsToolResolver,
): CompositionResult {
    val errorMessage = processRunner.exec(command, args)
    if (errorMessage.isNotEmpty()) {
        return CompositionResult(wasmPath = mainWasmPath, composed = false, error = errorMessage)
    }
    return if (toolResolver.fileExists(outputFile)) {
        CompositionResult(wasmPath = outputFile, composed = true, error = null)
    } else {
        CompositionResult(wasmPath = mainWasmPath, composed = false, error = "Output file not created")
    }
}
