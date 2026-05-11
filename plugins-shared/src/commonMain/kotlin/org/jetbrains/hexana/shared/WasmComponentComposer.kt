package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProviderType

/**
 * Platform-agnostic abstraction for executing external CLI tools.
 *
 * Both IntelliJ (JVM `ProcessBuilder`) and VS Code (Node.js
 * `child_process`) implement this interface to run composition tools.
 */
interface ProcessRunner {
    /**
     * Executes a CLI command synchronously.
     *
     * @param command the executable path
     * @param args    command-line arguments
     * @return an empty string on success, or the error message on failure
     */
    fun exec(command: String, args: List<String>): String
}

/**
 * Platform-agnostic abstraction for resolving CLI tool paths and
 * checking file existence.
 */
interface ToolResolver {
    /**
     * Resolves the absolute path to a CLI tool, or `null` when not found.
     */
    fun resolveToolPath(toolName: String): String?

    /** Returns `true` when the file at [path] exists. */
    fun fileExists(path: String): Boolean
}

/**
 * Platform-agnostic abstraction for reading files during composition.
 *
 * This is deliberately minimal — callers that already have a
 * [WasmFileSystem] can adapt it with [WasmFileSystem.toComposerFileAccess].
 */
interface ComposerFileAccess {
    /** Reads a file as a byte array, or `null` if unreadable. */
    fun readFile(path: String): ByteArray?
}

/**
 * Adapts a [WasmFileSystem] to the [ComposerFileAccess] interface.
 */
fun WasmFileSystem.toComposerFileAccess(): ComposerFileAccess = object : ComposerFileAccess {
    override fun readFile(path: String): ByteArray? = this@toComposerFileAccess.readFile(path)
}

/**
 * Result of a component composition attempt.
 */
data class ComposeResult(
    /** The path to the (possibly composed) `.wasm` file. */
    val wasmPath: String,
    /** `true` when composition succeeded and [wasmPath] points to the composed output. */
    val composed: Boolean,
    /** Error message when [composed] is `false`, or `null` on success. */
    val error: String? = null,
)

/**
 * Composes Wasm component dependencies into a main component using
 * external CLI tools (`wasm-tools compose` or `wac plug`).
 *
 * When a Wasm component imports interfaces from other components, those
 * dependencies cannot be supplied at runtime via Wasmtime's `--preload`
 * flag (which only works with core modules). Instead, the components
 * must be composed at build time.
 *
 * This utility first tries `wasm-tools compose`, then falls back to
 * `wac plug`. Both tools perform the same fundamental operation of
 * satisfying a component's imports with exports from other components,
 * producing a single composed output.
 *
 * Because `wasm-tools compose` (and `wac plug`) only satisfy the
 * **direct** imports of the input component, transitive dependencies
 * require iterative composition: after each pass the output is checked
 * for remaining non-runtime component imports, and composition is
 * repeated until all imports are satisfied or no further progress can
 * be made.
 */
object WasmComponentComposer {

    /** Maximum number of iterative compose passes to prevent infinite loops. */
    private const val MAX_COMPOSE_PASSES = 10

