package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.hexana.mcp.HexanaResourceLimits
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

internal const val HEXANA_MCP_LOG_DIR_ENV = "HEXANA_MCP_LOG_DIR"
private val DIAGNOSTIC_FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    .withZone(ZoneOffset.UTC)

internal enum class HexanaFailureCategory {
    CONFIGURATION,
    STARTUP,
    STDIO_TRANSPORT,
    HTTP_TRANSPORT,
    TOOL_INTERNAL,
    CLI_INTERNAL,
}

internal data class HexanaDiagnosticContext(
    val version: String,
    val command: String,
    val transport: String?,
    val toolProfile: String?,
    val logLevel: String,
    val telemetryEnabled: Boolean?,
    val allowedRootCount: Int,
    val resourceLimits: HexanaResourceLimits?,
)

internal data class HexanaDiagnosticStatus(
    val directory: Path,
    val file: Path?,
    val available: Boolean,
    val problem: String? = null,
) {
    val displayLocation: String
        get() = file?.toString() ?: directory.toString()
}

internal data class HexanaDiagnosticReference(
    val id: String,
    val location: String,
    val available: Boolean,
)

internal class HexanaDiagnosticSession private constructor(
    private val context: HexanaDiagnosticContext,
    private val sessionId: String,
    private val now: () -> Instant,
    initialStatus: HexanaDiagnosticStatus,
) : AutoCloseable {
    @Volatile
    private var currentStatus = initialStatus

    val status: HexanaDiagnosticStatus
        get() = currentStatus

    fun recordStart() {
        try {
            append(
                buildJsonObject {
                    put("event", "session_start")
                    put("context", contextJson())
                }
            )
        } catch (error: Exception) {
            markUnavailable(error)
        }
    }

    fun report(category: HexanaFailureCategory, summary: String, error: Throwable): HexanaDiagnosticReference {
        val diagnosticId = UUID.randomUUID().toString().substring(0, 8)
        try {
            append(
                buildJsonObject {
                    put("event", "failure")
                    put("diagnostic_id", diagnosticId)
                    put("category", category.name)
                    put("summary", summary)
                    put("exception", exceptionJson(error))
                    put("context", contextJson())
                }
            )
        } catch (failure: Exception) {
            markUnavailable(failure)
        }
        return HexanaDiagnosticReference(diagnosticId, status.displayLocation, status.available)
    }

    private fun append(event: JsonObject) {
        val file = currentStatus.file ?: return
        val record = buildJsonObject {
            put("schema_version", 1)
            put("timestamp", now().toString())
            put("session_id", sessionId)
            event.forEach { (key, value) -> put(key, value) }
        }
        try {
            synchronized(this) {
                Files.writeString(file, record.toString() + "\n", StandardOpenOption.APPEND)
            }
        } catch (error: Exception) {
            markUnavailable(error)
        }
    }

    private fun markUnavailable(error: Throwable) {
        currentStatus = HexanaDiagnosticStatus(
            directory = currentStatus.directory,
            file = null,
            available = false,
            problem = error.javaClass.simpleName,
        )
    }

    override fun close() = Unit

    private fun contextJson(): JsonObject = buildJsonObject {
        put("version", context.version)
        put("command", context.command)
        context.transport?.let { put("transport", it) }
        context.toolProfile?.let { put("tool_profile", it) }
        put("log_level", context.logLevel)
        context.telemetryEnabled?.let { put("telemetry_enabled", it) }
        put("allowed_root_count", context.allowedRootCount)
        put("runtime", if (isRunningInGraalVmNativeImage()) "native" else "jvm")
        put("java_version", System.getProperty("java.version").orEmpty())
        context.resourceLimits?.let { put("limits", limitsJson(it)) }
    }

    companion object {
        fun open(
            context: HexanaDiagnosticContext,
            envReader: (String) -> String? = System::getenv,
            userHome: String? = null,
            now: () -> Instant = Instant::now,
        ): HexanaDiagnosticSession {
            val directory = resolveDiagnosticDirectory(envReader, userHome)
            val sessionId = UUID.randomUUID().toString().substring(0, 12)
            val file = directory.resolve(
                "hexana-mcp-${DIAGNOSTIC_FILE_TIMESTAMP.format(now())}-$sessionId.jsonl"
            )
            val status = try {
                Files.createDirectories(directory)
                Files.createFile(file)
                HexanaDiagnosticStatus(directory, file, available = true)
            } catch (error: Exception) {
                HexanaDiagnosticStatus(directory, file = null, available = false, problem = error.javaClass.simpleName)
            }
            return HexanaDiagnosticSession(context, sessionId, now, status).also { it.recordStart() }
        }
    }
}

internal object HexanaDiagnostics : AutoCloseable {
    private var session: HexanaDiagnosticSession? = null

    val status: HexanaDiagnosticStatus
        get() = synchronized(this) { session?.status ?: diagnosticStatus() }

