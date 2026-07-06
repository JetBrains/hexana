package org.jetbrains.hexana.mcp.tool.runtime

enum class ToolOutputFormat(val cliName: String) {
    Json("json"),
    Pretty("pretty"),
    Raw("raw"),
    ;

    companion object {
        val cliNames: Set<String> = entries.mapTo(linkedSetOf()) { it.cliName }

        fun parse(cliName: String): ToolOutputFormat {
            val normalizedName = cliName.trim().lowercase()
            return entries.firstOrNull { it.cliName == normalizedName }
                ?: throw IllegalArgumentException(
                    "Unknown tool output format '$cliName'. Supported formats: ${cliNames.joinToString()}."
                )
        }
    }
}
