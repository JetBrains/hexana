package org.jetbrains.hexana.mcp.server

import org.slf4j.Logger
import java.io.File
import java.nio.file.Path

internal fun ProcessBuilder.useCurrentJvm(logger: Logger): ProcessBuilder {
    val javaHome = Path.of(
        System.getenv("JAVA_HOME")
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.home")
    ).toAbsolutePath().normalize()

    val environment = environment()
    environment["JAVA_HOME"] = javaHome.toString()
    environment["JRE_HOME"] = javaHome.toString()

    val javaBinDir = javaHome.resolve("bin").toString()
    val currentPath = environment["PATH"]
    environment["PATH"] = if (currentPath.isNullOrBlank()) {
        javaBinDir
    } else {
        "$javaBinDir${File.pathSeparator}$currentPath"
    }

    logger.info(
        "Configured child process JVM with JAVA_HOME={} javaVersion={} javaVendor={}",
        javaHome,
        System.getProperty("java.version"),
        System.getProperty("java.vendor")
    )
    return this
}
