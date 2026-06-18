package org.jetbrains.hexana.mcp.server

import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexanaPluginWrapperTelemetryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun claudeWrapperPreservesTelemetryArgumentsAndEnvironment() {
        assertWrapperPreservesTelemetry("mcp/packaging/claude-plugin/bin/hexana-mcp")
    }

    @Test
    fun codexWrapperPreservesTelemetryArgumentsAndEnvironment() {
        assertWrapperPreservesTelemetry("mcp/packaging/codex-plugin/bin/hexana-mcp")
    }

    @Test
    fun wrappersPreferBundledNativeExecutableWhenPresent() {
        assumeFalse("POSIX wrapper smoke is not applicable on Windows", isWindows())

        assertWrapperLauncherSelection("mcp/packaging/claude-plugin/bin/hexana-mcp", includeNative = true, expected = "native")
        assertWrapperLauncherSelection("mcp/packaging/codex-plugin/bin/hexana-mcp", includeNative = true, expected = "native")
    }

    @Test
    fun wrappersFallBackToBundledJvmLauncherWhenNativeExecutableIsMissing() {
        assumeFalse("POSIX wrapper smoke is not applicable on Windows", isWindows())

        assertWrapperLauncherSelection("mcp/packaging/claude-plugin/bin/hexana-mcp", includeNative = false, expected = "jvm")
        assertWrapperLauncherSelection("mcp/packaging/codex-plugin/bin/hexana-mcp", includeNative = false, expected = "jvm")
    }

    private fun assertWrapperPreservesTelemetry(wrapperPath: String) {
        assumeFalse("POSIX wrapper smoke is not applicable on Windows", isWindows())

        val outputPath = temporaryFolder.newFile("wrapper-output.txt").toPath()
        val fakeServer = createFakeServer(outputPath)
        val repositoryRoot = repositoryRoot()
        val wrapper = repositoryRoot.resolve(wrapperPath).normalize()

        val process = ProcessBuilder(
            wrapper.absolutePathString(),
            "stdio",
            "--log-level",
            "quiet",
            "--telemetry-enabled",
            "false",
        )
            .directory(repositoryRoot.toFile())
            .apply {
                environment()["HEXANA_SERVER_BIN"] = fakeServer.absolutePathString()
                environment()["HEXANA_MCP_TELEMETRY_ENABLED"] = "false"
            }
            .start()

        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Wrapper process timed out")
            val stderr = process.errorStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), stderr)
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                }
            }
        }

        val output = Files.readString(outputPath)
        assertTrue(output.contains("env=false"), output)
        assertTrue(output.contains("arg=stdio"), output)
        assertTrue(output.contains("arg=--log-level"), output)
        assertTrue(output.contains("arg=quiet"), output)
        assertTrue(output.contains("arg=--telemetry-enabled"), output)
        assertTrue(output.contains("arg=false"), output)
    }

    private fun assertWrapperLauncherSelection(wrapperPath: String, includeNative: Boolean, expected: String) {
        val outputPath = temporaryFolder.newFile().toPath()
        val pluginRoot = temporaryFolder.newFolder().toPath()
        val wrapper = pluginRoot.resolve("bin/hexana-mcp")
        Files.createDirectories(wrapper.parent)
        Files.copy(repositoryRoot().resolve(wrapperPath), wrapper, StandardCopyOption.REPLACE_EXISTING)
        wrapper.toFile().setExecutable(true)

        createFakeServer(
            outputPath = outputPath,
            destination = pluginRoot.resolve("server/bin/server"),
            launcherName = "jvm",
        )
        if (includeNative) {
            createFakeServer(
                outputPath = outputPath,
                destination = pluginRoot.resolve("native/${currentNativePlatformName()}/hexana-mcp"),
                launcherName = "native",
            )
        }

        val process = ProcessBuilder(wrapper.absolutePathString(), "version")
            .directory(pluginRoot.toFile())
            .start()

        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Wrapper process timed out")
            val stderr = process.errorStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), stderr)
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                }
            }
        }

        val output = Files.readString(outputPath)
        assertTrue(output.contains("launcher=$expected"), output)
        assertTrue(output.contains("arg=version"), output)
    }

    private fun createFakeServer(
        outputPath: Path,
        destination: Path = temporaryFolder.newFile("fake-server").toPath(),
        launcherName: String = "server",
    ): Path {
        Files.createDirectories(destination.parent)
        Files.writeString(
            destination,
            """
            #!/bin/sh
            set -eu
            {
              printf 'launcher=%s\n' "$launcherName"
              printf 'env=%s\n' "${'$'}{HEXANA_MCP_TELEMETRY_ENABLED:-}"
              for arg in "${'$'}@"; do
                printf 'arg=%s\n' "${'$'}arg"
              done
            } > "$outputPath"
            """.trimIndent()
        )
        destination.toFile().setExecutable(true)
        return destination
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }

    private fun currentNativePlatformName(): String {
        val os = when {
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux"
            else -> System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "x86_64", "amd64" -> "x64"
            "aarch64", "arm64" -> "aarch64"
            else -> System.getProperty("os.arch").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
        return "$os-$arch"
    }

    private fun repositoryRoot(): Path {
        return generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("mcp/packaging")) }
            ?: error("Failed to locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
