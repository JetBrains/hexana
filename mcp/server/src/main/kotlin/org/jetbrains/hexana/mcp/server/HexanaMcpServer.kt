package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.DefaultHexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaResourceLimits

fun createHexanaMcpServer(
    resourceLimits: HexanaResourceLimits = HexanaResourceLimits(),
    analysisService: HexanaAnalysisService = DefaultHexanaAnalysisService(resourceLimits),
    transport: HexanaEvent.McpTransport = HexanaEvent.McpTransport.STDIO,
    toolProfile: HexanaMcpToolProfile = HexanaMcpToolProfile.PRODUCTION
): Server {
    val toolHandlers = HexanaToolHandlers(
        analysisService = analysisService,
        resourceLimits = resourceLimits,
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

fun createStandaloneHexanaAnalysisService(
    allowedRoots: List<String>,
    resourceLimits: HexanaResourceLimits,
): HexanaAnalysisService {
    // TODO: Replace this compatibility default with client-provided workspace roots once wrappers can pass them reliably.
    val allowedFilePolicy = if (allowedRoots.none { it.isNotBlank() }) {
        AllowedFilePolicy.allowAll
    } else {
        AllowedFilePolicy.requiringAllowedRoots(allowedRoots)
    }
    return DefaultHexanaAnalysisService(
        limits = resourceLimits,
        allowedFilePolicy = allowedFilePolicy
    )
}

internal fun hexanaServerVersion(): String {
    return System.getProperty("hexana.version")
        ?: object {}.javaClass.`package`.implementationVersion
        ?: HexanaBuildInfo.VERSION.takeIf { it.isNotBlank() }
        ?: "dev"
}
