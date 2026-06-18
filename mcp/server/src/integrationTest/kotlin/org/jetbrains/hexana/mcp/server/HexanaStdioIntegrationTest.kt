package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaStdioIntegrationTest {
    private val logger = LoggerFactory.getLogger(HexanaStdioIntegrationTest::class.java)

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installedServerVersionCommandExitsPromptly() = runBlocking {
        val result = HexanaInstalledStdioClient.runCommand(StdioLauncher.Server, "version")

        assertFalse(
            result.timedOut,
            "Version command timed out. stdout=${result.stdout}, stderr=${result.stderr}, command=${result.command}"
        )
        assertEquals(0, result.exitCode, "stderr=${result.stderr}")
        assertTrue(
            result.stdout.trim().matches(Regex("""hexana-mcp \S+""")),
            "Unexpected version output: ${result.stdout}"
        )
    }

    @Test
    fun installedServerSupportsListToolsAndToolCallsOverStdio() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        withInstalledStdioClient { server ->
            assertProductionStdioSmoke(
                server = server,
                fixtureDirectory = temporaryFolder.root.toPath(),
                logger = logger,
                label = "installed"
            )
        }
    }

    @Test
    fun installedServerAdvancedProfileExposesAllToolsOverStdio() = runBlocking {
        withInstalledStdioClient(toolProfile = HexanaMcpToolProfile.ADVANCED) { server ->
            val tools = server.client.listTools(ListToolsRequest())
            assertEquals(EXPECTED_ADVANCED_HEXANA_TOOL_NAMES, tools.tools.map { it.name }.sorted())
        }
    }
}
