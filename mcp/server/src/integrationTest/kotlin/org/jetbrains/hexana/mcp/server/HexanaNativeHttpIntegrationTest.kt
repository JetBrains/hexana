package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.slf4j.LoggerFactory

class HexanaNativeHttpIntegrationTest {
    private val logger = LoggerFactory.getLogger(HexanaNativeHttpIntegrationTest::class.java)

    @Test
    fun nativeExecutableSupportsHealthAndListToolsOverHttp() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        withInstalledServer(launcher = HttpLauncher.NativeExecutable) { server ->
            assertProductionHttpSmoke(
                server = server,
                logger = logger,
                label = "native"
            )
        }
    }

    @Test
    fun nativeExecutableRejectsInvalidLocalHttpOriginAndHost() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        withInstalledServer(launcher = HttpLauncher.NativeExecutable) { server ->
            assertLocalHttpProtectionSmoke(
                server = server,
                logger = logger,
                label = "native"
            )
        }
    }
}
