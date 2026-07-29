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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.jetbrains.hexana.analytics.*
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger by lazy { LoggerFactory.getLogger("org.jetbrains.hexana.mcp.server.Main") }

fun main(args: Array<String>) {
    KotlinLoggingConfiguration.logStartupMessage = false
    configureLogging(HexanaLogLevel.INFO)
    retainMcpSerializersForNativeImage()
    val cli = HexanaCli()
    val version = hexanaServerVersion()
    val command = try {
        cli.parse(args)
    } catch (error: CliktError) {
        if (error.statusCode != 0) {
            HexanaDiagnostics.start(
                HexanaDiagnosticContext(
                    version, "cli", null, null, HexanaLogLevel.INFO.cliValue(), null, 0, null
                )
            )
            emitDiagnosticFailure(HexanaFailureCategory.CONFIGURATION, "Invalid command-line configuration.", error)
        }
        cli.echoFormattedHelp(error)
        exitProcess(error.statusCode)
    }
    configureLogging(command.configuredLogLevel())
    HexanaDiagnostics.start(command.diagnosticContext(version))
    HexanaDiagnostics.status.takeUnless { it.available }?.let { status ->
        logger.warn(
            "Structured diagnostics are unavailable at {} ({}); errors will still use stderr",
            status.displayLocation,
            status.problem ?: "destination is not writable",
        )
    }

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
                is HexanaCommand.Stdio -> runBlocking { runStdio(command) }
                is HexanaCommand.Http -> runBlocking { runHttp(command) }
            }
        }
    } catch (error: Throwable) {
        handleFatalCommandFailure(command, error)
    }
}

private fun handleFatalCommandFailure(command: HexanaCommand, error: Throwable): Nothing {
    val category = when (command) {
        is HexanaCommand.Stdio -> HexanaFailureCategory.STARTUP
        is HexanaCommand.Http -> HexanaFailureCategory.HTTP_TRANSPORT
        is HexanaCommand.Doctor,
        is HexanaCommand.Version,
        is HexanaCommand.Help -> HexanaFailureCategory.CLI_INTERNAL
    }
    if (error is VirtualMachineError || error is LinkageError) {
        emitDiagnosticFailure(category, "Fatal runtime failure.", error)
        throw error
    }

    val userMessage = when (command) {
        is HexanaCommand.Stdio -> "Hexana MCP server failed while running stdio transport."
        is HexanaCommand.Http -> "Hexana MCP server failed while running HTTP transport."
        is HexanaCommand.Doctor -> "Hexana MCP doctor failed."
        is HexanaCommand.Version,
        is HexanaCommand.Help -> "Hexana MCP server failed."
    }
    emitDiagnosticFailure(category, userMessage, error)
    exitProcess(1)
}

private fun HexanaCommand.configuredLogLevel(): HexanaLogLevel = when (this) {
    is HexanaCommand.Stdio -> logLevel
    is HexanaCommand.Http -> logLevel
    is HexanaCommand.Doctor,
    is HexanaCommand.Version,
    is HexanaCommand.Help -> HexanaLogLevel.INFO
}

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
    is HexanaCommand.Version -> null
}

internal fun developmentVersionWarning(command: HexanaCommand, version: String): String? {
    if (version != "dev") {
        return null
    }
    return when (command) {
        is HexanaCommand.Help,
        is HexanaCommand.Doctor,
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
        distinctIdProvider = UuidDistinctIdProvider(),
        globalProperties = mapOf("surface" to "standalone_mcp"),
    )

    HexanaAnalytics.client.capture(
        HexanaEvent.McpServerStarted(transport = analytics.transport, embeddedInIde = false)
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        HexanaAnalytics.shutdown()
    })
}

private suspend fun runStdio(command: HexanaCommand.Stdio) {
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
        emitDiagnosticFailure(HexanaFailureCategory.STDIO_TRANSPORT, "STDIO transport failed.", error)
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
        hexanaStatelessMcpStreamableHttp(
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
