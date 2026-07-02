package org.jetbrains.hexana.mcp.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.joran.spi.ConsoleTarget
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal object HexanaDoctor {
    fun inspect(environment: DoctorEnvironment): DoctorReport {
        val checks = listOf(
            versionCheck(environment.version),
            runtimeCheck(environment),
            logDestinationCheck(environment.consoleLogTargets),
            allowedRootsCheck(environment.allowedRoots),
            installedLayoutCheck(environment)
        )
        return DoctorReport(checks)
    }

    fun render(report: DoctorReport): String {
        return buildString {
            appendLine("Hexana MCP doctor")
            appendLine("Overall: ${report.status.label}")
            report.checks.forEach { check ->
                appendLine("[${check.severity.label}] ${check.name}: ${check.message}")
            }
        }.trimEnd()
    }

    private fun versionCheck(version: String): DoctorCheck {
        return if (version == "dev") {
            DoctorCheck(
                severity = DoctorSeverity.WARNING,
                name = "version",
                message = "fallback development version `dev`; packaged distributions should provide an exact version"
            )
        } else {
            DoctorCheck(
                severity = DoctorSeverity.OK,
                name = "version",
                message = version
            )
        }
    }

    private fun runtimeCheck(environment: DoctorEnvironment): DoctorCheck {
        if (environment.nativeImage) {
            return DoctorCheck(
                severity = DoctorSeverity.OK,
                name = "runtime",
                message = "GraalVM Native Image"
            )
        }

        val javaVersion = environment.javaVersion.ifBlank { "unknown" }
        val javaVendor = environment.javaVendor.ifBlank { "unknown vendor" }
        val javaHome = environment.javaHome.ifBlank { "unknown java.home" }
        val javaMajor = parseJavaSpecificationMajorVersion(environment.javaSpecificationVersion)
        if (javaMajor != null && javaMajor < 21) {
            return DoctorCheck(
                severity = DoctorSeverity.ERROR,
                name = "runtime",
                message = "JVM $javaVersion from $javaVendor is below required Java 21 (java.home=$javaHome)"
            )
        }

        return DoctorCheck(
            severity = DoctorSeverity.OK,
            name = "runtime",
            message = "JVM $javaVersion from $javaVendor (java.home=$javaHome)"
        )
    }

    private fun logDestinationCheck(consoleLogTargets: Set<ConsoleLogTarget>?): DoctorCheck {
        if (consoleLogTargets == null) {
            return DoctorCheck(
                severity = DoctorSeverity.WARNING,
                name = "logs",
                message = "logging backend is not Logback; keep human-readable transport logs off stdout"
            )
        }

        if (ConsoleLogTarget.STDOUT in consoleLogTargets) {
            return DoctorCheck(
                severity = DoctorSeverity.ERROR,
                name = "logs",
                message = "a console log appender targets stdout; stdio stdout must remain MCP protocol-only"
            )
        }

        if (ConsoleLogTarget.STDERR in consoleLogTargets) {
            return DoctorCheck(
                severity = DoctorSeverity.OK,
                name = "logs",
                message = "human-readable logs are configured for stderr; stdio stdout remains protocol-only"
            )
        }

        return DoctorCheck(
            severity = DoctorSeverity.WARNING,
            name = "logs",
            message = "no console log appender detected; transport logs must remain off stdout"
        )
    }

    private fun allowedRootsCheck(allowedRoots: List<String>): DoctorCheck {
        val configuredRoots = allowedRoots.filter { it.isNotBlank() }
        if (configuredRoots.isEmpty()) {
            return DoctorCheck(
                severity = DoctorSeverity.OK,
                name = "allowed roots",
                message = "none configured; all local paths are currently allowed by default"
            )
        }

        val invalidRoots = configuredRoots.mapNotNull { rawRoot ->
            val root = runCatching { Path.of(rawRoot) }.getOrNull()
                ?: return@mapNotNull "$rawRoot (invalid path)"
            if (!Files.isDirectory(root)) {
                return@mapNotNull "$rawRoot (missing or not a directory)"
            }
            runCatching { root.toRealPath() }
                .exceptionOrNull()
                ?.let { "$rawRoot (cannot resolve real path)" }
        }

        if (invalidRoots.isNotEmpty()) {
            return DoctorCheck(
                severity = DoctorSeverity.ERROR,
                name = "allowed roots",
                message = "invalid configured root(s): ${invalidRoots.joinToString()}"
            )
        }

        return DoctorCheck(
            severity = DoctorSeverity.OK,
            name = "allowed roots",
            message = "${configuredRoots.size} configured"
        )
    }

    private fun installedLayoutCheck(environment: DoctorEnvironment): DoctorCheck {
        if (environment.nativeImage) {
            return DoctorCheck(
                severity = DoctorSeverity.OK,
                name = "installed layout",
                message = "native executable runtime; JVM launcher layout is not expected"
            )
        }

        val serverJar = environment.classPath
            .split(File.pathSeparatorChar)
            .mapNotNull { rawEntry -> rawEntry.takeIf { it.isNotBlank() }?.let { Path.of(it) } }
            .firstOrNull { path ->
                val fileName = path.fileName?.toString().orEmpty()
                fileName.startsWith("server-") &&
                    fileName.endsWith(".jar") &&
                    path.parent?.fileName?.toString() == "lib"
            }

        if (serverJar == null) {
            return DoctorCheck(
                severity = DoctorSeverity.WARNING,
                name = "installed layout",
                message = "JVM distribution layout was not detected from classpath"
            )
        }

        val installDir = serverJar.parent.parent
        val launcher = if (isWindows()) installDir.resolve("bin/server.bat") else installDir.resolve("bin/server")
        val missing = buildList {
            if (!Files.isDirectory(installDir.resolve("lib"))) {
                add("lib/")
            }
            if (!Files.isRegularFile(launcher)) {
                add(installDir.relativize(launcher).toString())
            }
        }

        if (missing.isNotEmpty()) {
            return DoctorCheck(
                severity = DoctorSeverity.ERROR,
                name = "installed layout",
                message = "detected $installDir but missing ${missing.joinToString()}"
            )
        }

        return DoctorCheck(
            severity = DoctorSeverity.OK,
            name = "installed layout",
            message = "JVM distribution detected at $installDir"
        )
    }
}