    /**
     * Composes the given component dependencies into the main Wasm file.
     *
     * @param mainWasmPath   path to the main Wasm component file
     * @param componentDeps  map of import name → file path for component dependencies
     * @param tempDir        temporary directory for composed output files
     * @param processRunner  platform-specific process execution callback
     * @param toolResolver   platform-specific tool resolution callback
     * @param fileAccess     platform-specific file reading callback
     * @return a [ComposeResult] indicating the outcome
     */
    fun compose(
        mainWasmPath: String,
        componentDeps: Map<String, String>,
        tempDir: String,
        processRunner: ProcessRunner,
        toolResolver: ToolResolver,
        fileAccess: ComposerFileAccess,
    ): ComposeResult {
        if (componentDeps.isEmpty()) return ComposeResult(mainWasmPath, composed = false)

        // Try wasm-tools compose first (more commonly available)
        val wasmToolsPath = toolResolver.resolveToolPath("wasm-tools")
        if (wasmToolsPath != null) {
            val result = composeIteratively(tempDir, mainWasmPath, componentDeps, fileAccess) { input, deps, output ->
                composeWithWasmTools(wasmToolsPath, input, deps, output, processRunner, toolResolver)
            }
            if (result.composed) return result
        }

        // Fall back to wac plug
        val wacPath = toolResolver.resolveToolPath("wac")
        if (wacPath != null) {
            val result = composeIteratively(tempDir, mainWasmPath, componentDeps, fileAccess) { input, deps, output ->
                composeWithWac(wacPath, input, deps, output, processRunner, toolResolver)
            }
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
        return ComposeResult(wasmPath = mainWasmPath, composed = false, error = errorMsg)
    }

    /**
     * Runs the given compose function iteratively until all non-runtime
     * component imports are satisfied or no further progress can be made.
     */
    private fun composeIteratively(
        tempDir: String,
        mainWasmPath: String,
        componentDeps: Map<String, String>,
        fileAccess: ComposerFileAccess,
        composeFn: (input: String, deps: Map<String, String>, output: String) -> ComposeResult,
    ): ComposeResult {
        var currentInput = mainWasmPath
        var remainingDeps = componentDeps.toMap()
        var lastResult: ComposeResult? = null

        for (pass in 1..MAX_COMPOSE_PASSES) {
            val outputFile = "$tempDir/composed_pass$pass.wasm"
            val result = composeFn(currentInput, remainingDeps, outputFile)
            if (!result.composed) return result

            lastResult = result
            val unresolvedImports = findUnresolvedComponentImports(result.wasmPath, fileAccess)
            if (unresolvedImports.isEmpty()) return result

            val nextDeps = remainingDeps.filterKeys { it in unresolvedImports }
            if (nextDeps.isEmpty() || nextDeps == remainingDeps) return result

            currentInput = result.wasmPath
            remainingDeps = nextDeps
        }

        return lastResult ?: ComposeResult(
            wasmPath = mainWasmPath, composed = false, error = "max compose passes reached",
        )
    }

    /**
     * Parses the given Wasm file and returns the set of non-runtime component
     * import names that are still unresolved.
     */
    internal fun findUnresolvedComponentImports(wasmPath: String, fileAccess: ComposerFileAccess): Set<String> {
        val bytes = fileAccess.readFile(wasmPath) ?: return emptySet()
        if (detectBinaryType(bytes) != BinaryProviderType.WasmComponent) return emptySet()
        return componentImportNames(bytes)
            .filter { !isRuntimeImport(it) }
            .toSet()
    }

    /**
     * Builds the `wasm-tools compose` command-line arguments.
     */
    fun buildWasmToolsComposeArgs(
        mainWasmPath: String,
        componentDeps: Map<String, String>,
        outputFile: String,
    ): List<String> {
        val args = mutableListOf("compose", mainWasmPath)
        for (depPath in componentDeps.values) {
            args.add("-d")
            args.add(depPath)
        }
        args.add("-o")
        args.add(outputFile)
        return args
    }

    /**
     * Builds the `wac plug` command-line arguments.
     */
    fun buildWacPlugArgs(
        mainWasmPath: String,
        componentDeps: Map<String, String>,
        outputFile: String,
    ): List<String> {
        val args = mutableListOf("plug", mainWasmPath)
        for (depPath in componentDeps.values) {
            args.add("--plug")
            args.add(depPath)
        }
        args.add("-o")
        args.add(outputFile)
        return args
    }

    private fun composeWithWasmTools(
        wasmToolsPath: String,
        mainWasmPath: String,
        componentDeps: Map<String, String>,
        outputFile: String,
        processRunner: ProcessRunner,
        toolResolver: ToolResolver,
    ): ComposeResult {
        val args = buildWasmToolsComposeArgs(mainWasmPath, componentDeps, outputFile)
        return runComposeTool(wasmToolsPath, args, mainWasmPath, outputFile, processRunner, toolResolver)
    }

    private fun composeWithWac(
        wacPath: String,
        mainWasmPath: String,
        componentDeps: Map<String, String>,
        outputFile: String,
        processRunner: ProcessRunner,
        toolResolver: ToolResolver,
    ): ComposeResult {
        val args = buildWacPlugArgs(mainWasmPath, componentDeps, outputFile)
        return runComposeTool(wacPath, args, mainWasmPath, outputFile, processRunner, toolResolver)
    }

    private fun runComposeTool(
        command: String,
        args: List<String>,
        mainWasmPath: String,
        outputFile: String,
        processRunner: ProcessRunner,
        toolResolver: ToolResolver,
    ): ComposeResult {
        val errorMessage = processRunner.exec(command, args)
        if (errorMessage.isNotEmpty()) {
            return ComposeResult(wasmPath = mainWasmPath, composed = false, error = errorMessage)
        }
        return if (toolResolver.fileExists(outputFile)) {
            ComposeResult(wasmPath = outputFile, composed = true, error = null)
        } else {
            ComposeResult(wasmPath = mainWasmPath, composed = false, error = "Output file not created")
        }
    }
}
