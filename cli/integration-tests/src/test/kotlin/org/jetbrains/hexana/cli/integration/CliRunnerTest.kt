package org.jetbrains.hexana.cli.integration

import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CliRunnerTest {
    @Test
    fun `kills a non-terminating process tree on timeout`() {
        val result = CliRunner.run(
            command = listOf(javaLauncher(), "-cp", System.getProperty("java.class.path"), TimeoutFixture::class.java.name),
            timeout = Duration.ofSeconds(3),
            disableTelemetry = false,
        )
        assertTrue(result.timedOut, result.describe())
        val childPid = result.stdout.trim().toLongOrNull() ?: fail("Fixture did not report its child pid: ${result.describe()}")
        assertTrue(childPid in result.destroyedProcessIds, result.describe())
        assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false), "child $childPid survived timeout teardown")
    }

    @Test
    fun `times out while oversized stdin is blocked by a non-reader`() {
        val result = CliRunner.run(
            command = listOf(javaLauncher(), "-cp", System.getProperty("java.class.path"), TimeoutFixture::class.java.name, "non-reader"),
            stdin = ByteArray(2 * 1024 * 1024),
            timeout = Duration.ofMillis(500),
            disableTelemetry = false,
        )
        assertTrue(result.timedOut, result.describe())
        assertTrue(result.destroyedProcessIds.isNotEmpty(), result.describe())
    }

    @Test
    fun `returns the result when a process exits before consuming stdin`() {
        val result = CliRunner.run(
            command = listOf(javaLauncher(), "-cp", System.getProperty("java.class.path"), TimeoutFixture::class.java.name, "early-exit"),
            stdin = ByteArray(2 * 1024 * 1024),
            disableTelemetry = false,
        )
        assertFalse(result.timedOut, result.describe())
        assertEquals(0, result.exitCode, result.describe())
    }
}

private fun javaLauncher(): String = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString()

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
