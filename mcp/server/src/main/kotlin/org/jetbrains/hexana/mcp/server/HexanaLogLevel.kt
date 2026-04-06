package org.jetbrains.hexana.mcp.server

enum class HexanaLogLevel {
    QUIET,
    INFO,
    DEBUG;

    fun cliValue(): String = name.lowercase()

    companion object {
        fun parseCliValue(value: String): HexanaLogLevel {
            return entries.firstOrNull { it.cliValue() == value.lowercase() }
                ?: throw IllegalArgumentException("Unsupported log level: $value")
        }
    }
}
