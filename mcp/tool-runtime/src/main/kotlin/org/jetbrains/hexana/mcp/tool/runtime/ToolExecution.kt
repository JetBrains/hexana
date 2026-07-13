package org.jetbrains.hexana.mcp.tool.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun interface ToolExecutor {
    fun execute(request: ToolExecutionRequest): ToolExecutionResult
}

data class ToolExecutionRequest(
    val context: ToolRuntimeContext,
    val arguments: JsonObject,
)

data class ToolExecutionResult(
    val semanticResult: JsonElement,
)
