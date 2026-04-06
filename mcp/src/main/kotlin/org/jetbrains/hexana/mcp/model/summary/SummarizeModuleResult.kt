package org.jetbrains.hexana.mcp.model.summary

import kotlinx.serialization.Serializable

@Serializable
data class SummarizeModuleResult(
    val functions: Int,
    val exports: Int,
    val memories: Int,
    val globals: Int,
    val tables: Int,
    val types: Int,
    val imports: SummarizeModuleImports,
    val moduleSize: Int
)

@Serializable
data class SummarizeModuleImports(
    val functions: Int,
    val memories: Int,
    val tables: Int,
    val globals: Int
)
