package org.jetbrains.hexana.cli.runtime

enum class ToolOutputFormat(val cliName: String) {
    JSON("json"),
    PRETTY("pretty"),
    RAW("raw"),
    TABLE("table"),
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