internal data class DoctorEnvironment(
    val version: String,
    val allowedRoots: List<String>,
    val nativeImage: Boolean,
    val javaVersion: String,
    val javaVendor: String,
    val javaHome: String,
    val javaSpecificationVersion: String,
    val classPath: String,
    val consoleLogTargets: Set<ConsoleLogTarget>?,
) {
    companion object {
        fun current(version: String, allowedRoots: List<String>): DoctorEnvironment {
            return DoctorEnvironment(
                version = version,
                allowedRoots = allowedRoots,
                nativeImage = isRunningInGraalVmNativeImage(),
                javaVersion = System.getProperty("java.version").orEmpty(),
                javaVendor = System.getProperty("java.vendor").orEmpty(),
                javaHome = System.getProperty("java.home").orEmpty(),
                javaSpecificationVersion = System.getProperty("java.specification.version").orEmpty(),
                classPath = System.getProperty("java.class.path").orEmpty(),
                consoleLogTargets = currentConsoleLogTargets()
            )
        }
    }
}

internal data class DoctorReport(
    val checks: List<DoctorCheck>,
) {
    val status: DoctorStatus
        get() = when {
            checks.any { it.severity == DoctorSeverity.ERROR } -> DoctorStatus.FAILURE
            checks.any { it.severity == DoctorSeverity.WARNING } -> DoctorStatus.WARNING
            else -> DoctorStatus.SUCCESS
        }

    val exitCode: Int
        get() = status.exitCode
}

internal data class DoctorCheck(
    val severity: DoctorSeverity,
    val name: String,
    val message: String,
)

internal enum class DoctorSeverity(val label: String) {
    OK("OK"),
    WARNING("WARN"),
    ERROR("FAIL")
}

internal enum class DoctorStatus(val label: String, val exitCode: Int) {
    SUCCESS("OK", 0),
    WARNING("WARNING", 0),
    FAILURE("FAILURE", 1)
}

internal enum class ConsoleLogTarget {
    STDOUT,
    STDERR
}

internal fun currentConsoleLogTargets(): Set<ConsoleLogTarget>? {
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return null
    val rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME)
    val targets = mutableSetOf<ConsoleLogTarget>()
    val appenders = rootLogger.iteratorForAppenders()
    while (appenders.hasNext()) {
        val appender = appenders.next()
        if (appender is ConsoleAppender<*>) {
            when (appender.target) {
                ConsoleTarget.SystemOut.toString() -> targets += ConsoleLogTarget.STDOUT
                ConsoleTarget.SystemErr.toString() -> targets += ConsoleLogTarget.STDERR
            }
        }
    }
    return targets
}

private fun parseJavaSpecificationMajorVersion(version: String): Int? {
    if (version.isBlank()) {
        return null
    }
    val normalized = version.removePrefix("1.")
    return normalized.substringBefore('.').toIntOrNull()
}

private fun isWindows(): Boolean {
    return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
