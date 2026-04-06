package org.jetbrains.hexana.mcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HexanaVersionWarningTest {
    @Test
    fun noWarningForVersionCommand() {
        assertNull(developmentVersionWarning(HexanaCommand.Version, "dev"))
    }

    @Test
    fun noWarningForHelpCommand() {
        assertNull(developmentVersionWarning(HexanaCommand.Help, "dev"))
    }

    @Test
    fun noWarningForNonDevVersion() {
        assertNull(
            developmentVersionWarning(
                HexanaCommand.Stdio(HexanaLogLevel.INFO),
                "0.7"
            )
        )
    }

    @Test
    fun warningForRuntimeCommandsUsingDevVersion() {
        val warning = developmentVersionWarning(
            HexanaCommand.Http(
                host = "127.0.0.1",
                port = 8080,
                endpoint = "/mcp",
                allowPublicBind = false,
                logLevel = HexanaLogLevel.INFO
            ),
            "dev"
        )

        assertEquals(
            "Hexana MCP server is running with fallback version `dev`. " +
                "Set -Dhexana.version or package the server with an implementation version.",
            warning
        )
    }
}
