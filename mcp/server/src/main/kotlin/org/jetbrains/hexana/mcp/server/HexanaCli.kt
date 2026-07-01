package org.jetbrains.hexana.mcp.server

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import java.net.InetAddress

sealed interface HexanaCommand {
    data class Stdio(
        val logLevel: HexanaLogLevel,
        val analyticsKey: String?,
        val analyticsEnabled: Boolean,
        val toolProfile: HexanaMcpToolProfile
    ) : HexanaCommand

    data class Http(
        val host: String,
        val port: Int,
        val endpoint: String,
        val allowPublicBind: Boolean,
        val logLevel: HexanaLogLevel,
        val analyticsKey: String?,
        val analyticsEnabled: Boolean,
        val toolProfile: HexanaMcpToolProfile
    ) : HexanaCommand

    data object Version : HexanaCommand
    data object Help : HexanaCommand
}

class HexanaCli {
    private var parsedCommand: HexanaCommand? = null
    private val rootCommand = RootCommand { parsedCommand = it }

    fun parse(args: Array<String>): HexanaCommand {
        parsedCommand = null
        rootCommand.parse(args.asList())
        return parsedCommand ?: HexanaCommand.Help
    }

    fun helpText(): String = rootCommand.getFormattedHelp(null) ?: ""

    fun echoFormattedHelp(error: CliktError) {
        rootCommand.echoFormattedHelp(error)
    }

    private class RootCommand(
        private val sink: (HexanaCommand) -> Unit
    ) : CliktCommand(name = "hexana-mcp") {
        override fun help(context: Context): String = "Standalone Hexana MCP server for WebAssembly inspection."

        override val invokeWithoutSubcommand: Boolean = true
        override val printHelpOnEmptyArgs: Boolean = false

        init {
            subcommands(
                StdioCommand(sink),
                HttpCommand(sink),
                VersionCommand(sink),
                HelpCommand(sink)
            )
        }

        override fun run() = Unit
    }

    private class StdioCommand(
        private val sink: (HexanaCommand) -> Unit
    ) : CliktCommand(name = "stdio") {
        override fun help(context: Context): String = "Run the MCP server over stdio."

        private val logLevel by option(
            "--log-level",
            help = "Human-readable stderr logging level."
        ).convert("quiet|info|debug") { parseLogLevel(it) }.default(HexanaLogLevel.INFO)

        private val analyticsKey by option(
            "--analytics-key",
            help = "PostHog project API key. Enables anonymous usage analytics when provided.",
            envvar = "HEXANA_POSTHOG_KEY"
        )

        private val analyticsEnabled by analyticsEnabledOption()

        private val toolProfile by toolProfileOption()

        override fun run() {
            sink(
                HexanaCommand.Stdio(
                    logLevel = logLevel,
                    analyticsKey = analyticsKey,
                    analyticsEnabled = analyticsEnabled,
                    toolProfile = toolProfile
                )
            )
        }
    }

    private class HttpCommand(
        private val sink: (HexanaCommand) -> Unit
    ) : CliktCommand(name = "http") {
        override fun help(context: Context): String = "Run the MCP server over stateless Streamable HTTP."

        private val host by option(
            "--host",
            help = "Host interface to bind."
        ).default("127.0.0.1")

        private val port by option(
            "--port",
            help = "TCP port to bind."
        ).int().default(3000).validate {
            require(it in 1..65535) { "Port must be between 1 and 65535" }
        }

        private val endpoint by option(
            "--endpoint",
            help = "HTTP endpoint path for MCP traffic."
        ).default("/mcp")

        private val allowPublicBind by option(
            "--allow-public-bind",
            help = "Allow binding to a non-loopback host."
        ).flag(default = false)

        private val logLevel by option(
            "--log-level",
            help = "Human-readable stderr logging level."
        ).convert("quiet|info|debug") { parseLogLevel(it) }.default(HexanaLogLevel.INFO)

        private val analyticsKey by option(
            "--analytics-key",
            help = "PostHog project API key. Enables anonymous usage analytics when provided.",
            envvar = "HEXANA_POSTHOG_KEY"
        )

        private val analyticsEnabled by analyticsEnabledOption()

        private val toolProfile by toolProfileOption()

        override fun run() {
            if (!allowPublicBind && !host.isLoopbackHost()) {
                throw UsageError("Public HTTP bind requires --allow-public-bind")
            }

            sink(
                HexanaCommand.Http(
                    host = host,
                    port = port,
                    endpoint = endpoint.prependIfMissing("/"),
                    allowPublicBind = allowPublicBind,
                    logLevel = logLevel,
                    analyticsKey = analyticsKey,
                    analyticsEnabled = analyticsEnabled,
                    toolProfile = toolProfile
                )
            )
        }
    }

    private class VersionCommand(
        private val sink: (HexanaCommand) -> Unit
    ) : CliktCommand(name = "version") {
        override fun help(context: Context): String = "Print the standalone server version."

        override fun run() {
            sink(HexanaCommand.Version)
        }
    }

    private class HelpCommand(
        private val sink: (HexanaCommand) -> Unit
    ) : CliktCommand(name = "help") {
        override fun help(context: Context): String = "Print the root command help."

        override fun run() {
            sink(HexanaCommand.Help)
        }
    }
}

private fun parseLogLevel(value: String): HexanaLogLevel {
    return try {
        HexanaLogLevel.parseCliValue(value)
    } catch (_: IllegalArgumentException) {
        throw UsageError(
            "Unsupported log level: $value. Expected one of: ${HexanaLogLevel.entries.joinToString { it.cliValue() }}"
        )
    }
}

private fun CliktCommand.analyticsEnabledOption() = option(
    "--telemetry-enabled",
    help = "Whether anonymous usage analytics are allowed. Defaults to true.",
    envvar = "HEXANA_MCP_TELEMETRY_ENABLED"
).convert("true|false") { parseBooleanOption("--telemetry-enabled", it) }.default(defaultMcpAnalyticsEnabled())

internal fun defaultMcpAnalyticsEnabled(): Boolean {
    return true
}

internal fun isRunningInGraalVmNativeImage(
    imageCodeProperty: String? = System.getProperty(GRAALVM_NATIVE_IMAGE_CODE_PROPERTY)
): Boolean {
    return imageCodeProperty == GRAALVM_NATIVE_IMAGE_RUNTIME_VALUE
}

private fun CliktCommand.toolProfileOption() = option(
    "--tool-profile",
    help = "MCP tool inventory: production is compact, advanced exposes the full investigation surface.",
    envvar = "HEXANA_MCP_TOOL_PROFILE"
).convert("production|advanced") { parseToolProfile(it) }.default(HexanaMcpToolProfile.PRODUCTION)

private fun parseToolProfile(value: String): HexanaMcpToolProfile {
    return try {
        HexanaMcpToolProfile.parseCliValue(value)
    } catch (_: IllegalArgumentException) {
        throw UsageError("Unsupported MCP tool profile: $value. Expected production or advanced.")
    }
}

private fun parseBooleanOption(optionName: String, value: String): Boolean {
    return when (value.lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw UsageError("Unsupported $optionName value: $value. Expected true or false.")
    }
}

private fun String.isLoopbackHost(): Boolean {
    return runCatching { InetAddress.getByName(this).isLoopbackAddress }
        .getOrDefault(false)
}

private fun String.prependIfMissing(prefix: String): String {
    return if (startsWith(prefix)) this else prefix + this
}

private const val GRAALVM_NATIVE_IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode"
private const val GRAALVM_NATIVE_IMAGE_RUNTIME_VALUE = "runtime"
