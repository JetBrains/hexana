package org.jetbrains.hexana.mcp.server

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.hexana.cli.metadata.HexanaCliExecutionStatus
import org.jetbrains.hexana.cli.metadata.HexanaCliInputMode
import org.jetbrains.hexana.cli.metadata.HexanaCliOutputFormat
import org.jetbrains.hexana.cli.metadata.HexanaCliToolGroup
import org.jetbrains.hexana.cli.metadata.HexanaCliToolSpec
import org.jetbrains.hexana.cli.metadata.HexanaGeneratedCliToolRegistry
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.HexanaResourceLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    data class ExecutableTool(
        val spec: HexanaCliToolSpec,
        val sourceSelections: List<ToolInputSourceSelection>,
        val params: Map<String, JsonPrimitive> = emptyMap(),
        val format: HexanaCliOutputFormat,
        val allowedRoots: List<String>,
        val resourceLimits: HexanaResourceLimits,
    ) : HexanaCommand {
        constructor(
            spec: HexanaCliToolSpec,
            sourceSelection: ToolInputSourceSelection,
            format: HexanaCliOutputFormat,
            allowedRoots: List<String>,
            resourceLimits: HexanaResourceLimits,
            params: Map<String, JsonPrimitive> = emptyMap(),
        ) : this(
            spec = spec,
            sourceSelections = listOf(sourceSelection),
            params = params,
            format = format,
            allowedRoots = allowedRoots,
            resourceLimits = resourceLimits,
        )

        init {
            require(sourceSelections.isNotEmpty()) {
                "Executable CLI tool '${spec.commandName}' must carry at least one source selection."
            }
            val duplicateSourceNames = sourceSelections
                .map { it.slotName }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            require(duplicateSourceNames.isEmpty()) {
                "Executable CLI tool '${spec.commandName}' has duplicate source selections: $duplicateSourceNames."
            }
            val blankParamNames = params.keys.filter { it.isBlank() }
            require(blankParamNames.isEmpty()) {
                "Executable CLI tool '${spec.commandName}' has blank parameter names."
            }
        }

        val sourceSelection: ToolInputSourceSelection
            get() = sourceSelections.singleOrNull()
                ?: error(
                    "Executable CLI tool '${spec.commandName}' has ${sourceSelections.size} source selections; " +
                        "use named sourceSelections instead of the single-source accessor."
                )
    }

    data class Tools(
        val group: HexanaCliToolGroup?,
    ) : HexanaCommand

    data class TodoTool(
        val spec: HexanaCliToolSpec,
    ) : HexanaCommand

    data object Version : HexanaCommand
    data object Help : HexanaCommand
}

interface HexanaCliStdin {
    fun readExplicitBytes(maxBytes: Int): ByteArray
    fun readAutoBytesOrNull(maxBytes: Int): ByteArray?
}

object SystemHexanaCliStdin : HexanaCliStdin {
    private const val AUTO_STDIN_READ_TIMEOUT_MILLIS = 2_000L
    private const val BUFFER_SIZE = 8 * 1024

    override fun readExplicitBytes(maxBytes: Int): ByteArray =
        System.`in`.readAtMost(maxBytes)

    override fun readAutoBytesOrNull(maxBytes: Int): ByteArray? {
        if (System.console() != null) {
            return null
        }
        val input = System.`in`
        if (input.available() > 0) {
            return input.readAtMost(maxBytes).takeIf { it.isNotEmpty() }
        }

        return input.readAtMostAsync(maxBytes)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "Maximum stdin byte count must be positive." }
        val output = ByteArrayOutputStream()
        val limit = maxBytes.toLong() + 1L
        val bufferSize = if (limit < BUFFER_SIZE) limit.toInt() else BUFFER_SIZE
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (total < limit) {
            val bytesToRead = (limit - total).coerceAtMost(buffer.size.toLong()).toInt()
            val bytesRead = read(buffer, 0, bytesToRead)
            if (bytesRead < 0) {
                break
            }
            output.write(buffer, 0, bytesRead)
            total += bytesRead
        }
        return output.toByteArray()
    }

    private fun InputStream.readAtMostAsync(maxBytes: Int): ByteArray? {
        val latch = CountDownLatch(1)
        var result: Result<ByteArray>? = null
        val reader = Thread(
            {
                result = runCatching { readAtMost(maxBytes) }
                latch.countDown()
            },
            "hexana-cli-auto-stdin-reader"
        )
        reader.isDaemon = true
        reader.start()
        if (!latch.await(AUTO_STDIN_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            return null
        }
        return requireNotNull(result).getOrThrow()
    }
}

