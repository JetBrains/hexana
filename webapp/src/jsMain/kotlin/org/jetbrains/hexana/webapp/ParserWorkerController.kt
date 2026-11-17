package org.jetbrains.hexana.webapp

import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer

/** Same-origin public-distribution asset; Karma maps this URL to its test fixture. */
internal const val PARSER_WORKER_URL = "parser-worker.js"

/** Production lifecycle policy. Tests inject a scheduler and assert this value without waiting. */
internal const val PARSER_WORKER_TIMEOUT_MS = 240_000

internal sealed interface ParserWorkerState {
    data object Ready : ParserWorkerState
    data class Parsing(val requestId: String) : ParserWorkerState
    data class Parsed(val response: BoundedParserWorkerSuccess) : ParserWorkerState {
        val summary: ParserWorkerSummary get() = response.presentation.summary
        val presentation: ParserWorkerPresentation get() = response.presentation
        val partial: ParserWorkerPartial get() = response.partial
    }
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

    fun parse(buffer: ArrayBuffer, timeoutMs: Int, fileName: String? = null) {
        require(timeoutMs > 0)
        require(fileName == null || isValidParserWorkerFileName(fileName)) { "Invalid parser-worker file name." }
        // TODO(MCP-181): Remove when bounded parallel parsing with per-request ownership/isolation is implemented and browser-tested.
        invalidateAndTerminateActive()
        val requestGeneration = generation
        val requestId = "parser-worker-$requestGeneration"
        check(isValidParserWorkerRequestId(requestId)) { "Generated parser-worker request ID is invalid." }
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
        wireRequest.fileName = fileName
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
                onStateChanged(ParserWorkerState.Parsed(decoded.response))
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
    data class Success(val response: BoundedParserWorkerSuccess) : DecodedWorkerResponse
    data class Error(val code: String, val message: String) : DecodedWorkerResponse
    data object Malformed : DecodedWorkerResponse
}

internal fun decodeParserWorkerResponse(value: dynamic, requestId: String): DecodedWorkerResponse {
    return try {
        val wireBytes = parserWorkerResponseEnvelopeBytesOrNull(value) ?: return DecodedWorkerResponse.Malformed
        if (!isValidParserWorkerRequestId(requestId) || value.protocolVersion != PARSER_WORKER_PROTOCOL_VERSION) {
            return DecodedWorkerResponse.Malformed
        }
        val wireRequestId = value.requestId as? String ?: return DecodedWorkerResponse.Malformed
        if (!isValidParserWorkerRequestId(wireRequestId) || wireRequestId != requestId) return DecodedWorkerResponse.Malformed
        when (value.kind as? String) {
            "success" -> decodeParserWorkerSuccess(value, requestId, wireBytes)?.let(DecodedWorkerResponse::Success)
                ?: DecodedWorkerResponse.Malformed
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
    } catch (_: Throwable) {
        DecodedWorkerResponse.Malformed
    }
}

internal fun isDedicatedWorkerScope(scope: dynamic): Boolean =
    js("typeof DedicatedWorkerGlobalScope !== 'undefined'").unsafeCast<Boolean>() &&
        js("scope instanceof DedicatedWorkerGlobalScope").unsafeCast<Boolean>() &&
        scope.document == null


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
