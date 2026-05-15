package org.jetbrains.hexana.mcp.server

import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
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

    private fun createFakeServer(outputPath: Path): Path {
        val fakeServer = temporaryFolder.newFile("fake-server").toPath()
        Files.writeString(
            fakeServer,
            """
            #!/bin/sh
            set -eu
            {
              printf 'env=%s\n' "${'$'}{HEXANA_MCP_TELEMETRY_ENABLED:-}"
              for arg in "${'$'}@"; do
                printf 'arg=%s\n' "${'$'}arg"
              done
            } > "$outputPath"
            """.trimIndent()
        )
        fakeServer.toFile().setExecutable(true)
        return fakeServer
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }

    private fun repositoryRoot(): Path {
        return generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("mcp/packaging")) }
            ?: error("Failed to locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
