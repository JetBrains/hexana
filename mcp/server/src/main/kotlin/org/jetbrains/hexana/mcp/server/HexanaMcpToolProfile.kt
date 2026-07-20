package org.jetbrains.hexana.mcp.server

enum class HexanaMcpToolProfile {
    DEFAULT,
    ADVANCED,
    SCRIPTING;

    fun cliValue(): String = name.lowercase()

    internal fun includesTool(toolName: String): Boolean {
        return when (this) {
            DEFAULT -> toolName in HexanaGeneratedMcpToolProfiles.DEFAULT_TOOL_NAMES
            ADVANCED -> toolName in HexanaGeneratedMcpToolProfiles.ADVANCED_TOOL_NAMES
            SCRIPTING -> toolName in HexanaGeneratedMcpToolProfiles.SCRIPTING_TOOL_NAMES
        }
    }

    companion object {
        private val ALIASES = mapOf(
            "default" to DEFAULT,
            "advanced" to ADVANCED,
            "scripting" to SCRIPTING,
        )

        fun parseCliValue(value: String): HexanaMcpToolProfile {
            return ALIASES[value.lowercase()]
                ?: throw IllegalArgumentException("Unsupported MCP tool profile: $value. Expected default, advanced, or scripting.")
        }
    }
}
