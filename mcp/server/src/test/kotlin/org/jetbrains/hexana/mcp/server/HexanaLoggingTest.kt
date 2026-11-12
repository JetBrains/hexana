package org.jetbrains.hexana.mcp.server

import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexanaLoggingTest {
    @Test
    fun quietLevelKeepsOnlyErrorsOnStderr() {
        val result = runProbe("quiet")

        assertEquals("", result.stdout)
        assertEquals(listOf("ERROR probe-error"), result.stderrLines)
    }

    @Test
    fun infoLevelWritesInfoWarnAndErrorToStderr() {
        val result = runProbe("info")

        assertEquals("", result.stdout)
        assertEquals(listOf("INFO probe-info", "WARN probe-warn", "ERROR probe-error"), result.stderrLines)
    }

    @Test
    fun debugLevelAddsDebugOutput() {
        val result = runProbe("debug")

        assertEquals("", result.stdout)
        assertEquals("DEBUG probe-debug", result.stderrLines.first())
        assertEquals(4, result.stderrLines.size)
    }

    @Test
    fun doctorSeesTheStderrConsoleTarget() {
        val result = runProbe("info", "targets")

        assertTrue("TARGETS [STDERR]" in result.stdout)
    }

    @Test
    fun invalidCommandLineKeepsHexanaLogFormatOnStderr() {
        val logDirectory = Files.createTempDirectory("hexana-logging-test")
        try {
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val builder = ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                "org.jetbrains.hexana.mcp.server.MainKt",
                "stdio",
                "--no-such-option",
            )
            builder.environment()[HEXANA_MCP_LOG_DIR_ENV] = logDirectory.toString()
            val process = builder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "server did not exit")

            assertEquals("", stdout)
            assertTrue(process.exitValue() != 0)
            assertTrue("hexana-mcp error [CONFIGURATION" in stderr, stderr)
            assertTrue(stderr.lines().none { it.startsWith("[main]") }, "default slf4j-simple format leaked: $stderr")
        } finally {
            logDirectory.toFile().deleteRecursively()
        }
    }

    private fun runProbe(level: String, mode: String = "log"): ProbeResult {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "org.jetbrains.hexana.mcp.server.HexanaLoggingProbeKt",
            level,
            mode,
        ).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "probe did not finish")
        assertEquals(0, process.exitValue(), "probe failed: $stderr")
        return ProbeResult(stdout.trim(), stderr.lines().filter(String::isNotBlank))
    }

    private data class ProbeResult(val stdout: String, val stderrLines: List<String>)
}
