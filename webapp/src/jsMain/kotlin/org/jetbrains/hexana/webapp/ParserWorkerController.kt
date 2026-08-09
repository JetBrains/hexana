package org.jetbrains.hexana.webapp

import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer

internal const val PARSER_WORKER_PROTOCOL_VERSION = 1
/** Same-origin public-distribution asset; Karma maps this URL to its test fixture. */
internal const val PARSER_WORKER_URL = "parser-worker.js"

/** Production lifecycle policy. Tests inject a scheduler and assert this value without waiting. */
internal const val PARSER_WORKER_TIMEOUT_MS = 240_000

internal data class ParserWorkerSummary(
    val scopeMarker: String,
    val documentAbsent: Boolean,
    val inputBytes: Int,
    val sectionCount: Int,
    val importCount: Int,
    val definedFunctionCount: Int,
    val codeBodyCount: Int,
    val elapsedMs: Double,
)

internal sealed interface ParserWorkerState {
    data object Ready : ParserWorkerState
    data class Parsing(val requestId: String) : ParserWorkerState
    data class Parsed(val summary: ParserWorkerSummary) : ParserWorkerState
    data class Failed(val code: String, val message: String) : ParserWorkerState
    data object TimedOut : ParserWorkerState
    data object Cancelled : ParserWorkerState
}

internal interface ParserWorkerPort {
    fun setHandlers(
        onMessage: (dynamic) -> Unit,
        onError: () -> Unit,
        onMessageError: () -> Unit,
    )

    fun clearHandlers()
    fun postParse(request: dynamic, buffer: ArrayBuffer)
    fun terminate()
}

internal fun interface ParserWorkerFactory {
    fun create(): ParserWorkerPort
}

internal interface ParserWorkerTimer {
    fun cancel()
}

internal fun interface ParserWorkerScheduler {
    fun schedule(delayMs: Int, callback: () -> Unit): ParserWorkerTimer
}

internal class ParserWorkerController(
    private val workerFactory: ParserWorkerFactory,
    private val scheduler: ParserWorkerScheduler,
    private val onStateChanged: (ParserWorkerState) -> Unit,
) {
    private data class ActiveRequest(
        val generation: Int,
        val requestId: String,
        val worker: ParserWorkerPort,
        var timer: ParserWorkerTimer? = null,
        var terminated: Boolean = false,
    )

    private var generation = 0
    private var active: ActiveRequest? = null

    fun parse(buffer: ArrayBuffer, timeoutMs: Int) {
        require(timeoutMs > 0)
        // TODO(MCP-181): Remove when bounded parallel parsing with per-request ownership/isolation is implemented and browser-tested.
        invalidateAndTerminateActive()
        val requestGeneration = generation
        val requestId = "parser-worker-$requestGeneration"
        val worker = try {
            workerFactory.create()
        } catch (_: Throwable) {
            onStateChanged(ParserWorkerState.Failed("WORKER_START_FAILED", "The parser worker could not start."))
            return
        }
        val request = ActiveRequest(requestGeneration, requestId, worker)
        active = request
        worker.setHandlers(
            onMessage = { message -> handleMessage(request, message) },
            onError = { fail(request, "WORKER_ERROR", "The parser worker stopped unexpectedly.") },
            onMessageError = { fail(request, "MESSAGE_ERROR", "The parser worker response was unreadable.") },
        )
        request.timer = scheduler.schedule(timeoutMs) { timeout(request) }
        onStateChanged(ParserWorkerState.Parsing(requestId))

        val wireRequest = js("({})")
        wireRequest.protocolVersion = PARSER_WORKER_PROTOCOL_VERSION
        wireRequest.kind = "parse"
        wireRequest.requestId = requestId
        wireRequest.buffer = buffer
        try {
            worker.postParse(wireRequest, buffer)
        } catch (_: Throwable) {
            fail(request, "POST_FAILED", "The parser worker request could not be sent.")
        }
    }

    fun cancel() {
        if (active == null) return
        invalidateAndTerminateActive()
        onStateChanged(ParserWorkerState.Cancelled)
    }

    fun dispose() {
        invalidateAndTerminateActive()
    }

    private fun handleMessage(request: ActiveRequest, message: dynamic) {
        if (!isCurrent(request)) return
        when (val decoded = decodeParserWorkerResponse(message, request.requestId)) {
            is DecodedWorkerResponse.Success -> {
                complete(request)
                onStateChanged(ParserWorkerState.Parsed(decoded.summary))
            }
            is DecodedWorkerResponse.Error -> {
                complete(request)
                onStateChanged(ParserWorkerState.Failed(decoded.code, decoded.message))
            }
            DecodedWorkerResponse.Malformed -> fail(
                request,
                "INVALID_RESPONSE",
                "The parser worker response was invalid.",
            )
        }
    }

    private fun timeout(request: ActiveRequest) {
        if (!isCurrent(request)) return
        invalidateAndTerminateActive()
        onStateChanged(ParserWorkerState.TimedOut)
    }

    private fun fail(request: ActiveRequest, code: String, message: String) {
        if (!isCurrent(request)) return
        complete(request)
        onStateChanged(ParserWorkerState.Failed(code, message))
    }

    private fun complete(request: ActiveRequest) {
        if (!isCurrent(request)) return
        generation++
        active = null
        request.timer?.cancel()
        request.timer = null
        request.worker.clearHandlers()
        terminateOnce(request)
    }

    private fun invalidateAndTerminateActive() {
        generation++
        val request = active ?: return
        active = null
        request.timer?.cancel()
        request.timer = null
        request.worker.clearHandlers()
        terminateOnce(request)
    }

    private fun terminateOnce(request: ActiveRequest) {
        if (request.terminated) return
        request.terminated = true
        request.worker.terminate()
    }

    private fun isCurrent(request: ActiveRequest): Boolean =
        active === request && generation == request.generation
}

