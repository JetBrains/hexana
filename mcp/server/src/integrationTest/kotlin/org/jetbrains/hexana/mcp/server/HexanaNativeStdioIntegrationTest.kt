package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HexanaNativeStdioIntegrationTest {
    private val logger = LoggerFactory.getLogger(HexanaNativeStdioIntegrationTest::class.java)

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun nativeExecutableSupportsListToolsAndToolCallsOverStdio() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        val fixtureDirectory = temporaryFolder.root.toPath()
        withInstalledStdioClient(
            launcher = StdioLauncher.NativeExecutable,
            allowedRoots = listOf(fixtureDirectory)
        ) { server ->
            assertDefaultStdioSmoke(
                server = server,
                fixtureDirectory = fixtureDirectory,
                logger = logger,
                label = "native"
            )
        }
    }

    @Test
    fun nativeExecutableRunsRunScriptCliCommand() = runBlocking {
        val binary = temporaryFolder.newFile("native-run-script.bin").toPath()
        Files.write(binary, byteArrayOf(8, 9, 10))

        val result = HexanaInstalledStdioClient.runCommand(
            StdioLauncher.NativeExecutable,
            "run-script",
            binary.toString(),
            "--script-text",
            "print(binary.bytes.size); print(binary.bytes.get(2));",
            "--format",
            "raw",
        )

        assertFalse(
            result.timedOut,
            "run-script command timed out. stdout=${result.stdout}, stderr=${result.stderr}, command=${result.command}"
        )
        assertEquals(0, result.exitCode, "stdout=${result.stdout}, stderr=${result.stderr}")
        assertEquals("", result.unexpectedStderr, "Native run-script should not log to stderr.")
        assertEquals("3\n10", result.stdout)
    }
}
