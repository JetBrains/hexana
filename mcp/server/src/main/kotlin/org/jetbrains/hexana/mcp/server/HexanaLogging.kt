package org.jetbrains.hexana.mcp.server

internal const val SIMPLE_LOGGER_PROPERTY_PREFIX = "org.slf4j.simpleLogger."
internal const val SIMPLE_LOGGER_LOG_FILE_PROPERTY = "${SIMPLE_LOGGER_PROPERTY_PREFIX}logFile"
internal const val SIMPLE_LOGGER_FACTORY_CLASS_NAME = "org.slf4j.simple.SimpleLoggerFactory"

/**
 * Configures slf4j-simple, which reads its settings once, when the first logger is created. This must run before any
 * SLF4J or kotlin-logging logger is touched, otherwise the requested level is silently ignored.
 *
 * Logs go to stderr so that stdout stays protocol-only for the stdio transport.
 */
fun configureLogging(configuredLevel: HexanaLogLevel) {
    mapOf(
        "defaultLogLevel" to configuredLevel.toSimpleLoggerLevel(),
        "logFile" to "System.err",
        "showDateTime" to "false",
        "showThreadName" to "false",
        "showLogName" to "false",
        "showShortLogName" to "false",
        "levelInBrackets" to "false",
    ).forEach { (key, value) -> System.setProperty("$SIMPLE_LOGGER_PROPERTY_PREFIX$key", value) }
}

private fun HexanaLogLevel.toSimpleLoggerLevel(): String {
    return when (this) {
        HexanaLogLevel.QUIET -> "error"
        HexanaLogLevel.INFO -> "info"
        HexanaLogLevel.DEBUG -> "debug"
    }
}
