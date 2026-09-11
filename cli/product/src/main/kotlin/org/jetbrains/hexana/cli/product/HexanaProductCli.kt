package org.jetbrains.hexana.cli.product

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.hexana.analytics.AnalyticsConfig
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.analytics.UuidDistinctIdProvider
import org.jetbrains.hexana.cli.metadata.HexanaCliToolGroup
import org.jetbrains.hexana.cli.metadata.HexanaCliInputMode
import org.jetbrains.hexana.cli.metadata.HexanaCliOutputFormat
import org.jetbrains.hexana.cli.metadata.HexanaCliProductDescriptor
import org.jetbrains.hexana.cli.metadata.HexanaCliProductToolSelection
import org.jetbrains.hexana.cli.metadata.HexanaCliExecutionStatus
import org.jetbrains.hexana.cli.metadata.HexanaCliToolSpec
import org.jetbrains.hexana.cli.metadata.HexanaGeneratedCliProducts
import org.jetbrains.hexana.cli.metadata.HexanaGeneratedCliToolRegistry
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.runtime.CliTerminalWidthException
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.HexanaResourceLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.system.exitProcess

public fun runHexanaProductCli(
    product: HexanaCliProductDescriptor,
    args: Array<String>,
    executor: HexanaProductToolExecutor,
): Nothing {
    val cli = HexanaProductCli(product, executor)
    val result = cli.run(args, System.`in`, System.getenv())
    if (result.stdout.isNotEmpty()) {
        System.out.print(result.stdout)
    }
    if (result.stderr.isNotEmpty()) {
        System.err.print(result.stderr)
    }
    exitProcess(result.exitCode)
}