    fun start(context: HexanaDiagnosticContext, envReader: (String) -> String? = System::getenv) = synchronized(this) {
        session?.close()
        session = HexanaDiagnosticSession.open(context, envReader)
    }

    fun report(
        category: HexanaFailureCategory,
        summary: String,
        error: Throwable,
        envReader: (String) -> String? = System::getenv,
    ): HexanaDiagnosticReference = synchronized(this) {
        val active = session ?: HexanaDiagnosticSession.open(fallbackContext(), envReader).also { session = it }
        active.report(category, summary, error)
    }

    override fun close() = synchronized(this) {
        session?.close()
        session = null
    }
}

internal fun emitDiagnosticFailure(
    category: HexanaFailureCategory,
    summary: String,
    error: Throwable,
): HexanaDiagnosticReference {
    val reference = HexanaDiagnostics.report(category, summary, error)
    val destination = if (reference.available) {
        "Diagnostics: ${reference.location}"
    } else {
        "Diagnostics unavailable at ${reference.location}"
    }
    System.err.println("hexana-mcp error [${category.name}; id=${reference.id}]: $summary $destination")
    return reference
}

internal fun diagnosticStatus(
    envReader: (String) -> String? = System::getenv,
    userHome: String? = null,
): HexanaDiagnosticStatus {
    val directory = resolveDiagnosticDirectory(envReader, userHome)
    val available = try {
        Files.createDirectories(directory)
        Files.isDirectory(directory) && Files.isWritable(directory)
    } catch (_: Exception) {
        false
    }
    return HexanaDiagnosticStatus(
        directory = directory,
        file = null,
        available = available,
    )
}

private fun resolveDiagnosticDirectory(envReader: (String) -> String?, userHome: String?): Path {
    val configured = try {
        envReader(HEXANA_MCP_LOG_DIR_ENV)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
    return try {
        val home = userHome ?: System.getProperty("user.home").orEmpty()
        configured?.let(Path::of) ?: Path.of(home, ".hexana", "mcp", "logs")
    } catch (_: Exception) {
        Path.of(".hexana", "mcp", "logs").toAbsolutePath()
    }
}

internal fun HexanaCommand.diagnosticContext(version: String): HexanaDiagnosticContext = when (this) {
    is HexanaCommand.Stdio -> HexanaDiagnosticContext(
        version, "stdio", "stdio", toolProfile.cliValue(), logLevel.cliValue(), analyticsEnabled,
        allowedRoots.count { it.isNotBlank() }, resourceLimits,
    )
    is HexanaCommand.Http -> HexanaDiagnosticContext(
        version, "http", "http", toolProfile.cliValue(), logLevel.cliValue(), analyticsEnabled,
        allowedRoots.count { it.isNotBlank() }, resourceLimits,
    )
    is HexanaCommand.Doctor -> HexanaDiagnosticContext(
        version, "doctor", null, null, HexanaLogLevel.INFO.cliValue(), null,
        allowedRoots.count { it.isNotBlank() }, null,
    )
    HexanaCommand.Version -> fallbackContext(version, "version")
    HexanaCommand.Help -> fallbackContext(version, "help")
}

private fun fallbackContext(version: String = hexanaServerVersion(), command: String = "cli") =
    HexanaDiagnosticContext(version, command, null, null, HexanaLogLevel.INFO.cliValue(), null, 0, null)

private fun limitsJson(limits: HexanaResourceLimits): JsonObject = buildJsonObject {
    put("max_inline_wasm_bytes", limits.maxInlineWasmBytes)
    put("max_file_wasm_bytes", limits.maxFileWasmBytes)
    put("max_inline_binary_bytes", limits.maxInlineBinaryBytes)
    put("max_file_binary_bytes", limits.maxFileBinaryBytes)
    put("max_text_file_bytes", limits.maxTextFileBytes)
    put("max_output_bytes", limits.maxOutputBytes)
    put("max_candidate_count", limits.maxCandidateCount)
    put("max_scan_roots", limits.maxScanRoots)
    put("max_scan_depth", limits.maxScanDepth)
}

private fun exceptionJson(error: Throwable): JsonObject = buildJsonObject {
    put("type", error.javaClass.name)
    put("stack_trace", stackTraceJson(error.stackTrace))
    error.cause?.takeUnless { it === error }?.let { cause ->
        put("cause", buildJsonObject {
            put("type", cause.javaClass.name)
            put("stack_trace", stackTraceJson(cause.stackTrace))
        })
    }
}

private fun stackTraceJson(frames: Array<StackTraceElement>): JsonArray = buildJsonArray {
    frames.take(80).forEach { frame ->
        add(JsonPrimitive("${frame.className}.${frame.methodName}(${frame.fileName ?: "Unknown"}:${frame.lineNumber})"))
    }
}
