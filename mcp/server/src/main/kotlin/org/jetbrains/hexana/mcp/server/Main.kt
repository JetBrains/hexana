package org.jetbrains.hexana.mcp.server

import com.github.ajalt.clikt.core.CliktError
import io.ktor.http.*
import io.ktor.server.application.*
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.jetbrains.hexana.analytics.*
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger by lazy { LoggerFactory.getLogger("org.jetbrains.hexana.mcp.server.Main") }

fun main(args: Array<String>) {
    KotlinLoggingConfiguration.logStartupMessage = false
    configureLogging(HexanaLogLevel.INFO)
    retainMcpSerializersForNativeImage()
    val cli = HexanaCli()
    val command = try {
        cli.parse(args)
    } catch (error: CliktError) {
        cli.echoFormattedHelp(error)
        exitProcess(error.statusCode)
    }
    val version = hexanaServerVersion()

    developmentVersionWarning(command, version)?.let(logger::warn)

    try {
        withCommandAnalytics(command) {
            when (command) {
                is HexanaCommand.Help -> println(cli.helpText())
                is HexanaCommand.Version -> println("hexana-mcp $version")
                is HexanaCommand.Doctor -> {
                    val report = HexanaDoctor.inspect(
                        DoctorEnvironment.current(
                            version = version,
                            allowedRoots = command.allowedRoots
                        )
                    )
                    println(HexanaDoctor.render(report))
                    if (report.exitCode != 0) {
                        exitProcess(report.exitCode)
                    }
                }
                is HexanaCommand.ExecutableTool -> printCommandOutput(generatedExecutableCliToolCommandOutput(command))
                is HexanaCommand.Tools -> println(renderCliToolsListing(command))
                is HexanaCommand.TodoTool -> {
                    System.err.println(renderTodoToolNotImplementedMessage(command))
                    exitProcess(1)
                }
                is HexanaCommand.Stdio -> runBlocking { runStdio(command) }
                is HexanaCommand.Http -> runBlocking { runHttp(command) }
            }
        }
    } catch (error: HexanaAnalysisException) {
        if (command is HexanaCommand.ExecutableTool && error.code != HexanaAnalysisErrorCode.INTERNAL_ERROR) {
            System.err.println(renderExecutableToolDomainError(command, error))
            exitProcess(1)
        }
        handleFatalCommandFailure(command, error)
    } catch (error: Throwable) {
        handleFatalCommandFailure(command, error)
    }
}

private fun handleFatalCommandFailure(command: HexanaCommand, error: Throwable): Nothing {
    if (error is VirtualMachineError || error is LinkageError) {
        logger.error("Fatal JVM error while running Hexana MCP server", error)
        throw error
    }

    val userMessage = when (command) {
        is HexanaCommand.Stdio -> "Hexana MCP server failed while running stdio transport. See server logs for details."
        is HexanaCommand.Http -> "Hexana MCP server failed while running HTTP transport. See server logs for details."
        is HexanaCommand.Doctor -> "Hexana MCP doctor failed. See server logs for details."
        is HexanaCommand.ExecutableTool ->
            "Hexana MCP ${command.spec.commandName} command failed. See server logs for details."
        is HexanaCommand.Tools ->
            "Hexana MCP tools command failed. See server logs for details."
        is HexanaCommand.TodoTool ->
            "Hexana MCP ${command.spec.commandName} command failed. See server logs for details."
        is HexanaCommand.Version,
        is HexanaCommand.Help -> "Hexana MCP server failed. See server logs for details."
    }
    logger.error(userMessage, error)
    System.err.println(userMessage)
    exitProcess(1)
}

private fun printCommandOutput(output: HexanaCliCommandOutput) {
    if (output.stderr.isNotEmpty()) {
        System.err.print(output.stderr)
    }
    if (output.stdout.isNotEmpty()) {
        System.out.print(output.stdout)
    }
    if (output.exitCode != 0) {
        exitProcess(output.exitCode)
    }
}

private fun renderExecutableToolDomainError(
    command: HexanaCommand.ExecutableTool,
    error: HexanaAnalysisException,
): String = "Hexana MCP ${command.spec.commandName} failed: ${error.message}"

internal data class CommandAnalytics(
    val analyticsKey: String?,
    val analyticsEnabled: Boolean,
    val transport: HexanaEvent.McpTransport,
    val analyticsHost: String? = null,
)

internal inline fun withCommandAnalytics(command: HexanaCommand, block: () -> Unit) {
    val analytics = command.analytics() ?: run {
        block()
        return
    }
    withMcpAnalytics(analytics, block)
}

internal inline fun withMcpAnalytics(analytics: CommandAnalytics, block: () -> Unit) {
    if (!analytics.analyticsEnabled) {
        block()
        return
    }

    initializeMcpAnalytics(analytics)
    try {
        block()
    } finally {
        HexanaAnalytics.shutdown()
    }
}

internal fun HexanaCommand.analytics(): CommandAnalytics? = when (this) {
    is HexanaCommand.Stdio -> CommandAnalytics(
        analyticsKey = analyticsKey,
        analyticsEnabled = analyticsEnabled,
        transport = HexanaEvent.McpTransport.STDIO,
    )

    is HexanaCommand.Http -> CommandAnalytics(
        analyticsKey = analyticsKey,
        analyticsEnabled = analyticsEnabled,
        transport = HexanaEvent.McpTransport.HTTP,
    )

    is HexanaCommand.Help,
    is HexanaCommand.Doctor,
    is HexanaCommand.ExecutableTool,
    is HexanaCommand.Tools,
    is HexanaCommand.TodoTool,
    is HexanaCommand.Version -> null
}

