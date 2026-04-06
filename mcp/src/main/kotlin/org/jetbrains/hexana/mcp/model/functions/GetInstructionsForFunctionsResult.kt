package org.jetbrains.hexana.mcp.model.functions

import kotlinx.serialization.Serializable

@Serializable
data class GetInstructionsForFunctionsResult(
    val functions: List<FunctionInstructionsResult>
)

@Serializable
data class FunctionInstructionsResult(
    val funcidx: Int,
    val instructions: List<String>
)
