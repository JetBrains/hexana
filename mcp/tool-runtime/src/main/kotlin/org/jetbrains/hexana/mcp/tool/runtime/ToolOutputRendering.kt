package org.jetbrains.hexana.mcp.tool.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface ToolOutputRenderer {
    fun render(request: ToolOutputRenderRequest): RenderedToolOutput
}

data class ToolOutputRenderRequest(
    val context: ToolRuntimeContext,
    val arguments: JsonObject,
    val result: JsonElement,
    val options: ToolRenderOptions = ToolRenderOptions(),
)

data class ToolRenderOptions(
    val format: ToolOutputFormat = ToolOutputFormat.Json,
    val colorMode: TerminalColorMode = TerminalColorMode.Auto,
    val terminalWidth: Int? = null,
    val includeDiagnostics: Boolean = false,
) {
    init {
        require(terminalWidth == null || terminalWidth > 0) { "terminalWidth must be positive when provided." }
    }
}

enum class TerminalColorMode {
    Auto,
    Always,
    Never,
}
