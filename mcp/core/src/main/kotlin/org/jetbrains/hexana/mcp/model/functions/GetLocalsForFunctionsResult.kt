package org.jetbrains.hexana.mcp.model.functions

import kotlinx.serialization.Serializable

@Serializable
data class GetLocalsForFunctionsResult(
    val functions: List<FunctionLocalsResult>
)

@Serializable
data class FunctionLocalsResult(
    val funcidx: Int,
    val locals: List<LocalDeclarationResult>
)

@Serializable
data class LocalDeclarationResult(
    val local: Int,
    val type: String
)
