package org.jetbrains.hexana.mcp.model.functions

import kotlinx.serialization.Serializable

@Serializable
data class ListCodeSectionOffsetsResult(
    val functions: List<CodeSectionOffsetResult>
)

@Serializable
data class CodeSectionOffsetResult(
    val funcidx: Int,
    val offset: Long,
    val size: Long
)
