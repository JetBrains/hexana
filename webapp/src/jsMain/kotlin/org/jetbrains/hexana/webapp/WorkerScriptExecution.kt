package org.jetbrains.hexana.webapp

import org.jetbrains.hexana.vscode.ScriptExecution
import org.jetbrains.hexana.vscode.ScriptExecutionHandle
import org.khronos.webgl.ArrayBuffer

private const val SCRIPT_WORKER_PROTOCOL_VERSION = 1
private const val MAX_SCRIPT_SOURCE_CHARS = 256 * 1024
private const val MAX_SCRIPT_OUTPUT_LINES = 1_024
private const val MAX_SCRIPT_OUTPUT_LINE_CHARS = 4_096
private const val MAX_SCRIPT_ERROR_CHARS = 1_024

/**
 * Runs scripts in a fresh dedicated Worker using the typed provider constructed there.
 * The UI keeps its deliberately generic display provider and never receives a typed one.
 */
internal class WorkerScriptExecution(
    private val bytes: ByteArray,
    private val fileName: String,
    private val workerFactory: WorkerScriptWorkerFactory = browserWorkerScriptWorkerFactory,
) : ScriptExecution {
    private var nextRequest = 0

    override fun start(
        scriptContent: String,
        onLine: (String) -> Unit,
        onComplete: (String?) -> Unit,
    ): ScriptExecutionHandle {
        if (scriptContent.length > MAX_SCRIPT_SOURCE_CHARS) {
            onComplete("Script source exceeds the browser execution limit.")
            return NoopWorkerScriptExecutionHandle
        }
        val requestId = "script-worker-${nextRequest++}"
        val worker = try {
            workerFactory.create()
        } catch (_: Throwable) {
            onComplete("The script worker could not start.")
            return NoopWorkerScriptExecutionHandle
        }
        val active = ActiveWorkerScriptExecution(worker, requestId, onLine, onComplete)
        worker.setHandlers(active::onMessage, active::onFailure, active::onFailure)
        val request = js("({})")
        request.protocolVersion = SCRIPT_WORKER_PROTOCOL_VERSION
        request.kind = "script"
        request.requestId = requestId
        request.fileName = fileName
        request.script = scriptContent
        val buffer = copyForParserWorker(bytes)
        request.buffer = buffer
        try {
            worker.post(request, buffer)
        } catch (_: Throwable) {
            active.fail("The script worker request could not be sent.")
        }
        return active
    }

    private class ActiveWorkerScriptExecution(
        private val worker: WorkerScriptWorkerPort,
        private val requestId: String,
        private val onLine: (String) -> Unit,
        private val onComplete: (String?) -> Unit,
    ) : ScriptExecutionHandle {
        private var active = true
        private var receivedLines = 0

        fun onMessage(message: dynamic) {
            if (!active || !isPlainScriptWorkerMessage(message) || message.protocolVersion != SCRIPT_WORKER_PROTOCOL_VERSION ||
                message.requestId != requestId
            ) return fail("The script worker response was invalid.")
            when (message.kind as? String) {
                "script_line" -> {
                    val line = message.line as? String
                    if (line == null || line.length > MAX_SCRIPT_OUTPUT_LINE_CHARS || ++receivedLines > MAX_SCRIPT_OUTPUT_LINES) {
                        fail("The script worker response exceeded its output limit.")
                    } else {
                        onLine(line)
                    }
                }
                "script_done" -> {
                    val error = message.error
                    if (error != null && (error !is String || error.length > MAX_SCRIPT_ERROR_CHARS)) {
                        fail("The script worker response was invalid.")
                    } else {
                        complete(error as String?)
                    }
                }
                else -> fail("The script worker response was invalid.")
            }
        }

        fun onFailure() = fail("The script worker stopped unexpectedly.")

        fun fail(message: String) = complete(message)

        private fun complete(error: String?) {
            if (!active) return
            active = false
            worker.clearHandlers()
            worker.terminate()
            onComplete(error)
        }

        override fun cancel() {
            if (!active) return
            active = false
            worker.clearHandlers()
            worker.terminate()
        }
    }
}

private object NoopWorkerScriptExecutionHandle : ScriptExecutionHandle {
    override fun cancel() = Unit
}

private fun isPlainScriptWorkerMessage(value: dynamic): Boolean =
    value != null && js("Object.prototype.toString.call(value) === '[object Object]'").unsafeCast<Boolean>()

internal interface WorkerScriptWorkerPort {
    fun setHandlers(onMessage: (dynamic) -> Unit, onError: () -> Unit, onMessageError: () -> Unit)
    fun clearHandlers()
    fun post(request: dynamic, buffer: ArrayBuffer)
    fun terminate()
}

internal fun interface WorkerScriptWorkerFactory {
    fun create(): WorkerScriptWorkerPort
}

@JsName("Worker")
private external class NativeScriptWorker(scriptUrl: String) {
    var onmessage: ((dynamic) -> Unit)?
    var onerror: ((dynamic) -> Unit)?
    var onmessageerror: ((dynamic) -> Unit)?
    fun postMessage(message: dynamic, transfer: Array<dynamic>)
    fun terminate()
}

private class BrowserWorkerScriptWorkerPort : WorkerScriptWorkerPort {
    private val worker = NativeScriptWorker(PARSER_WORKER_URL)

    override fun setHandlers(onMessage: (dynamic) -> Unit, onError: () -> Unit, onMessageError: () -> Unit) {
        worker.onmessage = { event -> onMessage(event.data) }
        worker.onerror = { onError() }
        worker.onmessageerror = { onMessageError() }
    }

    override fun clearHandlers() {
        worker.onmessage = null
        worker.onerror = null
        worker.onmessageerror = null
    }

    override fun post(request: dynamic, buffer: ArrayBuffer) {
        worker.postMessage(request, arrayOf(buffer))
    }

    override fun terminate() = worker.terminate()
}

internal val browserWorkerScriptWorkerFactory = WorkerScriptWorkerFactory { BrowserWorkerScriptWorkerPort() }
