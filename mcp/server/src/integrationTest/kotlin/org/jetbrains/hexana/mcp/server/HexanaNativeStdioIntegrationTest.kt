package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun nativeExecutableRejectsRemovedDirectCommands() = runBlocking {
        listOf("summarize-binary", "summarize-module", "wasm-to-wat", "run-script", "tools").forEach { command ->
            val result = HexanaInstalledStdioClient.runCommand(StdioLauncher.NativeExecutable, command)

            assertFalse(result.timedOut, "$command timed out: stdout=${result.stdout}, stderr=${result.stderr}")
            assertTrue(result.exitCode != 0, "$command unexpectedly succeeded")
            assertEquals("", result.stdout, "$command must not write protocol-unsafe stdout")
            assertTrue(result.stderr.contains("no such subcommand"), result.stderr)
        }
    }
}
