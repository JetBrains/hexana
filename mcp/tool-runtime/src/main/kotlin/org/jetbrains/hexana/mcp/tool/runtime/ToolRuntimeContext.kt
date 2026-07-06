package org.jetbrains.hexana.mcp.tool.runtime

import java.nio.file.Path

data class ToolRuntimeContext(
    val toolName: String,
    val workingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(toolName.isNotBlank()) { "toolName must not be blank." }
    }
}
