package org.jetbrains.hexana.mcp.vscode.server

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class MainTest {
    @Test
    fun keepsExistingStandaloneSubcommands() {
        assertContentEquals(
            arrayOf("stdio", "--log-level", "debug"),
            normalizeVsCodeServerArgs(arrayOf("stdio", "--log-level", "debug"))
        )
    }

    @Test
    fun convertsTransportOptionToStandaloneSubcommand() {
        assertContentEquals(
            arrayOf("stdio", "--log-level", "quiet"),
            normalizeVsCodeServerArgs(arrayOf("--transport", "stdio", "--log-level", "quiet"))
        )
    }

    @Test
    fun preservesTelemetryFlagAfterTransportNormalization() {
        assertContentEquals(
            arrayOf("stdio", "--telemetry-enabled", "false"),
            normalizeVsCodeServerArgs(arrayOf("--transport", "stdio", "--telemetry-enabled", "false"))
        )
    }

    @Test
    fun convertsTransportOptionWhenItAppearsAfterOtherArgs() {
        assertContentEquals(
            arrayOf("http", "--host", "127.0.0.1", "--port", "3001"),
            normalizeVsCodeServerArgs(arrayOf("--host", "127.0.0.1", "--transport", "http", "--port", "3001"))
        )
    }

    @Test
    fun rejectsMissingTransportValue() {
        assertFailsWith<IllegalArgumentException> {
            normalizeVsCodeServerArgs(arrayOf("--transport"))
        }
    }

    @Test
    fun rejectsUnsupportedTransportValue() {
        assertFailsWith<IllegalArgumentException> {
            normalizeVsCodeServerArgs(arrayOf("--transport", "sse"))
        }
    }
}
