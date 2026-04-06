package org.jetbrains.hexana.mcp.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.joran.spi.ConsoleTarget
import org.slf4j.LoggerFactory

private const val HEXANA_STDERR_APPENDER = "hexana-stderr"

fun configureLogging(configuredLevel: HexanaLogLevel) {
    val loggerFactory = LoggerFactory.getILoggerFactory()
    val context = loggerFactory as? LoggerContext ?: return

    val encoder = PatternLayoutEncoder().apply {
        this.context = context
        pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
        start()
    }

    val appender = ConsoleAppender<ILoggingEvent>().apply {
        this.context = context
        name = HEXANA_STDERR_APPENDER
        target = ConsoleTarget.SystemErr.toString()
        this.encoder = encoder
        start()
    }

    context.getLogger(Logger.ROOT_LOGGER_NAME).apply {
        detachAndStopAllAppenders()
        addAppender(appender)
        level = configuredLevel.toLogbackLevel()
        isAdditive = false
    }
}

private fun HexanaLogLevel.toLogbackLevel(): Level {
    return when (this) {
        HexanaLogLevel.QUIET -> Level.ERROR
        HexanaLogLevel.INFO -> Level.INFO
        HexanaLogLevel.DEBUG -> Level.DEBUG
    }
}