internal fun developmentVersionWarning(command: HexanaCommand, version: String): String? {
    if (version != "dev") {
        return null
    }
    return when (command) {
        is HexanaCommand.Help,
        is HexanaCommand.Doctor,
        is HexanaCommand.ExecutableTool,
        is HexanaCommand.Tools,
        is HexanaCommand.TodoTool,
        is HexanaCommand.Version -> null

        is HexanaCommand.Stdio,
        is HexanaCommand.Http -> {
            "Hexana MCP server is running with fallback version `dev`. " +
                "Set -Dhexana.version or package the server with an implementation version."
        }
    }
}

internal fun initializeMcpAnalytics(analytics: CommandAnalytics) {
    HexanaAnalytics.initialize(
        config = AnalyticsConfig(
            apiKey = analytics.analyticsKey ?: AnalyticsConfig.DEFAULT_API_KEY,
            host = analytics.analyticsHost ?: AnalyticsConfig.DEFAULT_HOST
        ),
        consentProvider = ConsentProvider { true },
        distinctIdProvider = UuidDistinctIdProvider()
    )

    HexanaAnalytics.client.capture(
        HexanaEvent.McpServerStarted(transport = analytics.transport, embeddedInIde = false)
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        HexanaAnalytics.shutdown()
    })
}

private suspend fun runStdio(command: HexanaCommand.Stdio) {
    configureLogging(command.logLevel)
    val server = createHexanaMcpServer(
        analysisService = createStandaloneHexanaAnalysisService(command.allowedRoots, command.resourceLimits),
        transport = HexanaEvent.McpTransport.STDIO,
        toolProfile = command.toolProfile,
        resourceLimits = command.resourceLimits,
        allowedRoots = command.allowedRoots,
    )
    val finished = CompletableDeferred<Unit>()
    val transport = HexanaStdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink()
    )

    transport.onClose {
        finished.complete(Unit)
    }
    transport.onError { error ->
        logger.error("STDIO transport failed", error)
        finished.complete(Unit)
    }

    logger.info("Starting stdio MCP transport with {} tool profile", command.toolProfile.cliValue())
    server.createSession(transport)
    try {
        finished.await()
    } finally {
        server.close()
    }
}

private suspend fun runHttp(command: HexanaCommand.Http) {
    configureLogging(command.logLevel)
    logger.info(
        "Starting HTTP MCP transport on http://{}:{}{} with {} tool profile",
        command.host,
        command.port,
        command.endpoint,
        command.toolProfile.cliValue()
    )
    val localOnlyBinding = !command.allowPublicBind
    embeddedServer(CIO, host = command.host, port = command.port) {
        if (localOnlyBinding) {
            install(localOriginValidationPlugin(command.endpoint, command.host, command.port))
        }
        routing {
            get("/healthz") {
                call.respondText("OK", ContentType.Text.Plain)
            }
        }
        mcpStatelessStreamableHttp(
            path = command.endpoint,
            enableDnsRebindingProtection = localOnlyBinding,
            allowedHosts = if (localOnlyBinding) localAllowedHosts(command.host, command.port) else null,
            allowedOrigins = null,
            eventStore = null
        ) {
            createHexanaMcpServer(
                analysisService = createStandaloneHexanaAnalysisService(command.allowedRoots, command.resourceLimits),
                transport = HexanaEvent.McpTransport.HTTP,
                toolProfile = command.toolProfile,
                resourceLimits = command.resourceLimits,
                allowedRoots = command.allowedRoots,
            )
        }
    }.start(wait = true)
}

private fun localOriginValidationPlugin(endpoint: String, boundHost: String, port: Int) = createApplicationPlugin(
    name = "LocalOriginValidationPlugin"
) {
    val allowedOrigins = localAllowedOrigins(boundHost, port).map { it.lowercase() }.toSet()

    onCall { call ->
        if (call.request.path() != endpoint) {
            return@onCall
        }

        val originHeader = call.request.headers[HttpHeaders.Origin] ?: return@onCall
        if (originHeader.lowercase() !in allowedOrigins) {
            call.respondText("Invalid Origin header: $originHeader", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            return@onCall
        }
    }
}

private fun localAllowedHosts(boundHost: String, port: Int): List<String> {
    val hosts = linkedSetOf(
        boundHost,
        "127.0.0.1",
        "localhost",
        "::1",
        "[::1]"
    )
    return hosts.flatMapTo(linkedSetOf()) { host ->
        val normalizedHost = host.toOriginHost()
        listOf(normalizedHost, "$normalizedHost:$port")
    }.toList()
}

private fun localAllowedOrigins(boundHost: String, port: Int): List<String> {
    val hosts = linkedSetOf(
        boundHost,
        "127.0.0.1",
        "localhost",
        "::1"
    )
    return hosts.map { host ->
        "http://${host.toOriginHost()}:$port"
    }
}

private fun String.toOriginHost(): String {
    return if (contains(':') && !startsWith('[')) {
        "[$this]"
    } else {
        this
    }
}
