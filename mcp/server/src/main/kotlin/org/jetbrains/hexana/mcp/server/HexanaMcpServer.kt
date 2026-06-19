package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaAnalysisServices

fun createHexanaMcpServer(
    analysisService: HexanaAnalysisService = HexanaAnalysisServices.default,
    transport: HexanaEvent.McpTransport = HexanaEvent.McpTransport.STDIO,
    toolProfile: HexanaMcpToolProfile = HexanaMcpToolProfile.PRODUCTION
): Server {
    val toolHandlers = HexanaToolHandlers(
        analysisService = analysisService,
        transport = transport
    )

    return Server(
        serverInfo = Implementation("hexana", hexanaServerVersion()),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    ) {
        registerHexanaTools(toolHandlers, toolProfile)
    }
}

internal fun hexanaServerVersion(): String {
    return System.getProperty("hexana.version")
        ?: object {}.javaClass.`package`.implementationVersion
        ?: HexanaBuildInfo.VERSION.takeIf { it.isNotBlank() }
        ?: "dev"
}