class HexanaCli(
    private val envReader: (String) -> String? = System::getenv,
    private val stdin: HexanaCliStdin = SystemHexanaCliStdin,
) {
    private var parsedCommand: HexanaCommand? = null
    private val rootCommand = RootCommand(envReader, stdin) { parsedCommand = it }

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
        private val stdin: HexanaCliStdin,
        private val sink: (HexanaCommand) -> Unit
    ) : CliktCommand(name = "hexana-mcp") {
        override fun help(context: Context): String = "Standalone Hexana MCP server for WebAssembly inspection."

        override val invokeWithoutSubcommand: Boolean = true
        override val printHelpOnEmptyArgs: Boolean = false

        init {
            val manualCommands = manualServiceCommands(envReader, sink)
            val generatedCommands = generatedCliToolCommands(envReader, stdin, sink)
            validateRootCliCommandNames(
                manualCommandNames = manualCommands.map { it.commandName },
                generatedCommandNames = generatedCommands.map { it.commandName },
            )
            subcommands(manualCommands + generatedCommands)
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

    private class ToolsCommand(
        private val sink: (HexanaCommand) -> Unit
    ) : CliktCommand(name = "tools") {
        override fun help(context: Context): String =
            "List generated CLI-visible tools. Groups are metadata, not MCP profiles."

        private val group by option(
            "--group",
            help = "Filter by generated CLI metadata group. Supported: ${supportedCliToolGroupNames()}."
        ).convert(supportedCliToolGroupNames()) { parseCliToolGroup(it) }

        override fun run() {
            sink(HexanaCommand.Tools(group = group))
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
            ToolsCommand(sink),
            DoctorCommand(envReader, sink),
            VersionCommand(sink),
            HelpCommand(sink),
        )
    }
}

internal fun validateRootCliCommandNames(
    manualCommandNames: List<String>,
    generatedCommandNames: List<String>,
) {
    val duplicateManualNames = duplicateRootCliCommandNames(manualCommandNames)
    require(duplicateManualNames.isEmpty()) {
        "Manual service CLI command names must be unique: ${duplicateManualNames.joinToString()}."
    }

    val manualCollisions = generatedCommandNames.toSet().intersect(manualCommandNames)
    require(manualCollisions.isEmpty()) {
        "Generated CLI command names must not duplicate manual service command names: ${manualCollisions.sorted().joinToString()}."
    }

    val duplicateGeneratedNames = duplicateRootCliCommandNames(generatedCommandNames)
    require(duplicateGeneratedNames.isEmpty()) {
        "Generated CLI command names must be unique: ${duplicateGeneratedNames.joinToString()}."
    }
}

private fun duplicateRootCliCommandNames(commandNames: List<String>): List<String> =
    commandNames
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()

internal fun renderCliToolsListing(command: HexanaCommand.Tools): String {
    val tools = HexanaGeneratedCliToolRegistry.TOOLS
        .filter { spec -> spec.executionStatus == HexanaCliExecutionStatus.EXECUTABLE }
        .filter { spec -> command.group == null || command.group in spec.groups }
        .sortedBy { it.commandName }

    return buildString {
        appendLine("Hexana MCP CLI tools")
        appendLine("Groups are generated CLI metadata, not MCP tool profiles.")
        tools.forEachIndexed { index, spec ->
            if (index > 0) {
                appendLine()
            }
            appendLine(spec.commandName)
            appendLine("  groups: ${spec.sortedGroupNames()}")
            appendLine("  status: ${spec.executionStatus.cliName()}")
            appendLine("  formats: ${spec.sortedFormatNames()}")
            appendLine("  default: ${spec.defaultOutputFormat.cliName()}")
            appendLine("  summary: ${spec.summary}")
        }
    }.trimEnd()
}

internal fun parseCliOutputFormat(spec: HexanaCliToolSpec, value: String): HexanaCliOutputFormat {
    val format = HexanaCliOutputFormat.entries.firstOrNull { it.cliName() == value.trim().lowercase() }
    if (format != null && format in spec.outputFormats) {
        return format
    }
    throw UsageError(
        "Unsupported ${spec.commandName} format: $value. Supported formats: ${spec.supportedFormatNames()}."
    )
}

internal fun HexanaCliToolSpec.supportedFormatNames(): String =
    outputFormats.joinToString { it.cliName() }

internal fun HexanaCliToolSpec.supportedFormatCompletionCandidates(): CompletionCandidates =
    CompletionCandidates.Fixed(outputFormats.map { it.cliName() }.toSet())

private fun HexanaCliToolSpec.sortedGroupNames(): String =
    groups.map { it.cliName() }.sorted().joinToString()

private fun HexanaCliToolSpec.sortedFormatNames(): String =
    outputFormats.map { it.cliName() }.sorted().joinToString()

internal fun HexanaCliOutputFormat.cliName(): String = name.lowercase()

private fun HexanaCliExecutionStatus.cliName(): String = name.lowercase()

private fun HexanaCliToolGroup.cliName(): String = name.lowercase().replace('_', '-')

private fun supportedCliToolGroupNames(): String =
    HexanaCliToolGroup.entries.map { it.cliName() }.sorted().joinToString()

private fun parseCliToolGroup(value: String): HexanaCliToolGroup {
    val normalized = value.trim().lowercase()
    return HexanaCliToolGroup.entries.firstOrNull { it.cliName() == normalized }
        ?: throw UsageError(
            "Unsupported CLI tool metadata group: $value. Supported groups: ${supportedCliToolGroupNames()}."
        )
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
    help = "MCP tool inventory: production is compact, advanced exposes investigation tools, scripting exposes dedicated scripting tools when available.",
    envvar = "HEXANA_MCP_TOOL_PROFILE"
).convert("production|advanced|scripting") { parseToolProfile(it) }.default(HexanaMcpToolProfile.PRODUCTION)

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
    "Maximum bounded query output budget accepted for one MCP tool call.",
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
        throw UsageError("Unsupported MCP tool profile: $value. Expected production, advanced, or scripting.")
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
