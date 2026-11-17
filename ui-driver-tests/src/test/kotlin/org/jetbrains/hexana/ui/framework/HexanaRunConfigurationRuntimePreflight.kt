package org.jetbrains.hexana.ui.framework

import com.intellij.tools.ide.util.common.logOutput
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

internal object HexanaRunConfigurationRuntimePreflight {
    private const val WASMTIME_VERSION = "v44.0.0"
    private const val WHATS_NEW_WASMTIME_ENV = "HEXANA_WASMTIME_PATH"
    private const val WHATS_NEW_LLDB_ENV = "HEXANA_WASM_LLDB_PATH"

    private val wasmtimePath by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        resolveWasmtimePath()?.also { logOutput("Wasmtime already available at: $it") } ?: downloadWasmtime()
    }

    private val nodePath by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        resolveNodePath()?.also { logOutput("Node.js already available at: $it") }
            ?: error("Node.js preflight failed: could not find a Node.js executable. Install Node.js before running Node.js debug tests.")
    }

    fun ensureWasmtime(): String = validateRuntimePath("Wasmtime", wasmtimePath)
    fun ensureNodeJs(): String = validateRuntimePath("Node.js", nodePath)

    fun ensureWasmtimeForWhatsNew018Capture(): String {
        val path = requiredCaptureExecutable(WHATS_NEW_WASMTIME_ENV, "Wasmtime")
        val output = runCapturePreflight(path, "--version")
        val major = Regex("\\bwasmtime\\s+(\\d+)", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("Wasmtime capture preflight failed: could not parse version from '$output'")
        require(major >= 44) {
            "Wasmtime capture preflight failed: version 44+ is required, got '$output'"
        }
        logOutput("What's New 0.18 Wasmtime preflight: $output ($path)")
        return path
    }

    fun ensureWasmLldbForWhatsNew018Capture(): String {
        val path = requiredCaptureExecutable(WHATS_NEW_LLDB_ENV, "LLDB")
        val version = runCapturePreflight(path, "--version")
        val plugins = runCapturePreflight(path, "-b", "-o", "plugin list", "-o", "quit")
        val hasWasmProcessPlugin = Regex(
            "(?ms)^process\\s*$.*?^\\s*\\[\\+\\]\\s+wasm\\s+.*WebAssembly debugging plug-in\\.\\s*$",
        ).containsMatchIn(plugins)
        require(hasWasmProcessPlugin) {
            "LLDB capture preflight failed: 'plugin list' does not contain the process wasm plugin; " +
                "version='$version', path=$path"
        }
        logOutput("What's New 0.18 LLDB preflight: $version with process wasm ($path)")
        return path
    }

    private fun resolveNodePath(): String? = lookupExecutable(
        executableName = "node",
        candidates = listOf("/usr/local/bin/node", "/usr/bin/node"),
    )

    private fun resolveWasmtimePath(): String? = lookupExecutable(
        executableName = "wasmtime",
        candidates = listOf(defaultWasmtimePath()),
    )

    private fun lookupExecutable(executableName: String, candidates: List<String>): String? {
        candidates.firstOrNull { File(it).exists() }?.let { return it }
        return try {
            val process = ProcessBuilder(if (isWindows()) "where" else "which", executableName)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            val firstPath = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            if (process.exitValue() == 0 && firstPath != null && File(firstPath).exists()) firstPath else null
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultWasmtimePath(): String {
        val home = System.getProperty("user.home")
        val executableName = if (isWindows()) "wasmtime.exe" else "wasmtime"
        return "$home/.wasmtime/bin/$executableName"
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").contains("windows", ignoreCase = true)

    private fun validateRuntimePath(runtimeName: String, path: String): String {
        require(File(path).exists()) {
            "$runtimeName preflight failed: resolved executable does not exist: $path"
        }
        return path
    }

    private fun requiredCaptureExecutable(environmentVariable: String, runtimeName: String): String {
        val path = System.getenv(environmentVariable)?.trim()?.ifBlank { null }
            ?: error("$runtimeName capture preflight failed: $environmentVariable must point to the explicit executable")
        return validateRuntimePath(runtimeName, Path.of(path).toAbsolutePath().normalize().toString())
    }

    private fun runCapturePreflight(executable: String, vararg arguments: String): String {
        val process = ProcessBuilder(listOf(executable) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val completed = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            error("Capture preflight timed out: $executable ${arguments.joinToString(" ")}")
        }
        require(process.exitValue() == 0) {
            "Capture preflight failed (${process.exitValue()}): $executable ${arguments.joinToString(" ")}\n$output"
        }
        return output
    }

    private fun downloadWasmtime(): String {
        logOutput("Wasmtime not found, downloading...")
        val arch = System.getProperty("os.arch").let {
            when {
                it.contains("aarch64") || it.contains("arm64") -> "aarch64"
                it.contains("x86_64") || it.contains("amd64") -> "x86_64"
                else -> it
            }
        }
        val os = System.getProperty("os.name").lowercase().let {
            when {
                it.contains("mac") || it.contains("darwin") -> "macos"
                it.contains("linux") -> "linux"
                it.contains("windows") -> "windows"
                else -> error("Unsupported OS: $it")
            }
        }

        val ext = if (os == "windows") "zip" else "tar.xz"
        val fileName = "wasmtime-$WASMTIME_VERSION-$arch-$os.$ext"
        val url = "https://github.com/bytecodealliance/wasmtime/releases/download/$WASMTIME_VERSION/$fileName"
        val tempDir = Files.createTempDirectory("wasmtime-download")
        val archiveFile = tempDir.resolve(fileName)

        try {
            URI(url).toURL().openStream().use { input -> Files.copy(input, archiveFile) }

            val binDir = Path.of(System.getProperty("user.home"), ".wasmtime", "bin")
            Files.createDirectories(binDir)

            if (ext == "tar.xz") {
                val process = ProcessBuilder("tar", "xf", archiveFile.toString(), "-C", tempDir.toString())
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
                require(process.exitValue() == 0) {
                    "Failed to extract wasmtime archive: ${process.inputStream.bufferedReader().readText()}"
                }

                val extractedDir = Files.list(tempDir)
                    .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("wasmtime-") }
                    .findFirst()
                    .orElseThrow { error("Extracted wasmtime directory not found") }
                val targetBin = binDir.resolve("wasmtime")
                Files.copy(extractedDir.resolve("wasmtime"), targetBin, StandardCopyOption.REPLACE_EXISTING)
                targetBin.toFile().setExecutable(true)
            } else {
                unzip(archiveFile, tempDir)
                val extractedDir = Files.list(tempDir)
                    .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("wasmtime-") }
                    .findFirst()
                    .orElseThrow { error("Extracted wasmtime directory not found") }
                Files.copy(
                    extractedDir.resolve("wasmtime.exe"),
                    binDir.resolve("wasmtime.exe"),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }

        return resolveWasmtimePath() ?: error("Wasmtime preflight failed: not found after download")
    }

    private fun unzip(archiveFile: Path, destinationDir: Path) {
        val normalizedDestination = destinationDir.toAbsolutePath().normalize()
        ZipInputStream(Files.newInputStream(archiveFile)).use { zipInput ->
            generateSequence { zipInput.nextEntry }.forEach { entry ->
                val outputPath = normalizedDestination.resolve(entry.name).normalize()
                require(outputPath.startsWith(normalizedDestination)) {
                    "Refusing to extract ZIP entry outside destination: ${entry.name}"
                }
                if (entry.isDirectory) {
                    Files.createDirectories(outputPath)
                } else {
                    Files.createDirectories(outputPath.parent)
                    Files.copy(zipInput, outputPath, StandardCopyOption.REPLACE_EXISTING)
                }
                zipInput.closeEntry()
            }
        }
    }
}
