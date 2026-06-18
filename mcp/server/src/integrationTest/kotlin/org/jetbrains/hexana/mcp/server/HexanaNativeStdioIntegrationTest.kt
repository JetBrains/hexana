package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory

class HexanaNativeStdioIntegrationTest {
    private val logger = LoggerFactory.getLogger(HexanaNativeStdioIntegrationTest::class.java)

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun nativeExecutableSupportsListToolsAndToolCallsOverStdio() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        withInstalledStdioClient(launcher = StdioLauncher.NativeExecutable) { server ->
            assertProductionStdioSmoke(
                server = server,
                fixtureDirectory = temporaryFolder.root.toPath(),
                logger = logger,
                label = "native"
            )
        }
    }
}
