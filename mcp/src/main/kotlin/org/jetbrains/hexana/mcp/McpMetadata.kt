package org.jetbrains.hexana.mcp

open class McpMetadata<T>(
    val from: Int,
    val to: Int,
    val returned: List<T>,
    open val total: Int,
    val truncate: Boolean,
    val nextOffset: Int?
)
