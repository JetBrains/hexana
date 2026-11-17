package org.jetbrains.hexana.cli.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun interface ToolExecutor {
    fun execute(request: ToolExecutionRequest): ToolExecutionResult
}

data class ToolExecutionRequest(
    val context: ToolRuntimeContext,
    val arguments: JsonObject,
    val sources: ToolInputSources = ToolInputSources(),
)

data class ToolExecutionResult(
    val semanticResult: JsonElement,
    val diagnostics: List<ToolExecutionDiagnostic> = emptyList(),
)

data class ToolExecutionDiagnostic(
    val severity: ToolExecutionDiagnosticSeverity,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "diagnostic message must not be blank." }
    }
}

enum class ToolExecutionDiagnosticSeverity {
    WARNING,
}
