package org.jetbrains.hexana.disassembly.llvmobjdump

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

data class LlvmObjdumpCandidate(
    val path: Path,
    val source: LlvmObjdumpSource,
)

enum class LlvmObjdumpSource {
    ExplicitPath,
    ToolchainDirectory,
    ExtraDirectory,
    PathEnvironment,
}

object LlvmObjdumpDiscovery {
    fun discover(
        explicitPath: String? = null,
        toolchainDirectories: List<Path> = emptyList(),
        extraDirectories: List<Path> = emptyList(),
        pathEnv: String? = System.getenv("PATH"),
        isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
        pathSeparator: String = File.pathSeparator,
        isExecutable: (Path) -> Boolean = ::isExecutableFile,
        environment: Map<String, String> = System.getenv(),
    ): LlvmObjdumpCandidate? {
        return executableCandidates(
            explicitPath = explicitPath,
            toolchainDirectories = toolchainDirectories,
            extraDirectories = extraDirectories,
            pathEnv = pathEnv,
            isWindows = isWindows,
            pathSeparator = pathSeparator,
            environment = environment,
            isExecutable = isExecutable,
        ).firstOrNull()
    }

    fun executableCandidates(
        explicitPath: String? = null,
        toolchainDirectories: List<Path> = emptyList(),
        extraDirectories: List<Path> = emptyList(),
        pathEnv: String? = System.getenv("PATH"),
        isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
        pathSeparator: String = File.pathSeparator,
        environment: Map<String, String> = System.getenv(),
        isExecutable: (Path) -> Boolean = ::isExecutableFile,
    ): List<LlvmObjdumpCandidate> {
        return candidatePaths(
            explicitPath = explicitPath,
            toolchainDirectories = toolchainDirectories,
            extraDirectories = extraDirectories,
            pathEnv = pathEnv,
            isWindows = isWindows,
            pathSeparator = pathSeparator,
            environment = environment,
        ).filter { isExecutable(it.path) }
    }

    fun candidatePaths(
        explicitPath: String? = null,
        toolchainDirectories: List<Path> = emptyList(),
        extraDirectories: List<Path> = emptyList(),
        pathEnv: String? = System.getenv("PATH"),
        isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
        pathSeparator: String = File.pathSeparator,
        environment: Map<String, String> = System.getenv(),
    ): List<LlvmObjdumpCandidate> {
        val names = executableNames(isWindows)
        val result = mutableListOf<LlvmObjdumpCandidate>()
        val seen = mutableSetOf<String>()

        fun add(path: Path, source: LlvmObjdumpSource) {
            if (seen.add(path.toString())) {
                result.add(LlvmObjdumpCandidate(path, source))
            }
        }

        explicitPath?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
            parsePath(path, environment)?.let { add(it, LlvmObjdumpSource.ExplicitPath) }
        }

        for (directory in toolchainDirectories) {
            for (name in names) {
                add(directory.resolve(name), LlvmObjdumpSource.ToolchainDirectory)
            }
        }

        for (directory in extraDirectories) {
            for (name in names) {
                add(directory.resolve(name), LlvmObjdumpSource.ExtraDirectory)
            }
        }

        pathEnv
            ?.split(pathSeparator)
            ?.asSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.mapNotNull { parsePath(it, environment) }
            ?.forEach { directory ->
                for (name in names) {
                    add(directory.resolve(name), LlvmObjdumpSource.PathEnvironment)
                }
            }

        return result
    }
}

private fun executableNames(isWindows: Boolean): List<String> =
    if (isWindows) listOf("llvm-objdump.exe", "llvm-objdump") else listOf("llvm-objdump")

private fun parsePath(path: String, environment: Map<String, String>): Path? {
    val normalized = path.stripSurroundingQuotes().expandWindowsEnvironmentVariables(environment).trim()
    if (normalized.isEmpty()) return null
    return runCatching { Path.of(normalized) }.getOrNull()
}

private fun String.stripSurroundingQuotes(): String {
    val trimmed = trim()
    return if (trimmed.length >= 2 && trimmed.first() == trimmed.last() && trimmed.first() in "'\"") {
        trimmed.substring(1, trimmed.lastIndex)
    } else {
        trimmed
    }
}

private fun String.expandWindowsEnvironmentVariables(environment: Map<String, String>): String =
    WINDOWS_ENVIRONMENT_VARIABLE.replace(this) { match ->
        environment.getIgnoringCase(match.groupValues[1]) ?: match.value
    }

private val WINDOWS_ENVIRONMENT_VARIABLE = Regex("""%([^%]+)%""")

private fun Map<String, String>.getIgnoringCase(key: String): String? =
    entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

private fun isExecutableFile(path: Path): Boolean =
    Files.isRegularFile(path) && Files.isExecutable(path)
