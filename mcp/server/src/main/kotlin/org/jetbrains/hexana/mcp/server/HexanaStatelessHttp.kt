package org.jetbrains.hexana.mcp.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.EventStore
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import org.slf4j.LoggerFactory

private val statelessHttpLogger = LoggerFactory.getLogger("org.jetbrains.hexana.mcp.server.HexanaStatelessHttp")

@Suppress("LongParameterList")
internal fun Application.hexanaStatelessMcpStreamableHttp(
    path: String = "/mcp",
    enableDnsRebindingProtection: Boolean = false,
    allowedHosts: List<String>? = null,
    allowedOrigins: List<String>? = null,
    eventStore: EventStore? = null,
    block: RoutingContext.() -> Server,
) {
    install(ContentNegotiation) {
        json(McpJson)
    }
    install(SSE)

    val configuration = StreamableHttpServerTransport.Configuration(
        enableDnsRebindingProtection = enableDnsRebindingProtection,
        allowedHosts = allowedHosts,
        allowedOrigins = allowedOrigins,
        eventStore = eventStore,
        enableJsonResponse = true,
    )

    routing {
        route(path) {
            post {
                handleStatelessMcpPost(configuration, block)
            }
            get {
                call.rejectMcpMethodNotAllowed()
            }
            delete {
                call.rejectMcpMethodNotAllowed()
            }
        }
    }
}

private suspend fun RoutingContext.handleStatelessMcpPost(
    configuration: StreamableHttpServerTransport.Configuration,
    block: RoutingContext.() -> Server,
) {
    val transport = StreamableHttpServerTransport(configuration).also {
        it.setSessionIdGenerator(null)
    }
    statelessHttpLogger.info("New stateless StreamableHttp connection established without sessionId")

    val server = block()
    server.onClose {
        statelessHttpLogger.info("Server connection closed without sessionId")
    }

    try {
        server.createSession(transport)
        transport.handleRequest(null, call)
        statelessHttpLogger.debug("Server connected to transport without sessionId")
    } finally {
        server.close()
    }
}

private suspend fun ApplicationCall.rejectMcpMethodNotAllowed() {
    response.status(HttpStatusCode.MethodNotAllowed)
    respond(
        JSONRPCError(
            id = null,
            error = RPCError(
                code = RPCError.ErrorCode.CONNECTION_CLOSED,
                message = "Method not allowed.",
            )
        )
    )
}