public class HexanaProductCli(
    private val product: HexanaCliProductDescriptor,
    private val executor: HexanaProductToolExecutor,
) {
    private val selectedTools = selectedProductTools(product, executor.bindings)

    public fun run(
        args: Array<String>,
        stdin: InputStream,
        env: Map<String, String>,
    ): ProductCliResult {
        val parsed = try {
            parse(args.toList(), stdin, env)
        } catch (error: ProductUsageException) {
            return ProductCliResult(stderr = "${error.message}\n", exitCode = USAGE_EXIT_CODE)
        }

        return withCliAnalytics(product, parsed.analytics, parsed.commandName) {
            try {
                execute(parsed)
            } catch (error: CliTerminalWidthException) {
                ProductCliResult(stderr = "${error.message}\n", exitCode = USAGE_EXIT_CODE)
            } catch (error: HexanaAnalysisException) {
                if (error.code != HexanaAnalysisErrorCode.INTERNAL_ERROR) {
                    ProductCliResult(
                        stderr = "${product.executableName} ${parsed.commandName} failed: ${error.message}\n",
                        exitCode = FAILURE_EXIT_CODE,
                    )
                } else {
                    internalFailure(parsed.commandName)
                }
            } catch (_: ProductUsageException) {
                ProductCliResult(stderr = "Invalid ${parsed.commandName} invocation.\n", exitCode = USAGE_EXIT_CODE)
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is LinkageError) {
                    throw error
                }
                internalFailure(parsed.commandName)
            }
        }
    }

    private fun parse(
        rawArgs: List<String>,
        stdin: InputStream,
        env: Map<String, String>,
    ): ParsedCommand {
        val args = rawArgs.toMutableList()
        val analytics = parseAnalyticsOptions(args, env)
        if (args.isEmpty()) {
            return ParsedCommand.Help(commandName = "help", topic = null, analytics = analytics)
        }

        val commandName = args.removeAt(0)
        if (isHelpFlag(commandName)) {
            requireNoArgs(commandName, args)
            return ParsedCommand.Help(commandName = "help", topic = null, analytics = analytics)
        }
        return when (commandName) {
            "help" -> {
                val topic = args.removeFirstOrNull()
                if (topic != null && isHelpFlag(topic)) {
                    requireNoArgs(topic, args)
                    return ParsedCommand.Help(commandName = "help", topic = "help", analytics = analytics)
                }
                if (args.isNotEmpty()) {
                    throw ProductUsageException("Usage: ${product.executableName} help [command]")
                }
                ParsedCommand.Help(commandName = "help", topic = topic, analytics = analytics)
            }
            "--version" -> {
                requireNoArgs(commandName, args)
                ParsedCommand.Version(analytics)
            }
            "version" -> {
                if (args.any(::isHelpFlag)) {
                    return ParsedCommand.Help(commandName = commandName, topic = commandName, analytics = analytics)
                }
                requireNoArgs(commandName, args)
                ParsedCommand.Version(analytics)
            }
            "tools" -> {
                if (args.any(::isHelpFlag)) {
                    return ParsedCommand.Help(commandName = commandName, topic = commandName, analytics = analytics)
                }
                requireNoArgs(commandName, args)
                ParsedCommand.Tools(analytics)
            }
            else -> {
                val spec = selectedTools[commandName]
                    ?: throw ProductUsageException("no such subcommand: $commandName")
                parseExecutableTool(spec, args, stdin, env, analytics)
            }
        }
    }

    private fun parseExecutableTool(
        spec: HexanaCliToolSpec,
        args: MutableList<String>,
        stdin: InputStream,
        env: Map<String, String>,
        analytics: CliAnalyticsSettings,
    ): ParsedCommand {
        if (args.any(::isHelpFlag)) {
            return ParsedCommand.Help(commandName = spec.commandName, topic = spec.commandName, analytics = analytics)
        }

        val options = CommandOptions.fromEnv(env)
        val params = mutableMapOf<String, JsonPrimitive>()
        val scriptSources = ScriptSourceOptions()
        var pathArgument: String? = null
        var stdinRequested = false
        var base64Source: String? = null
        var outputPath: Path? = null
        var format = spec.defaultOutputFormat

        while (args.isNotEmpty()) {
            val arg = args.removeAt(0)
            when (arg) {
                "--format" -> format = parseFormat(spec, args.requireValue(arg))
                "--stdin" -> {
                    requireSourceMode(spec, HexanaCliInputMode.STDIN, arg)
                    if (stdinRequested) {
                        throw ProductUsageException("Repeated ${spec.commandName} source option: $arg")
                    }
                    stdinRequested = true
                }
                "--binary-base64", "--bytes-base64", "--wasm-base64" -> {
                    requireBase64SourceOption(spec, arg)
                    if (base64Source != null) {
                        throw ProductUsageException("Repeated ${spec.commandName} base64 source option: $arg")
                    }
                    base64Source = args.requireValue(arg)
                }
                "--script" -> {
                    requireScriptOption(spec, arg)
                    scriptSources.addPath(args.requireValue(arg))
                }
                "--script-text" -> {
                    requireScriptOption(spec, arg)
                    scriptSources.addInline(args.requireValue(arg))
                }
                "--allowed-root" -> options.allowedRoots += args.requireValue(arg)
                "--max-file-bytes" -> options.maxFileBytes = args.requirePositiveInt(arg)
                "--max-inline-bytes" -> options.maxInlineBytes = args.requirePositiveInt(arg)
                "--max-text-file-bytes" -> options.maxTextFileBytes = args.requirePositiveInt(arg)
                "--max-output-bytes" -> options.maxOutputBytes = args.requirePositiveInt(arg)
                "--max-candidate-count" -> options.maxCandidateCount = args.requirePositiveInt(arg)
                "--max-scan-roots" -> options.maxScanRoots = args.requirePositiveInt(arg)
                "--max-scan-depth" -> options.maxScanDepth = args.requirePositiveInt(arg)
                "--terminal-width" -> {
                    requireTerminalWidthOption(spec, arg)
                    options.terminalWidth = args.requireValue(arg)
                    options.terminalWidthOptionName = arg
                }
                "--output" -> outputPath = Path.of(args.requireValue(arg))
                "--telemetry-enabled" -> analytics.enabled = parseBoolean("--telemetry-enabled", args.requireValue(arg))
                "--telemetry-key" -> analytics.key = args.requireValue(arg)
                "--telemetry-host" -> analytics.host = args.requireValue(arg)
                else -> {
                    if (arg.startsWith("--")) {
                        throw ProductUsageException("Unknown ${spec.commandName} option: $arg")
                    }
                    if (pathArgument != null) {
                        throw ProductUsageException("Unexpected ${spec.commandName} argument: $arg")
                    }
                    pathArgument = arg
                }
            }
        }

        scriptSources.validate(spec)
        params += scriptSources.params()

        val resourceLimits = options.toResourceLimits()
        val sourceSelection = selectSource(
            spec = spec,
            pathArgument = pathArgument,
            stdinRequested = stdinRequested,
            base64Source = base64Source,
            stdin = stdin,
            resourceLimits = resourceLimits,
        )

        return ParsedCommand.Executable(
            spec = spec,
            sourceSelection = sourceSelection,
            params = params,
            format = format,
            outputPath = outputPath,
            resourceLimits = resourceLimits,
            allowedRoots = options.effectiveAllowedRoots,
            terminalWidth = options.terminalWidthFor(format),
            analytics = analytics,
        )
    }

    private fun execute(command: ParsedCommand): ProductCliResult = when (command) {
        is ParsedCommand.Help -> ProductCliResult(stdout = helpText(command.topic))
        is ParsedCommand.Version -> ProductCliResult(stdout = "${product.executableName} ${hexanaProductVersion()}\n")
        is ParsedCommand.Tools -> ProductCliResult(stdout = toolsText())
        is ParsedCommand.Executable -> executeTool(command)
    }

    private fun executeTool(command: ParsedCommand.Executable): ProductCliResult {
        val output = executor.execute(command.toExecutionRequest())
            .normalize(command.format)
            .requireWithinOutputLimit(command)
        command.outputPath?.let { path ->
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.write(path, output.stdout.toByteArray(StandardCharsets.UTF_8))
            return ProductCliResult(stderr = output.stderr, exitCode = output.exitCode)
        }
        return output
    }

    private fun helpText(topic: String?): String {
        if (topic == null) {
            return buildString {
                appendLine("${product.executableName} <command> [options]")
                appendLine()
                appendLine(product.description)
                appendLine()
                appendLine("Commands:")
                selectedTools.values.sortedBy { it.commandName }.forEach { spec ->
                    appendLine("  ${spec.commandName}  ${spec.summary}")
                }
                appendLine("  tools  List commands available in this product.")
                appendLine("  version  Print the product version.")
                appendLine("  help  Print help for the product or one command.")
                appendLine()
                appendLine("Global options:")
                appendLine("  -h, --help")
                appendLine("  --version")
                appendLine("  --telemetry-enabled <true|false>")
                appendLine("  --telemetry-key <key>")
                appendLine("  --telemetry-host <url>")
            }
        }
        return when (topic) {
            "tools" -> "Usage: ${product.executableName} tools\n\nList commands available in this product.\n"
            "version" -> "Usage: ${product.executableName} version\n\nPrint the product version.\n"
            "help" -> "Usage: ${product.executableName} help [command]\n\nPrint help for the product or one command.\n"
            else -> {
                val spec = selectedTools[topic] ?: throw ProductUsageException("no such subcommand: $topic")
                buildString {
                    appendLine("Usage: ${product.executableName} ${spec.commandName} [source] [options]")
                    appendLine()
                    appendLine(spec.summary)
                    appendLine()
                    appendLine("Options:")
                    appendLine("  -h, --help")
                    appendLine("  --format <${spec.outputFormats.joinToString("|") { it.cliName() }}>")
                    appendLine("  --stdin")
                    if (HexanaCliInputMode.BINARY_PATH in spec.inputModes) {
                        appendLine("  --binary-base64 <base64>")
                        appendLine("  --bytes-base64 <base64>")
                    }
                    if (HexanaCliInputMode.WASM_PATH in spec.inputModes) {
                        appendLine("  --wasm-base64 <base64>")
                    }
                    appendLine("  --allowed-root <path>")
                    appendLine("  --max-file-bytes <n>")
                    appendLine("  --max-inline-bytes <n>")
                    appendLine("  --max-text-file-bytes <n>")
                    appendLine("  --max-output-bytes <n>")
                    appendLine("  --max-candidate-count <n>")
                    appendLine("  --max-scan-roots <n>")
                    appendLine("  --max-scan-depth <n>")
                    if (ToolOutputFormat.TABLE in spec.outputFormats) {
                        appendLine("  --terminal-width <positive-int>")
                    }
                    appendLine("  --output <path>")
                    appendLine("  --telemetry-enabled <true|false>")
                    appendLine("  --telemetry-key <key>")
                    appendLine("  --telemetry-host <url>")
                    if (spec.requiresScriptSource) {
                        appendLine("  --script <path>")
                        appendLine("  --script-text <text>")
                    }
                    appendLine()
                    appendLine("Environment:")
                    appendLine("  HEXANA_CLI_ALLOWED_ROOTS")
                    appendLine("  HEXANA_CLI_MAX_FILE_BYTES")
                    appendLine("  HEXANA_CLI_MAX_INLINE_BYTES")
                    appendLine("  HEXANA_CLI_MAX_TEXT_FILE_BYTES")
                    appendLine("  HEXANA_CLI_MAX_OUTPUT_BYTES")
                    appendLine("  HEXANA_CLI_MAX_CANDIDATE_COUNT")
                    appendLine("  HEXANA_CLI_MAX_SCAN_ROOTS")
                    appendLine("  HEXANA_CLI_MAX_SCAN_DEPTH")
                    if (ToolOutputFormat.TABLE in spec.outputFormats) {
                        appendLine("  HEXANA_CLI_TERMINAL_WIDTH")
                    }
                    appendLine("  HEXANA_CLI_TELEMETRY_ENABLED")
                    appendLine("  HEXANA_CLI_POSTHOG_KEY")
                    appendLine("  HEXANA_CLI_POSTHOG_HOST")
                }
            }
        }
    }

    private fun toolsText(): String = buildString {
        appendLine("${product.executableName} tools")
        selectedTools.values.sortedBy { it.commandName }.forEach { spec ->
            appendLine(spec.commandName)
            appendLine("  tool: ${spec.toolName}")
            appendLine("  formats: ${spec.outputFormats.map { it.cliName() }.sorted().joinToString()}")
            appendLine("  default: ${spec.defaultOutputFormat.cliName()}")
            appendLine("  summary: ${spec.summary}")
        }
    }

    private fun internalFailure(commandName: String): ProductCliResult =
        ProductCliResult(
            stderr = "${product.executableName} $commandName failed. See logs for details.\n",
            exitCode = FAILURE_EXIT_CODE,
        )
}

