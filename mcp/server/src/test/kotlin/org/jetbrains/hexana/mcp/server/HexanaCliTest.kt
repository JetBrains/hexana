package org.jetbrains.hexana.mcp.server

import com.github.ajalt.clikt.core.CliktError
import kotlin.test.*

class HexanaCliTest {
    private val cli = HexanaCli()

    @Test
    fun parseReturnsHelpWhenNoArgumentsAreProvided() {
        assertEquals(HexanaCommand.Help, cli.parse(emptyArray()))
    }

    @Test
    fun parseUsesLibraryBackedDefaultsForStdio() {
        val command = cli.parse(arrayOf("stdio"))

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(HexanaLogLevel.INFO, command.logLevel)
    }

    @Test
    fun parseNormalizesHttpEndpointAndKeepsLoopbackDefaults() {
        val command = cli.parse(arrayOf("http", "--endpoint", "api", "--log-level", "debug"))

        assertIs<HexanaCommand.Http>(command)
        assertEquals("127.0.0.1", command.host)
        assertEquals(3000, command.port)
        assertEquals("/api", command.endpoint)
        assertEquals(HexanaLogLevel.DEBUG, command.logLevel)
    }

    @Test
    fun parseRejectsPublicBindWithoutExplicitOptIn() {
        val error = assertFailsWith<CliktError> {
            cli.parse(arrayOf("http", "--host", "0.0.0.0"))
        }

        assertTrue(error.message.orEmpty().contains("--allow-public-bind"))
    }

    @Test
    fun parseAcceptsNonDefaultLoopbackAddress() {
        val command = cli.parse(arrayOf("http", "--host", "127.0.0.2"))

        assertIs<HexanaCommand.Http>(command)
        assertEquals("127.0.0.2", command.host)
    }

    @Test
    fun helpTextIncludesCurrentSubcommands() {
        val help = cli.helpText()

        assertTrue(help.contains("Standalone Hexana MCP server"))
        assertTrue(help.contains("stdio"))
        assertTrue(help.contains("http"))
        assertTrue(help.contains("version"))
        assertTrue(help.contains("help"))
    }

    @Test
    fun parseRejectsUnsupportedLogLevel() {
        val error = assertFailsWith<CliktError> {
            cli.parse(arrayOf("stdio", "--log-level", "trace"))
        }

        assertTrue(error.message.orEmpty().contains("quiet"))
        assertTrue(error.message.orEmpty().contains("info"))
        assertTrue(error.message.orEmpty().contains("debug"))
    }
}
