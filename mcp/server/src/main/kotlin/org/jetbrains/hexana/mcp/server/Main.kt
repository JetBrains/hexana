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
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.jetbrains.hexana.analytics.*
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("org.jetbrains.hexana.mcp.server.Main")

fun main(args: Array<String>) {
    KotlinLoggingConfiguration.logStartupMessage = false
    configureLogging(HexanaLogLevel.INFO)
    val cli = HexanaCli()
    val command = try {
        cli.parse(args)
    } catch (error: CliktError) {
        cli.echoFormattedHelp(error)
        exitProcess(error.statusCode)
    }
    val version = hexanaServerVersion()

    developmentVersionWarning(command, version)?.let(logger::warn)

    initializeAnalytics(command)

    try {
        when (command) {
            is HexanaCommand.Help -> println(cli.helpText())
            is HexanaCommand.Version -> println("hexana-mcp $version")
            is HexanaCommand.Stdio -> runBlocking { runStdio(command) }
            is HexanaCommand.Http -> runBlocking { runHttp(command) }
        }
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is LinkageError) {
            logger.error("Fatal JVM error while running Hexana MCP server", error)
            throw error
        }

        val userMessage = when (command) {
            is HexanaCommand.Stdio -> "Hexana MCP server failed while running stdio transport. See server logs for details."
            is HexanaCommand.Http -> "Hexana MCP server failed while running HTTP transport. See server logs for details."
            is HexanaCommand.Version,
            is HexanaCommand.Help -> "Hexana MCP server failed. See server logs for details."
        }
        logger.error(userMessage, error)
        System.err.println(userMessage)
        exitProcess(1)
    }
}

internal fun developmentVersionWarning(command: HexanaCommand, version: String): String? {
    if (version != "dev") {
        return null
    }
    return when (command) {
        is HexanaCommand.Help,
        is HexanaCommand.Version -> null

        is HexanaCommand.Stdio,
        is HexanaCommand.Http -> {
            "Hexana MCP server is running with fallback version `dev`. " +
                "Set -Dhexana.version or package the server with an implementation version."
        }
    }
}

private fun initializeAnalytics(command: HexanaCommand) {
    val analyticsKey = when (command) {
        is HexanaCommand.Stdio -> command.analyticsKey
        is HexanaCommand.Http -> command.analyticsKey
        else -> null
    } ?: return

    HexanaAnalytics.initialize(
        config = AnalyticsConfig(apiKey = analyticsKey),
        consentProvider = ConsentProvider { true },
        distinctIdProvider = UuidDistinctIdProvider()
    )

    val transport = when (command) {
        is HexanaCommand.Stdio -> HexanaEvent.McpTransport.STDIO
        is HexanaCommand.Http -> HexanaEvent.McpTransport.HTTP
        else -> return
    }
    HexanaAnalytics.client.capture(
        HexanaEvent.McpServerStarted(transport = transport, embeddedInIde = false)
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        HexanaAnalytics.shutdown()
    })
}

private suspend fun runStdio(command: HexanaCommand.Stdio) {
    configureLogging(command.logLevel)
    val server = createHexanaMcpServer()
    val finished = CompletableDeferred<Unit>()
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )

    transport.onClose {
        finished.complete(Unit)
    }
    transport.onError { error ->
        logger.error("STDIO transport failed", error)
        finished.complete(Unit)
    }

    logger.info("Starting stdio MCP transport")
    server.createSession(transport)
    try {
        finished.await()
    } finally {
        server.close()
    }
}

private suspend fun runHttp(command: HexanaCommand.Http) {
    configureLogging(command.logLevel)
    logger.info("Starting HTTP MCP transport on http://{}:{}{}", command.host, command.port, command.endpoint)
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
            createHexanaMcpServer()
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