internal sealed interface DecodedWorkerResponse {
    data class Success(val summary: ParserWorkerSummary) : DecodedWorkerResponse
    data class Error(val code: String, val message: String) : DecodedWorkerResponse
    data object Malformed : DecodedWorkerResponse
}

internal fun decodeParserWorkerResponse(value: dynamic, requestId: String): DecodedWorkerResponse {
    if (!isPlainWireObject(value)) return DecodedWorkerResponse.Malformed
    if (value.protocolVersion != PARSER_WORKER_PROTOCOL_VERSION || value.requestId != requestId) {
        return DecodedWorkerResponse.Malformed
    }
    return when (value.kind as? String) {
        "success" -> {
            val scopeMarker = value.scopeMarker as? String ?: return DecodedWorkerResponse.Malformed
            val documentAbsent = value.documentAbsent as? Boolean ?: return DecodedWorkerResponse.Malformed
            val inputBytes = safeWireInt(value.inputBytes) ?: return DecodedWorkerResponse.Malformed
            val sectionCount = safeWireInt(value.sectionCount) ?: return DecodedWorkerResponse.Malformed
            val importCount = safeWireInt(value.importCount) ?: return DecodedWorkerResponse.Malformed
            val definedFunctionCount = safeWireInt(value.definedFunctionCount) ?: return DecodedWorkerResponse.Malformed
            val codeBodyCount = safeWireInt(value.codeBodyCount) ?: return DecodedWorkerResponse.Malformed
            val elapsedMs = safeWireDouble(value.elapsedMs) ?: return DecodedWorkerResponse.Malformed
            if (scopeMarker != "DedicatedWorkerGlobalScope" || !documentAbsent) {
                return DecodedWorkerResponse.Malformed
            }
            DecodedWorkerResponse.Success(
                ParserWorkerSummary(
                    scopeMarker,
                    documentAbsent,
                    inputBytes,
                    sectionCount,
                    importCount,
                    definedFunctionCount,
                    codeBodyCount,
                    elapsedMs,
                )
            )
        }
        "error" -> {
            val code = value.code as? String ?: return DecodedWorkerResponse.Malformed
            val message = value.message as? String ?: return DecodedWorkerResponse.Malformed
            if (code !in setOf("INVALID_SCOPE", "INVALID_REQUEST", "PARSE_FAILED")) {
                return DecodedWorkerResponse.Malformed
            }
            if (message.length !in 1..160) return DecodedWorkerResponse.Malformed
            DecodedWorkerResponse.Error(code, message)
        }
        else -> DecodedWorkerResponse.Malformed
    }
}

internal fun isDedicatedWorkerScope(scope: dynamic): Boolean =
    js("typeof DedicatedWorkerGlobalScope !== 'undefined'").unsafeCast<Boolean>() &&
        js("scope instanceof DedicatedWorkerGlobalScope").unsafeCast<Boolean>() &&
        scope.document == null

private fun isPlainWireObject(value: dynamic): Boolean =
    value != null && js("Object.prototype.toString.call(value) === '[object Object]'").unsafeCast<Boolean>()

private fun safeWireInt(value: dynamic): Int? {
    if (jsTypeOf(value) != "number") return null
    val number = value.unsafeCast<Double>()
    if (!number.isFinite() || number < 0.0 || number > Int.MAX_VALUE || number % 1.0 != 0.0) return null
    return number.toInt()
}

private fun safeWireDouble(value: dynamic): Double? {
    if (jsTypeOf(value) != "number") return null
    return value.unsafeCast<Double>().takeIf { it.isFinite() && it >= 0.0 }
}

@JsName("Worker")
private external class NativeWorker(scriptUrl: String) {
    var onmessage: ((dynamic) -> Unit)?
    var onerror: ((dynamic) -> Unit)?
    var onmessageerror: ((dynamic) -> Unit)?
    fun postMessage(message: dynamic, transfer: Array<dynamic>)
    fun terminate()
}

internal class BrowserParserWorkerPort(scriptUrl: String = PARSER_WORKER_URL) : ParserWorkerPort {
    private val worker = NativeWorker(scriptUrl)

    override fun setHandlers(
        onMessage: (dynamic) -> Unit,
        onError: () -> Unit,
        onMessageError: () -> Unit,
    ) {
        worker.onmessage = { event -> onMessage(event.data) }
        worker.onerror = { onError() }
        worker.onmessageerror = { onMessageError() }
    }

    override fun clearHandlers() {
        worker.onmessage = null
        worker.onerror = null
        worker.onmessageerror = null
    }

    override fun postParse(request: dynamic, buffer: ArrayBuffer) {
        worker.postMessage(request, arrayOf(buffer))
    }

    override fun terminate() {
        worker.terminate()
    }
}

internal val browserParserWorkerFactory = ParserWorkerFactory { BrowserParserWorkerPort() }

internal val browserParserWorkerScheduler = ParserWorkerScheduler { delayMs, callback ->
    val timerId = window.setTimeout(callback, delayMs)
    object : ParserWorkerTimer {
        override fun cancel() {
            window.clearTimeout(timerId)
        }
    }
}
