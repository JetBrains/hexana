package org.jetbrains.hexana.mcp.server

import org.slf4j.Logger
import java.io.File
import java.nio.file.Path

internal fun ProcessBuilder.useCurrentJvm(logger: Logger): ProcessBuilder {
    val javaHome = resolveCurrentJvmHome(
        javaHomeProperty = System.getProperty("java.home"),
        javaHomeEnvironment = System.getenv("JAVA_HOME")
    )

    val processEnvironment = environment()
    processEnvironment["JAVA_HOME"] = javaHome.toString()
    processEnvironment["JRE_HOME"] = javaHome.toString()

    processEnvironment["PATH"] = prependJavaBinToPath(javaHome, processEnvironment["PATH"])

    logger.info(
        "Configured child process JVM with JAVA_HOME={} javaVersion={} javaVendor={}",
        javaHome,
        System.getProperty("java.version"),
        System.getProperty("java.vendor")
    )
    return this
}

internal fun resolveCurrentJvmHome(
    javaHomeProperty: String?,
    javaHomeEnvironment: String?
): Path {
    val rawJavaHome = javaHomeProperty?.takeIf { it.isNotBlank() }
        ?: javaHomeEnvironment?.takeIf { it.isNotBlank() }
        ?: error("Unable to determine Java home from `java.home` or `JAVA_HOME`")

    return Path.of(rawJavaHome).toAbsolutePath().normalize()
}

internal fun prependJavaBinToPath(javaHome: Path, currentPath: String?): String {
    val javaBinDir = javaHome.resolve("bin").toString()
    return if (currentPath.isNullOrBlank()) {
        javaBinDir
    } else {
        "$javaBinDir${File.pathSeparator}$currentPath"
    }
}
