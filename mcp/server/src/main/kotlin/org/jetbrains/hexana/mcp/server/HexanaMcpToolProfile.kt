package org.jetbrains.hexana.mcp.server

enum class HexanaMcpToolProfile {
    DEFAULT,
    ADVANCED,
    SCRIPTING;

    fun cliValue(): String = profileId()

    internal fun includesTool(toolName: String): Boolean = toolName in HexanaGeneratedMcpToolProfiles.toolNames(profileId())

    private fun profileId(): String = HexanaGeneratedMcpToolProfiles.PROFILE_ID_BY_COMPATIBILITY_ENUM.getValue(name)

    companion object {
        fun defaultProfile(): HexanaMcpToolProfile = fromProfileId(HexanaGeneratedMcpToolProfiles.DEFAULT_PROFILE_ID)

        fun parseCliValue(value: String): HexanaMcpToolProfile {
            return HexanaGeneratedMcpToolProfiles.profileIdForAlias(value)?.let(::fromProfileId)
                ?: unsupported(value)
        }

        private fun fromProfileId(profileId: String): HexanaMcpToolProfile = entries.singleOrNull {
            HexanaGeneratedMcpToolProfiles.PROFILE_ID_BY_COMPATIBILITY_ENUM[it.name] == profileId
        } ?: error("Generated MCP profile '$profileId' has no compatible runtime enum.")

        private fun unsupported(value: String): Nothing =
            throw IllegalArgumentException("Unsupported MCP tool profile: $value. Expected default, advanced, or scripting.")
    }
}
