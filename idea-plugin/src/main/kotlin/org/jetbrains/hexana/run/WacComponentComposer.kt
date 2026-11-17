package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.hexana.shared.ComposerFileAccess
import org.jetbrains.hexana.shared.ProcessRunner
import org.jetbrains.hexana.shared.ToolResolver
import org.jetbrains.hexana.shared.WasmComponentComposer
import org.jetbrains.hexana.shared.ComposeResult
import java.io.File
import java.nio.file.Files

/**
 * JVM adapter that delegates component composition to the shared
 * [WasmComponentComposer], providing JVM-specific process execution,
 * tool resolution, and file access implementations.
 */
internal object WacComponentComposer {

    private val LOG = Logger.getInstance(WacComponentComposer::class.java)

    /** JVM implementation of [ProcessRunner] using `ProcessBuilder`. */
    private val JVM_PROCESS_RUNNER = object : ProcessRunner {
        override fun exec(command: String, args: List<String>): String {
            return try {
                val fullCommand = listOf(command) + args
                LOG.info("Running: ${fullCommand.joinToString(" ")}")
                val process = ProcessBuilder(fullCommand)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0) "" else output.ifBlank { "exited with code $exitCode" }
            } catch (e: Exception) {
                e.message ?: "unknown error"
            }
        }
    }

    /** JVM implementation of [ToolResolver] using file system and PATH checks. */
    private val JVM_TOOL_RESOLVER = object : ToolResolver {
        override fun resolveToolPath(toolName: String): String? {
            val exeName = if (SystemInfo.isWindows) "$toolName.exe" else toolName
            val candidates = listOfNotNull(
                cargoHomeBin(exeName),
                exeName,
            )
            return candidates.firstOrNull { path ->
                if (path == exeName) {
                    isCommandAvailable(path)
                } else {
                    File(path).exists()
                }
            }
        }

        override fun fileExists(path: String): Boolean = File(path).exists()
    }

    /** JVM implementation of [ComposerFileAccess] using `java.io.File`. */
    private val JVM_FILE_ACCESS = object : ComposerFileAccess {
        override fun readFile(path: String): ByteArray? {
            val file = File(path)
            return if (file.exists()) file.readBytes() else null
        }
    }

    /**
     * Composes the given component dependencies into the main wasm file,
     * returning the path to the composed output file.
     *
     * Delegates to the shared [WasmComponentComposer.compose] with
     * JVM-specific adapters for process execution, tool resolution,
     * and file access.
     *
     * @param mainWasmPath path to the main wasm component file
     * @param componentDeps map of import name → file path for component dependencies
     * @return a [ComposeResult] indicating the outcome
     */
    fun compose(mainWasmPath: String, componentDeps: Map<String, String>): ComposeResult {
        if (componentDeps.isEmpty()) return ComposeResult(mainWasmPath, composed = false)

        val tempDir = Files.createTempDirectory("hexana-wac-compose").toFile()
        tempDir.deleteOnExit()

        val result = WasmComponentComposer.compose(
            mainWasmPath = mainWasmPath,
            componentDeps = componentDeps,
            tempDir = tempDir.absolutePath,
            processRunner = JVM_PROCESS_RUNNER,
            toolResolver = JVM_TOOL_RESOLVER,
            fileAccess = JVM_FILE_ACCESS,
        )

        // Register all files inside the temp directory for deletion so that
        // deleteOnExit can actually remove the (now-empty) directory on shutdown.
        // Files must be registered after the directory so they are deleted first
        // (deleteOnExit processes entries in reverse registration order).
        tempDir.listFiles()?.forEach { it.deleteOnExit() }

        if (!result.composed && result.error != null) {
            LOG.info("Component composition failed: ${result.error}")
        }

        return result
    }

    /**
     * Returns the path to the given executable inside the Cargo bin
     * directory, respecting `CARGO_HOME` on all platforms and falling
     * back to `~/.cargo/bin` on Unix or `%USERPROFILE%\.cargo\bin`
     * on Windows.
     */
    private fun cargoHomeBin(exeName: String): String? {
        val cargoHome = System.getenv("CARGO_HOME")
        if (cargoHome != null) {
            return File(cargoHome, "bin" + File.separator + exeName).path
        }
        val userHome = System.getProperty("user.home") ?: return null
        return File(userHome, ".cargo" + File.separator + "bin" + File.separator + exeName).path
    }

    private fun isCommandAvailable(command: String): Boolean {
        return try {
            val cmd = if (SystemInfo.isWindows) {
                listOf("cmd.exe", "/c", "where", command)
            } else {
                listOf("which", command)
            }
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