public data class ProductCliResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = SUCCESS_EXIT_CODE,
)

public interface HexanaProductToolExecutor {
    public val bindings: List<HexanaProductToolExecutorBinding>

    public fun execute(request: HexanaProductToolExecutionRequest): ProductCliResult =
        bindings.single { it.toolName == request.spec.toolName }.execute(request)
}

public data class HexanaProductToolExecutorBinding(
    val toolName: String,
    val supportedOutputFormats: Set<HexanaCliOutputFormat>,
    val execute: (HexanaProductToolExecutionRequest) -> ProductCliResult,
)

public data class HexanaProductToolExecutionRequest(
    val spec: HexanaCliToolSpec,
    val sourceSelection: ToolInputSourceSelection,
    val params: Map<String, JsonPrimitive>,
    val format: HexanaCliOutputFormat,
    val resourceLimits: HexanaResourceLimits,
    val allowedRoots: List<String>,
    val terminalWidth: Int? = null,
)

private sealed class ParsedCommand(
    open val commandName: String,
    open val analytics: CliAnalyticsSettings,
) {
    data class Help(
        override val commandName: String,
        val topic: String?,
        override val analytics: CliAnalyticsSettings,
    ) : ParsedCommand(commandName, analytics)

    data class Version(
        override val analytics: CliAnalyticsSettings,
    ) : ParsedCommand("version", analytics)

    data class Tools(
        override val analytics: CliAnalyticsSettings,
    ) : ParsedCommand("tools", analytics)

    data class Executable(
        val spec: HexanaCliToolSpec,
        val sourceSelection: ToolInputSourceSelection,
        val params: Map<String, JsonPrimitive>,
        val format: HexanaCliOutputFormat,
        val outputPath: Path?,
        val resourceLimits: HexanaResourceLimits,
        val allowedRoots: List<String>,
        val terminalWidth: Int?,
        override val analytics: CliAnalyticsSettings,
    ) : ParsedCommand(spec.commandName, analytics)
}

