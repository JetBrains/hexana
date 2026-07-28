package org.jetbrains.hexana.mcp.server

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.HexanaResourceLimits
import java.io.File
import java.net.InetAddress

sealed interface HexanaCommand {
    data class Stdio(
        val logLevel: HexanaLogLevel,
        val analyticsKey: String?,
        val analyticsEnabled: Boolean,
        val toolProfile: HexanaMcpToolProfile,
        val allowedRoots: List<String>,
        val resourceLimits: HexanaResourceLimits,
    ) : HexanaCommand

    data class Http(
        val host: String,
        val port: Int,
        val endpoint: String,
        val allowPublicBind: Boolean,
        val logLevel: HexanaLogLevel,
        val analyticsKey: String?,
        val analyticsEnabled: Boolean,
        val toolProfile: HexanaMcpToolProfile,
        val allowedRoots: List<String>,
        val resourceLimits: HexanaResourceLimits,
    ) : HexanaCommand

    data class Doctor(
        val allowedRoots: List<String>,
    ) : HexanaCommand

    data object Version : HexanaCommand
    data object Help : HexanaCommand
}

class HexanaCli(
    private val envReader: (String) -> String? = System::getenv,
) {
    private var parsedCommand: HexanaCommand? = null
    private val rootCommand = RootCommand(envReader) { parsedCommand = it }

    fun parse(args: Array<String>): HexanaCommand {
        parsedCommand = null
        rootCommand.parse(args.asList())
        return parsedCommand ?: HexanaCommand.Help
    }

    fun helpText(): String = rootCommand.getFormattedHelp(null) ?: ""

    internal fun helpText(args: Array<String>): String {
        return try {
            rootCommand.parse(args.asList())
            rootCommand.getFormattedHelp(null) ?: ""
        } catch (error: PrintHelpMessage) {
            rootCommand.getFormattedHelp(error) ?: ""
        }
    }

    fun echoFormattedHelp(error: CliktError) {
        rootCommand.echoFormattedHelp(error)
    }

    private class RootCommand(
        private val envReader: (String) -> String?,
        private val sink: (HexanaCommand) -> Unit
    ) : CliktCommand(name = "hexana-mcp") {
        override fun help(context: Context): String = "Standalone Hexana MCP server for WebAssembly inspection."

        override val invokeWithoutSubcommand: Boolean = true
        override val printHelpOnEmptyArgs: Boolean = false

        init {
            val manualCommands = manualServiceCommands(envReader, sink)
            subcommands(manualCommands)
        }

        override fun run() = Unit
    }

    private class StdioCommand(
        private val envReader: (String) -> String?,
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

        private val allowedRoots by allowedRootOption()
        private val maxFileBytes by maxFileBytesOption()
        private val maxInlineBytes by maxInlineBytesOption()
        private val maxTextFileBytes by maxTextFileBytesOption()
        private val maxOutputBytes by maxOutputBytesOption()
        private val maxCandidateCount by maxCandidateCountOption()
        private val maxScanRoots by maxScanRootsOption()
        private val maxScanDepth by maxScanDepthOption()

        override fun run() {
            sink(
                HexanaCommand.Stdio(
                    logLevel = logLevel,
                    analyticsKey = analyticsKey,
                    analyticsEnabled = analyticsEnabled,
                    toolProfile = toolProfile,
                    allowedRoots = allowedRoots + allowedRootsFromEnv(envReader),
                    resourceLimits = buildResourceLimits(
                        maxFileBytes = maxFileBytes,
                        maxInlineBytes = maxInlineBytes,
                        maxTextFileBytes = maxTextFileBytes,
                        maxOutputBytes = maxOutputBytes,
                        maxCandidateCount = maxCandidateCount,
                        maxScanRoots = maxScanRoots,
                        maxScanDepth = maxScanDepth,
                    )
                )
            )
        }
    }

    private class HttpCommand(
        private val envReader: (String) -> String?,
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

        private val allowedRoots by allowedRootOption()
        private val maxFileBytes by maxFileBytesOption()
        private val maxInlineBytes by maxInlineBytesOption()
        private val maxTextFileBytes by maxTextFileBytesOption()
        private val maxOutputBytes by maxOutputBytesOption()
        private val maxCandidateCount by maxCandidateCountOption()
        private val maxScanRoots by maxScanRootsOption()
        private val maxScanDepth by maxScanDepthOption()

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
                    toolProfile = toolProfile,
                    allowedRoots = allowedRoots + allowedRootsFromEnv(envReader),
                    resourceLimits = buildResourceLimits(
                        maxFileBytes = maxFileBytes,
                        maxInlineBytes = maxInlineBytes,
                        maxTextFileBytes = maxTextFileBytes,
                        maxOutputBytes = maxOutputBytes,
                        maxCandidateCount = maxCandidateCount,
                        maxScanRoots = maxScanRoots,
                        maxScanDepth = maxScanDepth,
                    )
                )
            )
        }
    }

    private class DoctorCommand(
        private val envReader: (String) -> String?,
        private val sink: (HexanaCommand) -> Unit
    ) : CliktCommand(name = "doctor") {
        override fun help(context: Context): String =
            "Run compact standalone server diagnostics. Exits 0 for OK or warning-only reports, 1 for failure reports."

        private val allowedRoots by allowedRootOption()

        override fun run() {
            sink(
                HexanaCommand.Doctor(
                    allowedRoots = allowedRoots + allowedRootsFromEnv(envReader)
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

    private companion object {
        fun manualServiceCommands(
            envReader: (String) -> String?,
            sink: (HexanaCommand) -> Unit,
        ): List<CliktCommand> = listOf(
            StdioCommand(envReader, sink),
            HttpCommand(envReader, sink),
            DoctorCommand(envReader, sink),
            VersionCommand(sink),
            HelpCommand(sink),
        )
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
    help = "MCP tool inventory: default is compact, advanced exposes investigation tools, scripting exposes dedicated scripting tools when available.",
    envvar = "HEXANA_MCP_TOOL_PROFILE"
).convert("default|advanced|scripting") { parseToolProfile(it) }.default(HexanaMcpToolProfile.DEFAULT)

internal fun CliktCommand.allowedRootOption() = option(
    "--allowed-root",
    help = "Directory whose files may be read by path-backed MCP inputs. Repeat to allow multiple roots; HEXANA_MCP_ALLOWED_ROOTS may also provide ${File.pathSeparator}-separated roots."
).multiple()

internal fun CliktCommand.maxFileBytesOption() = positiveIntOption(
    "--max-file-bytes",
    "Maximum input file size accepted by MCP tools.",
    "HEXANA_MCP_MAX_FILE_BYTES",
)

internal fun CliktCommand.maxInlineBytesOption() = positiveIntOption(
    "--max-inline-bytes",
    "Maximum inline payload size accepted in MCP tool arguments.",
    "HEXANA_MCP_MAX_INLINE_BYTES",
)

internal fun CliktCommand.maxTextFileBytesOption() = positiveIntOption(
    "--max-text-file-bytes",
    "Maximum text file size accepted by text-oriented MCP tool inputs.",
    "HEXANA_MCP_MAX_TEXT_FILE_BYTES",
)

internal fun CliktCommand.maxOutputBytesOption() = positiveIntOption(
    "--max-output-bytes",
    "Maximum encoded logical JSON result accepted for every MCP tool call.",
    "HEXANA_MCP_MAX_OUTPUT_BYTES",
)

internal fun CliktCommand.maxCandidateCountOption() = positiveIntOption(
    "--max-candidate-count",
    "Maximum number of candidate files or records kept during discovery.",
    "HEXANA_MCP_MAX_CANDIDATE_COUNT",
)

internal fun CliktCommand.maxScanRootsOption() = positiveIntOption(
    "--max-scan-roots",
    "Maximum number of scan root directories accepted for discovery tools.",
    "HEXANA_MCP_MAX_SCAN_ROOTS",
)

internal fun CliktCommand.maxScanDepthOption() = positiveIntOption(
    "--max-scan-depth",
    "Maximum directory depth traversed while scanning roots.",
    "HEXANA_MCP_MAX_SCAN_DEPTH",
)

private fun CliktCommand.positiveIntOption(name: String, description: String, envvar: String) =
    option(name, help = description, envvar = envvar).int().validate {
        require(it > 0) { "Value must be greater than 0" }
    }

internal fun buildResourceLimits(
    maxFileBytes: Int?,
    maxInlineBytes: Int?,
    maxTextFileBytes: Int?,
    maxOutputBytes: Int?,
    maxCandidateCount: Int?,
    maxScanRoots: Int?,
    maxScanDepth: Int?,
): HexanaResourceLimits {
    val fileBytes = maxFileBytes ?: HexanaAnalysisLimits.MAX_FILE_WASM_BYTES
    val inlineBytes = maxInlineBytes ?: HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES
    return HexanaResourceLimits(
        maxInlineWasmBytes = inlineBytes,
        maxFileWasmBytes = fileBytes,
        maxInlineBinaryBytes = inlineBytes,
        maxFileBinaryBytes = fileBytes,
        maxTextFileBytes = maxTextFileBytes ?: HexanaAnalysisLimits.MAX_TEXT_FILE_BYTES,
        maxOutputBytes = maxOutputBytes ?: HexanaAnalysisLimits.MAX_OUTPUT_BYTES,
        maxCandidateCount = maxCandidateCount ?: HexanaAnalysisLimits.MAX_CANDIDATE_COUNT,
        maxScanRoots = maxScanRoots ?: HexanaAnalysisLimits.MAX_SCAN_ROOTS,
        maxScanDepth = maxScanDepth ?: HexanaAnalysisLimits.MAX_SCAN_DEPTH,
    )
}

private fun parseToolProfile(value: String): HexanaMcpToolProfile {
    return try {
        HexanaMcpToolProfile.parseCliValue(value)
    } catch (_: IllegalArgumentException) {
        throw UsageError("Unsupported MCP tool profile: $value. Expected default, advanced, or scripting.")
    }
}

private fun parseBooleanOption(optionName: String, value: String): Boolean {
    return when (value.lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw UsageError("Unsupported $optionName value: $value. Expected true or false.")
    }
}

internal fun allowedRootsFromEnv(envReader: (String) -> String?): List<String> {
    return envReader("HEXANA_MCP_ALLOWED_ROOTS")
        ?.split(File.pathSeparatorChar, ',', '\n')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
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
