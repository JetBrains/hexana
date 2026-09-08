package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.shared.serializeMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.indexOf
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

private const val STDIO_READ_BUFFER_SIZE = 8192L
private const val STDIO_READ_CHANNEL_CAPACITY = 128
private const val STDIO_WRITE_CHANNEL_CAPACITY = 1024
internal const val HEXANA_STDIO_MAX_MESSAGE_BYTES = 128L * 1024L * 1024L
internal const val HEXANA_STDIO_MESSAGE_LIMIT_ERROR_MESSAGE =
    "Hexana stdio MCP transport message exceeded maximum size of 134217728 bytes."

/**
 * Local stdio transport equivalent to the SDK transport.
 *
 * GraalVM CE 21.0.2 crashes while parsing the SDK transport's nested suspend
 * `processReadBuffer` loop, so this keeps the same protocol behavior with the
 * message read and delivery branches split into simpler methods.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class HexanaStdioServerTransport(
    private val inputStream: Source,
    private val outputStream: RawSink,
    private val decodeMessage: (String) -> JSONRPCMessage = {
        McpJson.decodeFromString(JSONRPCMessage.serializer(), it)
    },
) : AbstractTransport() {

    private val logger = KotlinLogging.logger {}
    private val readBuffer = Buffer()
    private val fatalErrorReported = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var lifecycleState = StdioTransportLifecycleState.NEW
    private var readingJob: Job? = null
    private var sendingJob: Job? = null
    private var processingJob: Job? = null
    private var bufferedLineBytes = 0L
    private var pendingCarriageReturn = false

    private val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()
    private val scope = CoroutineScope(coroutineContext)
    private val readChannel = Channel<StdioReadChunk>(STDIO_READ_CHANNEL_CAPACITY)
    private val writeChannel = Channel<JSONRPCMessage>(STDIO_WRITE_CHANNEL_CAPACITY)
    private val outputSink = outputStream.buffered()

    override suspend fun start() {
        markRunningForStart()

        try {
            processingJob = launchProcessingJob()
            sendingJob = launchSendingJob()
            readingJob = launchReadingJob()
        } catch (error: Throwable) {
            markClosedAfterStartFailure()
            throw error
        }
    }

    private fun markRunningForStart() {
        synchronized(lifecycleLock) {
            if (lifecycleState != StdioTransportLifecycleState.NEW) {
                error("HexanaStdioServerTransport already started!")
            }
            lifecycleState = StdioTransportLifecycleState.RUNNING
        }
    }

    private fun markClosedAfterStartFailure() {
        synchronized(lifecycleLock) {
            lifecycleState = StdioTransportLifecycleState.CLOSED
        }
    }

    private fun beginEofDrain(): Boolean =
        synchronized(lifecycleLock) {
            if (lifecycleState != StdioTransportLifecycleState.RUNNING) return@synchronized false
            lifecycleState = StdioTransportLifecycleState.DRAINING_INPUT_EOF
            true
        }

    private fun eofDrainStillActive(): Boolean =
        synchronized(lifecycleLock) {
            lifecycleState == StdioTransportLifecycleState.DRAINING_INPUT_EOF
        }

    private fun completeEofDrain(): Boolean =
        synchronized(lifecycleLock) {
            if (lifecycleState != StdioTransportLifecycleState.DRAINING_INPUT_EOF) return@synchronized false
            lifecycleState = StdioTransportLifecycleState.CLOSED
            true
        }

    private fun beginForcedClose(): Boolean =
        synchronized(lifecycleLock) {
            when (lifecycleState) {
                StdioTransportLifecycleState.NEW,
                StdioTransportLifecycleState.CLOSING,
                StdioTransportLifecycleState.CLOSED -> false

                StdioTransportLifecycleState.RUNNING,
                StdioTransportLifecycleState.DRAINING_INPUT_EOF -> {
                    lifecycleState = StdioTransportLifecycleState.CLOSING
                    true
                }
            }
        }

    private fun completeForcedClose() {
        synchronized(lifecycleLock) {
            lifecycleState = StdioTransportLifecycleState.CLOSED
        }
    }

    private fun launchReadingJob(): Job {
        val job = scope.launch {
            val buffer = Buffer()
            try {
                while (isActive) {
                    val bytesRead = inputStream.readAtMostTo(buffer, STDIO_READ_BUFFER_SIZE)
                    if (bytesRead == -1L) break
                    if (bytesRead > 0) {
                        val chunk = buffer.readByteArray()
                        val containsLineBreak = checkMessageSize(chunk)
                        readChannel.send(StdioReadChunk(bytes = chunk, containsLineBreak = containsLineBreak))
                    }
                }
                countPendingCarriageReturn()
                drainAfterInputEof()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failTransport(error)
            }
        }
        job.invokeOnCompletion { cause ->
            logJobCompletion("Message reading", cause)
        }
        return job
    }

    private suspend fun drainAfterInputEof() {
        if (!beginEofDrain()) return

        readChannel.close()
        processingJob?.join()
        if (!eofDrainStillActive()) return

        readBuffer.clear()
        writeChannel.close()
        sendingJob?.join()
        if (!eofDrainStillActive()) return

        runCatching {
            outputSink.flush()
            outputSink.close()
        }.onFailure { error ->
            logger.warn(error) { "Failed to close stdout after stdin EOF" }
        }

        if (completeEofDrain()) {
            invokeOnCloseCallback()
        }
    }

    private fun launchProcessingJob(): Job {
        val job = scope.launch {
            try {
                for (chunk in readChannel) {
                    readBuffer.write(chunk.bytes)
                    if (chunk.containsLineBreak) {
                        processBufferedMessages()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failTransport(error)
            }
        }
        job.invokeOnCompletion { cause ->
            logJobCompletion("Processing", cause)
        }
        return job
    }

    private fun launchSendingJob(): Job {
        val job = scope.launch {
            try {
                for (message in writeChannel) {
                    val json = serializeMessage(message)
                    outputSink.writeString(json)
                    outputSink.flush()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failTransport(error)
            }
        }
        job.invokeOnCompletion { cause ->
            logJobCompletion("Message sending", cause)
            if (cause is CancellationException) {
                readingJob?.cancel(cause)
            }
        }
        return job
    }

    private fun checkMessageSize(chunk: ByteArray): Boolean {
        var containsLineBreak = false
        for (byte in chunk) {
            when (byte) {
                '\r'.code.toByte() -> {
                    countPendingCarriageReturn()
                    pendingCarriageReturn = true
                }

                '\n'.code.toByte() -> {
                    containsLineBreak = true
                    pendingCarriageReturn = false
                    bufferedLineBytes = 0L
                }

                else -> {
                    countPendingCarriageReturn()
                    countMessageByte()
                }
            }
        }
        return containsLineBreak
    }

    private fun countPendingCarriageReturn() {
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false
            countMessageByte()
        }
    }

    private fun countMessageByte() {
        bufferedLineBytes += 1
        if (bufferedLineBytes > HEXANA_STDIO_MAX_MESSAGE_BYTES) {
            throw HexanaStdioTransportMessageLimitException()
        }
    }

    private suspend fun processBufferedMessages() {
        while (true) {
            val message = readNextMessageOrReportError() ?: return
            deliverMessage(message)
        }
    }

    private fun readNextMessageOrReportError(): JSONRPCMessage? {
        while (true) {
            val lfIndex = if (readBuffer.exhausted()) -1L else readBuffer.indexOf('\n'.code.toByte())
            if (lfIndex == -1L) return null
            var messageLength = lfIndex
            var terminatorBytes = 1L
            if (messageLength > 0 && readBuffer[messageLength - 1] == '\r'.code.toByte()) {
                messageLength -= 1
                terminatorBytes += 1
            }
            val line = readBuffer.readString(messageLength)
            readBuffer.skip(terminatorBytes)
            if (line.isBlank()) continue
            return try {
                decodeMessage(line)
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is LinkageError) throw error
                failTransport(HexanaMalformedStdioMessageException(error))
                null
            }
        }
    }

    private suspend fun deliverMessage(message: JSONRPCMessage) {
        try {
            _onMessage.invoke(message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failTransport(error)
        }
    }

    private fun failTransport(error: Throwable) {
        if (fatalErrorReported.compareAndSet(expectedValue = false, newValue = true)) {
            _onError.invoke(error)
        }
        scope.launch {
            closeOutputSinkAfterFailure()
        }
        scope.launch {
            close()
        }
    }

    private fun closeOutputSinkAfterFailure() {
        runCatching {
            outputStream.close()
        }.onFailure { logger.warn(it) { "Failed to close stdout after fatal transport error" } }
    }

    private fun logJobCompletion(jobName: String, cause: Throwable?) {
        when (cause) {
            is CancellationException -> {
            }

            null -> {
                logger.debug { "$jobName job completed" }
            }

            else -> {
                logger.debug(cause) { "$jobName job completed exceptionally" }
            }
        }
    }

    override suspend fun close() {
        if (!beginForcedClose()) return

        withContext(NonCancellable) {
            writeChannel.close()
            sendingJob?.cancelAndJoin()

            runCatching {
                inputStream.close()
            }.onFailure { logger.warn(it) { "Failed to close stdin" } }

            readingJob?.cancel()
            readChannel.close()

            processingJob?.cancelAndJoin()

            readBuffer.clear()

            runCatching {
                outputSink.flush()
                outputSink.close()
            }.onFailure { logger.warn(it) { "Failed to close stdout" } }

            completeForcedClose()
            invokeOnCloseCallback()
        }
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        val sendResult = writeChannel.trySend(message)
        if (sendResult.isSuccess) return
        if (sendResult.isClosed) {
            throw sendResult.exceptionOrNull() ?: CancellationException("Hexana stdio MCP transport is closed.")
        }

        val error = HexanaStdioTransportQueueOverflowException(
            "Hexana stdio MCP transport write queue exceeded capacity of $STDIO_WRITE_CHANNEL_CAPACITY messages."
        )
        failTransport(error)
        throw error
    }
}

private class HexanaStdioTransportMessageLimitException : IllegalStateException(HEXANA_STDIO_MESSAGE_LIMIT_ERROR_MESSAGE)

private class HexanaStdioTransportQueueOverflowException(message: String) : IllegalStateException(message)

private class HexanaMalformedStdioMessageException(cause: Throwable) :
    IllegalArgumentException("Malformed JSON-RPC message received on stdio.", cause)

private enum class StdioTransportLifecycleState {
    NEW,
    RUNNING,
    DRAINING_INPUT_EOF,
    CLOSING,
    CLOSED,
}

private data class StdioReadChunk(val bytes: ByteArray, val containsLineBreak: Boolean)
