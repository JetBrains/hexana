package org.jetbrains.hexana.mcp.server

import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    configureLogging(HexanaLogLevel.parseCliValue(args[0]))
    if (args[1] == "targets") {
        println("TARGETS ${currentConsoleLogTargets()}")
        return
    }
    val logger = LoggerFactory.getLogger("org.jetbrains.hexana.mcp.server.Probe")
    logger.debug("probe-debug")
    logger.info("probe-info")
    logger.warn("probe-warn")
    logger.error("probe-error")
}