private fun ParsedCommand.Executable.toExecutionRequest(): HexanaProductToolExecutionRequest =
    HexanaProductToolExecutionRequest(
        spec = spec,
        sourceSelection = sourceSelection,
        params = params,
        format = format,
        resourceLimits = resourceLimits,
        allowedRoots = allowedRoots,
        terminalWidth = terminalWidth,
    )

private data class CliAnalyticsSettings(
    var enabled: Boolean,
    var key: String?,
    var host: String?,
)

private class CommandOptions private constructor(
    private val envAllowedRoots: List<String>,
    val allowedRoots: MutableList<String>,
    var maxFileBytes: Int?,
    var maxInlineBytes: Int?,
    var maxTextFileBytes: Int?,
    var maxOutputBytes: Int?,
    var maxCandidateCount: Int?,
    var maxScanRoots: Int?,
    var maxScanDepth: Int?,
    var terminalWidth: String?,
    var terminalWidthOptionName: String?,
    private val envTerminalWidth: String?,
) {
    val effectiveAllowedRoots: List<String>
        get() = (envAllowedRoots + allowedRoots)
            .filter { it.isNotBlank() }
            .distinct()

    fun toResourceLimits(): HexanaResourceLimits {
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

    fun terminalWidthFor(format: HexanaCliOutputFormat): Int? =
        if (format != ToolOutputFormat.TABLE) null else (terminalWidth ?: envTerminalWidth)?.let { value ->
            value.trim().toIntOrNull()?.takeIf { it > 0 }
                ?: throw ProductUsageException("${terminalWidthOptionName ?: "HEXANA_CLI_TERMINAL_WIDTH"} must be a positive integer")
        }

    companion object {
        fun fromEnv(env: Map<String, String>): CommandOptions = CommandOptions(
            envAllowedRoots = parseAllowedRoots(env["HEXANA_CLI_ALLOWED_ROOTS"]),
            allowedRoots = mutableListOf(),
            maxFileBytes = env.positiveIntEnv("HEXANA_CLI_MAX_FILE_BYTES"),
            maxInlineBytes = env.positiveIntEnv("HEXANA_CLI_MAX_INLINE_BYTES"),
            maxTextFileBytes = env.positiveIntEnv("HEXANA_CLI_MAX_TEXT_FILE_BYTES"),
            maxOutputBytes = env.positiveIntEnv("HEXANA_CLI_MAX_OUTPUT_BYTES"),
            maxCandidateCount = env.positiveIntEnv("HEXANA_CLI_MAX_CANDIDATE_COUNT"),
            maxScanRoots = env.positiveIntEnv("HEXANA_CLI_MAX_SCAN_ROOTS"),
            maxScanDepth = env.positiveIntEnv("HEXANA_CLI_MAX_SCAN_DEPTH"),
            terminalWidth = null,
            terminalWidthOptionName = null,
            envTerminalWidth = env["HEXANA_CLI_TERMINAL_WIDTH"],
        )
    }
}

private class ProductUsageException(message: String) : RuntimeException(message)

private class ScriptSourceOptions {
    private var scriptPath: String? = null
    private var script: String? = null
    private var occurrences = 0

    fun addPath(value: String) {
        scriptPath = value
        occurrences++
    }

    fun addInline(value: String) {
        script = value
        occurrences++
    }

    fun validate(spec: HexanaCliToolSpec) {
        if (!spec.requiresScriptSource) {
            return
        }
        when (occurrences) {
            0 -> throw ProductUsageException("Exactly one of --script or --script-text is required.")
            1 -> Unit
            else -> throw ProductUsageException("Provide exactly one of --script or --script-text.")
        }
    }

    fun params(): Map<String, JsonPrimitive> = buildMap {
        scriptPath?.let { put("scriptPath", JsonPrimitive(it)) }
        script?.let { put("script", JsonPrimitive(it)) }
    }
}

internal fun selectedProductTools(
    product: HexanaCliProductDescriptor,
    executorBindings: List<HexanaProductToolExecutorBinding>,
    selections: List<HexanaCliProductToolSelection> = HexanaGeneratedCliProducts.SELECTIONS[product.productId].orEmpty(),
    registry: List<HexanaCliToolSpec> = HexanaGeneratedCliToolRegistry.TOOLS,
): Map<String, HexanaCliToolSpec> {
    require(HexanaGeneratedCliProducts.PRODUCTS[product.productId] == product) {
        "Unknown generated CLI product '${product.productId}'."
    }
    val duplicateSelections = selections.groupBy { it.toolName }.filterValues { it.size > 1 }.keys
    require(duplicateSelections.isEmpty()) {
        "Generated ${product.productId} selection contains duplicate tools: ${duplicateSelections.sorted()}."
    }
    val duplicateCommands = selections.groupBy { it.commandName }.filterValues { it.size > 1 }.keys
    require(duplicateCommands.isEmpty()) {
        "Generated ${product.productId} selection contains duplicate commands: ${duplicateCommands.sorted()}."
    }

    val specs = selections.associate { selection ->
        val matches = registry.filter { it.toolName == selection.toolName }
        require(matches.size == 1) {
            "Generated ${product.productId} selection '${selection.toolName}' must resolve exactly one metadata contract; " +
                "found ${matches.size}."
        }
        val spec = matches.single()
        require(spec.commandName == selection.commandName) {
            "Generated ${product.productId} selection command '${selection.commandName}' does not match metadata " +
                "command '${spec.commandName}' for '${selection.toolName}'."
        }
        require(spec.executionStatus == HexanaCliExecutionStatus.EXECUTABLE) {
            "Generated ${product.productId} selection '${selection.toolName}' is ${spec.executionStatus}."
        }
        selection.commandName to spec
    }

    val bindingCounts = executorBindings.groupingBy { it.toolName }.eachCount()
    selections.forEach { selection ->
        require(bindingCounts[selection.toolName] == 1) {
            "Selected ${product.productId} tool '${selection.toolName}' must have exactly one executor binding; " +
                "found ${bindingCounts[selection.toolName] ?: 0}."
        }
        val binding = executorBindings.single { it.toolName == selection.toolName }
        val spec = specs.getValue(selection.commandName)
        require(binding.supportedOutputFormats == spec.outputFormats) {
            "Selected ${product.productId} tool '${selection.toolName}' output projections ${binding.supportedOutputFormats} " +
                "do not match generated formats ${spec.outputFormats}."
        }
    }
    val crossProductBindings = bindingCounts.keys - selections.mapTo(mutableSetOf()) { it.toolName }
    require(crossProductBindings.isEmpty()) {
        "${product.productId} executor contains bindings outside its generated selection: ${crossProductBindings.sorted()}."
    }
    return specs
}

private fun selectSource(
    spec: HexanaCliToolSpec,
    pathArgument: String?,
    stdinRequested: Boolean,
    base64Source: String?,
    stdin: InputStream,
    resourceLimits: HexanaResourceLimits,
): ToolInputSourceSelection {
    val sourceCount = listOf(pathArgument, base64Source).count { it != null } + if (stdinRequested) 1 else 0
    if (sourceCount > 1) {
        throw ProductUsageException("Provide exactly one ${spec.commandName} source.")
    }
    val slotName = if (HexanaCliInputMode.WASM_PATH in spec.inputModes) {
        "wasm"
    } else {
        "binary"
    }
    pathArgument?.let {
        if (it.isBlank()) {
            throw ProductUsageException("Blank source value is not allowed.")
        }
    }
    base64Source?.let {
        if (it.isBlank()) {
            throw ProductUsageException("Blank source value is not allowed.")
        }
    }
    if (pathArgument == "-") {
        return ToolInputSourceSelection.StdinBytes(readStdinBytes(stdin, maxInlineBytes(slotName, resourceLimits)), slotName)
    }
    pathArgument?.let { return ToolInputSourceSelection.Path(it, slotName) }
    base64Source?.let { return ToolInputSourceSelection.Base64(it, slotName) }
    if (stdinRequested) {
        return ToolInputSourceSelection.StdinBytes(readStdinBytes(stdin, maxInlineBytes(slotName, resourceLimits)), slotName)
    }
    val autoBytes = readAutoStdinBytes(stdin, maxInlineBytes(slotName, resourceLimits))
    if (autoBytes != null) {
        return ToolInputSourceSelection.StdinBytes(autoBytes, slotName)
    }
    throw ProductUsageException("Missing ${spec.commandName} source. Provide a path, --stdin, or a base64 source option.")
}

private fun maxInlineBytes(slotName: String, resourceLimits: HexanaResourceLimits): Int =
    if (slotName == "wasm") resourceLimits.maxInlineWasmBytes else resourceLimits.maxInlineBinaryBytes

private fun readStdinBytes(stdin: InputStream, maxBytes: Int): ByteArray {
    val bytes = stdin.readAtMost(maxBytes)
    if (bytes.size > maxBytes) {
        throw ProductUsageException("Inline bytes exceed maximum size of $maxBytes bytes.")
    }
    return bytes
}

private fun readAutoStdinBytes(stdin: InputStream, maxBytes: Int): ByteArray? {
    if (System.console() != null) {
        return null
    }
    return readStdinBytes(stdin, maxBytes).takeIf { it.isNotEmpty() }
}

private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val limit = maxBytes.toLong() + 1L
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (total < limit) {
        val bytesToRead = (limit - total).coerceAtMost(buffer.size.toLong()).toInt()
        val read = read(buffer, 0, bytesToRead)
        if (read < 0) break
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

private fun parseAnalyticsOptions(args: MutableList<String>, env: Map<String, String>): CliAnalyticsSettings {
    val settings = CliAnalyticsSettings(
        enabled = env["HEXANA_CLI_TELEMETRY_ENABLED"]?.let { parseBoolean("HEXANA_CLI_TELEMETRY_ENABLED", it) } ?: true,
        key = env["HEXANA_CLI_POSTHOG_KEY"]?.takeIf { it.isNotBlank() },
        host = env["HEXANA_CLI_POSTHOG_HOST"]?.takeIf { it.isNotBlank() },
    )
    while (args.isNotEmpty()) {
        when (args.first()) {
            "--telemetry-enabled" -> {
                args.removeAt(0)
                settings.enabled = parseBoolean("--telemetry-enabled", args.requireValue("--telemetry-enabled"))
            }
            "--telemetry-key" -> {
                args.removeAt(0)
                settings.key = args.requireValue("--telemetry-key")
            }
            "--telemetry-host" -> {
                args.removeAt(0)
                settings.host = args.requireValue("--telemetry-host")
            }
            else -> return settings
        }
    }
    return settings
}

private inline fun withCliAnalytics(
    product: HexanaCliProductDescriptor,
    settings: CliAnalyticsSettings,
    commandName: String,
    block: () -> ProductCliResult,
): ProductCliResult {
    if (!settings.enabled) {
        return block()
    }
    val analyticsThread = sendCliAnalyticsAsync(product, settings, commandName)
    val result = block()
    awaitCliAnalytics(analyticsThread)
    return result
}

private fun sendCliAnalyticsAsync(
    product: HexanaCliProductDescriptor,
    settings: CliAnalyticsSettings,
    commandName: String,
): Thread = thread(
    start = true,
    isDaemon = true,
    name = "hexana-cli-analytics",
) {
    sendCliAnalytics(product, settings, commandName)
}

private fun awaitCliAnalytics(thread: Thread) {
    joinCliAnalytics(thread, CLI_ANALYTICS_FLUSH_MILLIS)
    if (!thread.isAlive) {
        return
    }
    try {
        Runtime.getRuntime().addShutdownHook(Thread({
            joinCliAnalytics(thread, CLI_ANALYTICS_SHUTDOWN_FLUSH_MILLIS)
        }, "hexana-cli-analytics-shutdown"))
    } catch (_: IllegalStateException) {
        joinCliAnalytics(thread, CLI_ANALYTICS_SHUTDOWN_FLUSH_MILLIS)
    }
}

private fun joinCliAnalytics(thread: Thread, timeoutMillis: Long) {
    try {
        thread.join(timeoutMillis)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

private fun sendCliAnalytics(
    product: HexanaCliProductDescriptor,
    settings: CliAnalyticsSettings,
    commandName: String,
) {
    runCatching {
        val event = HexanaEvent.CliCommandInvoked(
            productId = product.productId,
            commandName = commandName,
        )
        val body = buildJsonObject {
            put("api_key", JsonPrimitive(settings.key ?: AnalyticsConfig.DEFAULT_API_KEY))
            put("event", JsonPrimitive(event.name))
            put("distinct_id", JsonPrimitive(UuidDistinctIdProvider().distinctId()))
            put("properties", buildJsonObject {
                event.properties.forEach { (key, value) ->
                    put(key, JsonPrimitive(value.toString()))
                }
            })
        }
        val host = settings.host ?: AnalyticsConfig.DEFAULT_HOST
        val connection = URI("${host.trimEnd('/')}/capture/").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { output ->
                output.write(analyticsJson.encodeToString(JsonObject.serializer(), body).toByteArray(StandardCharsets.UTF_8))
            }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}

private fun ProductCliResult.normalize(format: ToolOutputFormat): ProductCliResult =
    if (format == ToolOutputFormat.RAW) {
        this
    } else {
        copy(stdout = stdout.trimEnd('\r', '\n') + "\n")
    }

private fun ProductCliResult.requireWithinOutputLimit(command: ParsedCommand.Executable): ProductCliResult {
    val byteSize = stdout.toByteArray(StandardCharsets.UTF_8).size + stderr.toByteArray(StandardCharsets.UTF_8).size
    if (byteSize > command.resourceLimits.maxOutputBytes) {
        throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
            "${command.spec.commandName} output is $byteSize bytes, exceeding maxOutputBytes=${command.resourceLimits.maxOutputBytes}."
        )
    }
    return this
}

private fun parseFormat(spec: HexanaCliToolSpec, value: String): HexanaCliOutputFormat {
    val format = ToolOutputFormat.entries.firstOrNull { it.cliName() == value.trim().lowercase() }
    if (format != null && format in spec.outputFormats) {
        return format
    }
    throw ProductUsageException(
        "Unsupported ${spec.commandName} format: $value. Supported formats: " +
            spec.outputFormats.joinToString { it.cliName() } + "."
    )
}

private fun HexanaCliOutputFormat.cliName(): String = name.lowercase()

private fun requireSourceMode(spec: HexanaCliToolSpec, mode: HexanaCliInputMode, optionName: String) {
    if (mode !in spec.inputModes) {
        throw ProductUsageException("Unsupported ${spec.commandName} option: $optionName")
    }
}

private fun requireBase64SourceOption(spec: HexanaCliToolSpec, optionName: String) {
    when (optionName) {
        "--binary-base64" -> requireSourceMode(spec, HexanaCliInputMode.BINARY_PATH, optionName)
        "--bytes-base64" -> {
            if (HexanaCliInputMode.BINARY_PATH !in spec.inputModes && HexanaCliInputMode.WASM_PATH !in spec.inputModes) {
                throw ProductUsageException("Unsupported ${spec.commandName} option: $optionName")
            }
        }
        "--wasm-base64" -> requireSourceMode(spec, HexanaCliInputMode.WASM_PATH, optionName)
        else -> error("Unsupported base64 source option: $optionName")
    }
}

private fun requireScriptOption(spec: HexanaCliToolSpec, optionName: String) {
    if (!spec.requiresScriptSource) {
        throw ProductUsageException("Unsupported ${spec.commandName} option: $optionName")
    }
}

private fun requireTerminalWidthOption(spec: HexanaCliToolSpec, optionName: String) {
    if (ToolOutputFormat.TABLE !in spec.outputFormats) {
        throw ProductUsageException("Unsupported ${spec.commandName} option: $optionName")
    }
}

private val HexanaCliToolSpec.requiresScriptSource: Boolean
    get() = HexanaCliToolGroup.SCRIPTING in groups

private fun MutableList<String>.requireValue(optionName: String): String =
    removeFirstOrNull() ?: throw ProductUsageException("Missing value for $optionName")

private fun MutableList<String>.requirePositiveInt(optionName: String): Int =
    requireValue(optionName).toIntOrNull()?.takeIf { it > 0 }
        ?: throw ProductUsageException("$optionName must be a positive integer")

private fun requireNoArgs(commandName: String, args: List<String>) {
    if (args.isNotEmpty()) {
        throw ProductUsageException("Unexpected $commandName argument: ${args.first()}")
    }
}

private fun isHelpFlag(value: String): Boolean =
    value == "-h" || value == "--help"

private fun parseBoolean(name: String, value: String): Boolean = when (value.trim().lowercase()) {
    "true" -> true
    "false" -> false
    else -> throw ProductUsageException("Unsupported $name value: $value. Expected true or false.")
}

private fun parseAllowedRoots(value: String?): List<String> =
    value
        ?.split(File.pathSeparatorChar, ',', '\n')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

private fun Map<String, String>.positiveIntEnv(name: String): Int? {
    val rawValue = this[name] ?: return null
    return rawValue.trim().toIntOrNull()?.takeIf { it > 0 }
        ?: throw ProductUsageException("$name must be a positive integer")
}

private fun hexanaProductVersion(): String =
    System.getProperty("hexana.version")
        ?: object {}.javaClass.`package`.implementationVersion
        ?: "dev"

private val analyticsJson = Json
private const val CLI_ANALYTICS_FLUSH_MILLIS = 100L
private const val CLI_ANALYTICS_SHUTDOWN_FLUSH_MILLIS = 1_000L

public const val SUCCESS_EXIT_CODE: Int = 0
public const val FAILURE_EXIT_CODE: Int = 1
public const val USAGE_EXIT_CODE: Int = 2
