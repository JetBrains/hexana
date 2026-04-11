package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files

/**
 * Composes wasm component dependencies into the main component.
 *
 * When a wasm component imports interfaces from other components, those
 * dependencies cannot be supplied at runtime via wasmtime's `--preload` flag
 * (which only works with core modules). Instead, the components must be
 * composed at build time.
 *
 * This utility first tries `wasm-tools compose` (from the `wasm-tools` CLI),
 * then falls back to `wac plug` (from the `wac` CLI). Both tools perform
 * the same fundamental operation of satisfying a component's imports with
 * exports from other components, producing a single composed output.
 */
internal object WacComponentComposer {

    private val LOG = Logger.getInstance(WacComponentComposer::class.java)

    /**
     * Composes the given component dependencies into the main wasm file,
     * returning the path to the composed output file.
     *
     * @param mainWasmPath path to the main wasm component file
     * @param componentDeps map of import name → file path for component dependencies
     * @return path to the composed wasm file, or the original [mainWasmPath]
     *         if composition is not needed or fails
     */
    fun compose(mainWasmPath: String, componentDeps: Map<String, String>): ComposeResult {
        if (componentDeps.isEmpty()) return ComposeResult(mainWasmPath, composed = false)

        val tempDir = Files.createTempDirectory("hexana-wac-compose").toFile()
        tempDir.deleteOnExit()
        val outputFile = File(tempDir, "composed.wasm")
        outputFile.deleteOnExit()

        // Try wasm-tools compose first (more commonly available)
        val wasmToolsPath = resolveWasmToolsPath()
        if (wasmToolsPath != null) {
            val result = composeWithWasmTools(wasmToolsPath, mainWasmPath, componentDeps, outputFile)
            if (result.composed) return result
            LOG.info("wasm-tools compose failed: ${result.error}, trying wac plug")
        }

        // Fall back to wac plug
        val wacPath = resolveWacPath()
        if (wacPath != null) {
            val result = composeWithWac(wacPath, mainWasmPath, componentDeps, outputFile)
            if (result.composed) return result
            LOG.info("wac plug failed: ${result.error}")
        }

        val toolNames = listOfNotNull(
            if (wasmToolsPath != null) "wasm-tools" else null,
            if (wacPath != null) "wac" else null,
        )
        val errorMsg = if (toolNames.isEmpty()) {
            "No composition tool found. Install 'wasm-tools' (cargo install wasm-tools) or 'wac' (cargo install wac-cli) to compose component dependencies."
        } else {
            "Component composition failed with ${toolNames.joinToString(" and ")}."
        }
        return ComposeResult(mainWasmPath, composed = false, error = errorMsg)
    }

    /**
     * Compose using `wasm-tools compose` with `-d` (definitions) flags.
     */
    private fun composeWithWasmTools(
        wasmToolsPath: String,
        mainWasmPath: String,
        componentDeps: Map<String, String>,
        outputFile: File,
    ): ComposeResult {
        val command = mutableListOf(wasmToolsPath, "compose", mainWasmPath)
        for ((_, path) in componentDeps) {
            command.add("-d")
            command.add(path)
        }
        command.add("-o")
        command.add(outputFile.absolutePath)

        return runCommand(command, mainWasmPath, outputFile)
    }

    /**
     * Compose using `wac plug` with `--plug` flags.
     */
    private fun composeWithWac(
        wacPath: String,
        mainWasmPath: String,
        componentDeps: Map<String, String>,
        outputFile: File,
    ): ComposeResult {
        val command = mutableListOf(wacPath, "plug", mainWasmPath)
        for ((_, path) in componentDeps) {
            command.add("--plug")
            command.add(path)
        }
        command.add("-o")
        command.add(outputFile.absolutePath)

        return runCommand(command, mainWasmPath, outputFile)
    }

    private fun runCommand(command: List<String>, mainWasmPath: String, outputFile: File): ComposeResult {
        return try {
            LOG.info("Running: ${command.joinToString(" ")}")
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0 && outputFile.exists()) {
                ComposeResult(outputFile.absolutePath, composed = true)
            } else {
                ComposeResult(mainWasmPath, composed = false, error = output.ifBlank { "exited with code $exitCode" })
            }
        } catch (e: Exception) {
            ComposeResult(mainWasmPath, composed = false, error = e.message ?: "unknown error")
        }
    }

    private fun resolveWasmToolsPath(): String? {
        val candidates = listOfNotNull(
            System.getenv("HOME")?.let { "$it/.cargo/bin/wasm-tools" },
            "wasm-tools",
        )
        return candidates.firstOrNull { path ->
            if (path == "wasm-tools") {
                isCommandAvailable(path)
            } else {
                File(path).exists()
            }
        }
    }

    private fun resolveWacPath(): String? {
        val candidates = listOfNotNull(
            System.getenv("HOME")?.let { "$it/.cargo/bin/wac" },
            "wac",
        )
        return candidates.firstOrNull { path ->
            if (path == "wac") {
                isCommandAvailable(path)
            } else {
                File(path).exists()
            }
        }
    }

    private fun isCommandAvailable(command: String): Boolean {
        return try {
            val process = ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    data class ComposeResult(
        val wasmPath: String,
        val composed: Boolean,
        val error: String? = null,
    )
}
