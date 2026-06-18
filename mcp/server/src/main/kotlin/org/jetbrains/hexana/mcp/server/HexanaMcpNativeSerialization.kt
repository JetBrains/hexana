package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCEmptyMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.KSerializer

@Volatile
private var retainedMcpSerializers: Array<KSerializer<*>>? = null

internal fun retainMcpSerializersForNativeImage() {
    retainedMcpSerializers = arrayOf(
        JSONRPCRequest.serializer(),
        JSONRPCNotification.serializer(),
        JSONRPCResponse.serializer(),
        JSONRPCError.serializer(),
        JSONRPCEmptyMessage.serializer(),
        RequestId.StringId.serializer(),
        RequestId.NumberId.serializer(),
        InitializeResult.serializer(),
        ListToolsResult.serializer(),
        CallToolResult.serializer(),
        EmptyResult.serializer(),
        TextContent.serializer(),
    )
    retainedMcpSerializers?.forEach { it.descriptor.serialName }
}
