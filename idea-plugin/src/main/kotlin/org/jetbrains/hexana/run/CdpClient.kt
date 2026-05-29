package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Chrome DevTools Protocol client that communicates over WebSocket.
 *
 * Supports sending CDP commands and receiving events. The client
 * manages request/response correlation via monotonically increasing
 * message IDs and dispatches events to registered listeners.
 */
class CdpClient(private val scope: CoroutineScope) {

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private var session: DefaultClientWebSocketSession? = null
    private val nextId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<Map<String, Any?>>>()
    private val eventListeners = ConcurrentHashMap<String, MutableList<(Map<String, Any?>) -> Unit>>()
    private val mutex = Mutex()

    /** Scripts reported by `Debugger.scriptParsed` events, keyed by scriptId. */
    val scripts = ConcurrentHashMap<String, ScriptInfo>()

    @Volatile
    var connected = false
        private set

    /** Callback invoked when the WebSocket connection is lost. */
    var onDisconnect: (() -> Unit)? = null

    data class ScriptInfo(
        val scriptId: String,
        val url: String,
        val sourceMapURL: String?,
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int,
    )

    /**
     * Connects to the CDP WebSocket endpoint at the given [wsUrl].
     * Starts a background coroutine that reads incoming messages
     * and dispatches them to pending requests or event listeners.
     */
    suspend fun connect(wsUrl: String) {
        val ws = client.webSocketSession(wsUrl)
        session = ws
        connected = true

        scope.launch {
            try {
                for (frame in ws.incoming) {
                    if (frame is Frame.Text) {
                        handleMessage(frame.readText())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("CDP WebSocket read error", e)
            } finally {
                connected = false
                onDisconnect?.invoke()
            }
        }
    }

    /**
     * Sends a CDP command and waits for the response.
     *
     * @param method the CDP method name (e.g. "Debugger.enable")
     * @param params optional parameters map
     * @param timeoutMs maximum time to wait for the response
     * @return the "result" object from the CDP response
     */
    suspend fun send(
        method: String,
        params: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 10_000,
    ): Map<String, Any?> {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<Map<String, Any?>>()
        pendingRequests[id] = deferred

        val message = buildJsonString {
            put("id", id)
            put("method", method)
            if (params.isNotEmpty()) {
                put("params", params)
            }
        }

        mutex.withLock {
            session?.send(Frame.Text(message))
                ?: throw IllegalStateException("CDP WebSocket not connected")
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingRequests.remove(id)
        }
    }

    /**
     * Sends a CDP command without waiting for a response.
     */
    suspend fun sendFireAndForget(method: String, params: Map<String, Any?> = emptyMap()) {
        val id = nextId.getAndIncrement()
        val message = buildJsonString {
            put("id", id)
            put("method", method)
            if (params.isNotEmpty()) {
                put("params", params)
            }
        }
        mutex.withLock {
            session?.send(Frame.Text(message))
        }
    }

    /**
     * Registers a listener for a specific CDP event method.
     */
    fun on(method: String, listener: (Map<String, Any?>) -> Unit) {
        eventListeners.getOrPut(method) { mutableListOf() }.add(listener)
    }

    /**
     * Waits for a specific CDP event to occur, with a timeout.
     */
    suspend fun waitForEvent(method: String, timeoutMs: Long = 30_000): Map<String, Any?> {
        val channel = Channel<Map<String, Any?>>(1)
        val listener: (Map<String, Any?>) -> Unit = { params ->
            channel.trySend(params)
        }
        on(method, listener)
        return try {
            withTimeout(timeoutMs) { channel.receive() }
        } finally {
            eventListeners[method]?.remove(listener)
        }
    }

    suspend fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        connected = false
        client.close()
        pendingRequests.values.forEach {
            it.completeExceptionally(CancellationException("CDP client closed"))
        }
        pendingRequests.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleMessage(text: String) {
        val json = parseJson(text) ?: return

        val id = (json["id"] as? Number)?.toInt()
        if (id != null) {
            // This is a response to a command
            val result = json["result"] as? Map<String, Any?> ?: emptyMap()
            val error = json["error"] as? Map<String, Any?>
            val deferred = pendingRequests.remove(id)
            if (error != null) {
                val message = error["message"] as? String ?: "Unknown CDP error"
                deferred?.completeExceptionally(CdpException(message))
            } else {
                deferred?.complete(result)
            }
            return
        }

        // This is an event
        val method = json["method"] as? String ?: return
        val params = json["params"] as? Map<String, Any?> ?: emptyMap()

        // Track parsed scripts
        if (method == "Debugger.scriptParsed") {
            val scriptId = params["scriptId"] as? String ?: return
            scripts[scriptId] = ScriptInfo(
                scriptId = scriptId,
                url = params["url"] as? String ?: "",
                sourceMapURL = params["sourceMapURL"] as? String,
                startLine = (params["startLine"] as? Number)?.toInt() ?: 0,
                startColumn = (params["startColumn"] as? Number)?.toInt() ?: 0,
                endLine = (params["endLine"] as? Number)?.toInt() ?: 0,
                endColumn = (params["endColumn"] as? Number)?.toInt() ?: 0,
            )
        }

        eventListeners[method]?.forEach { listener ->
            try {
                listener(params)
            } catch (e: Exception) {
                LOG.warn("CDP event listener error for $method", e)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CdpClient::class.java)
    }
}

class CdpException(message: String) : RuntimeException(message)

// ---- Minimal JSON helpers (no external dependency) ----

@Suppress("UNCHECKED_CAST")
private fun parseJson(text: String): Map<String, Any?>? {
    return try {
        com.google.gson.Gson().fromJson(text, Map::class.java) as? Map<String, Any?>
    } catch (e: Exception) {
        null
    }
}

private fun buildJsonString(block: MutableMap<String, Any?>.() -> Unit): String {
    val map = mutableMapOf<String, Any?>()
    map.block()
    return com.google.gson.Gson().toJson(map)
}
