package org.jetbrains.hexana.mcp.server

enum class HexanaMcpToolProfile {
    PRODUCTION,
    ADVANCED;

    fun cliValue(): String = name.lowercase()

    internal fun includesTool(toolName: String): Boolean {
        return when (this) {
            PRODUCTION -> toolName in HexanaGeneratedMcpToolProfiles.PRODUCTION_TOOL_NAMES
            ADVANCED -> toolName in HexanaGeneratedMcpToolProfiles.ADVANCED_TOOL_NAMES
        }
    }

    companion object {
        private val ALIASES = mapOf(
            "default" to PRODUCTION,
            "production" to PRODUCTION,
            "advanced" to ADVANCED,
            "debug" to ADVANCED,
            "full" to ADVANCED,
        )

        fun parseCliValue(value: String): HexanaMcpToolProfile {
            return ALIASES[value.lowercase()]
                ?: throw IllegalArgumentException("Unsupported MCP tool profile: $value")
        }
    }
}
