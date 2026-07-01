package org.jetbrains.hexana.mcp.server

import com.github.ajalt.clikt.core.CliktError
import kotlin.test.*

class HexanaCliTest {
    private fun cli() = HexanaCli()

    @Test
    fun parseReturnsHelpWhenNoArgumentsAreProvided() {
        assertEquals(HexanaCommand.Help, cli().parse(emptyArray()))
    }

    @Test
    fun parseUsesLibraryBackedDefaultsForStdio() {
        val command = cli().parse(arrayOf("stdio"))

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(HexanaLogLevel.INFO, command.logLevel)
        assertTrue(command.analyticsEnabled)
        assertEquals(HexanaMcpToolProfile.PRODUCTION, command.toolProfile)
    }

    @Test
    fun parseEnablesTelemetryByDefaultInNativeImageRuntime() {
        withNativeImageRuntimeProperty {
            val command = cli().parse(arrayOf("stdio"))

            assertIs<HexanaCommand.Stdio>(command)
            assertTrue(command.analyticsEnabled)
        }
    }

    @Test
    fun parseAcceptsExplicitTelemetryOptInInNativeImageRuntime() {
        withNativeImageRuntimeProperty {
            val command = cli().parse(arrayOf("stdio", "--telemetry-enabled", "true"))

            assertIs<HexanaCommand.Stdio>(command)
            assertTrue(command.analyticsEnabled)
        }
    }

    @Test
    fun parseAcceptsTelemetryOptOutForStdio() {
        val command = cli().parse(arrayOf("stdio", "--telemetry-enabled", "false"))

        assertIs<HexanaCommand.Stdio>(command)
        assertFalse(command.analyticsEnabled)
    }

    @Test
    fun parseAcceptsAdvancedToolProfileForStdio() {
        val command = cli().parse(arrayOf("stdio", "--tool-profile", "advanced"))

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(HexanaMcpToolProfile.ADVANCED, command.toolProfile)
    }

    @Test
    fun parseNormalizesHttpEndpointAndKeepsLoopbackDefaults() {
        val command = cli().parse(
            arrayOf(
                "http",
                "--endpoint",
                "api",
                "--log-level",
                "debug",
                "--telemetry-enabled",
                "false",
                "--tool-profile",
                "full",
            )
        )

        assertIs<HexanaCommand.Http>(command)
        assertEquals("127.0.0.1", command.host)
        assertEquals(3000, command.port)
        assertEquals("/api", command.endpoint)
        assertEquals(HexanaLogLevel.DEBUG, command.logLevel)
        assertFalse(command.analyticsEnabled)
        assertEquals(HexanaMcpToolProfile.ADVANCED, command.toolProfile)
    }

    @Test
    fun parseRejectsPublicBindWithoutExplicitOptIn() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("http", "--host", "0.0.0.0"))
        }

        assertTrue(error.message.orEmpty().contains("--allow-public-bind"))
    }

    @Test
    fun parseAcceptsNonDefaultLoopbackAddress() {
        val command = cli().parse(arrayOf("http", "--host", "127.0.0.2"))

        assertIs<HexanaCommand.Http>(command)
        assertEquals("127.0.0.2", command.host)
    }

    @Test
    fun helpTextIncludesCurrentSubcommands() {
        val help = cli().helpText()

        assertTrue(help.contains("Standalone Hexana MCP server"))
        assertTrue(help.contains("stdio"))
        assertTrue(help.contains("http"))
        assertTrue(help.contains("version"))
        assertTrue(help.contains("help"))
    }

    @Test
    fun parseRejectsUnsupportedLogLevel() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("stdio", "--log-level", "trace"))
        }

        assertTrue(error.message.orEmpty().contains("quiet"))
        assertTrue(error.message.orEmpty().contains("info"))
        assertTrue(error.message.orEmpty().contains("debug"))
    }

    @Test
    fun parseRejectsUnsupportedTelemetryValue() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("stdio", "--telemetry-enabled", "maybe"))
        }

        assertTrue(error.message.orEmpty().contains("true"))
        assertTrue(error.message.orEmpty().contains("false"))
    }

    @Test
    fun parseRejectsUnsupportedToolProfile() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("stdio", "--tool-profile", "minimal"))
        }

        assertTrue(error.message.orEmpty().contains("production"))
        assertTrue(error.message.orEmpty().contains("advanced"))
    }

    @Test
    fun nativeImageRuntimeDetectionChecksGraalVmRuntimeMarker() {
        assertFalse(isRunningInGraalVmNativeImage(imageCodeProperty = null))
        assertFalse(isRunningInGraalVmNativeImage(imageCodeProperty = "buildtime"))
        assertTrue(isRunningInGraalVmNativeImage(imageCodeProperty = "runtime"))
    }

    private fun withNativeImageRuntimeProperty(block: () -> Unit) {
        val previousValue = System.getProperty(GRAALVM_NATIVE_IMAGE_CODE_PROPERTY)
        try {
            System.setProperty(GRAALVM_NATIVE_IMAGE_CODE_PROPERTY, "runtime")
            block()
        } finally {
            if (previousValue == null) {
                System.clearProperty(GRAALVM_NATIVE_IMAGE_CODE_PROPERTY)
            } else {
                System.setProperty(GRAALVM_NATIVE_IMAGE_CODE_PROPERTY, previousValue)
            }
        }
    }

    private companion object {
        const val GRAALVM_NATIVE_IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode"
    }
}
