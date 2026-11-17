package org.jetbrains.hexana.disassembly.llvmobjdump

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

data class NativeCommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val cancelled: Boolean,
)

sealed interface NativeCommandEvent {
    data class Stdout(val text: String) : NativeCommandEvent
    data class Stderr(val text: String) : NativeCommandEvent
    data class Exit(val exitCode: Int) : NativeCommandEvent
}

interface NativeCommandExecutor {
    fun events(
        executable: Path,
        arguments: List<String>,
    ): Flow<NativeCommandEvent>

    suspend fun execute(
        executable: Path,
        arguments: List<String>,
        timeoutInMilliseconds: Int,
    ): NativeCommandOutput {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode: Int? = null

        val completed = withTimeoutOrNull(timeoutInMilliseconds.toLong().milliseconds) {
            try {
                events(executable, arguments).collect { event ->
                    when (event) {
                        is NativeCommandEvent.Stdout -> stdout.append(event.text)
                        is NativeCommandEvent.Stderr -> stderr.append(event.text)
                        is NativeCommandEvent.Exit -> {
                            exitCode = event.exitCode
                            throw NativeCommandExited()
                        }
                    }
                }
            } catch (e: NativeCommandExited) {
                // Stop collecting when the process exit event arrives; stdout/stderr already received is preserved.
            }
            true
        }

        return NativeCommandOutput(
            exitCode = exitCode ?: -1,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = completed == null,
            cancelled = false,
        )
    }
}

object ProcessNativeCommandExecutor : NativeCommandExecutor {
    override fun events(
        executable: Path,
        arguments: List<String>,
    ): Flow<NativeCommandEvent> = channelFlow {
        val process = ProcessBuilder(listOf(executable.toString()) + arguments).start()
        val stdoutJob = launch(Dispatchers.IO) {
            process.inputStream.reader().use { reader ->
                reader.emitChunksTo(this@channelFlow, NativeCommandEvent::Stdout)
            }
        }
        val stderrJob = launch(Dispatchers.IO) {
            process.errorStream.reader().use { reader ->
                reader.emitChunksTo(this@channelFlow, NativeCommandEvent::Stderr)
            }
        }

        val exitJob = launch(Dispatchers.IO) {
            val exitCode = process.waitFor()
            val outputDrained = withTimeoutOrNull(PROCESS_OUTPUT_DRAIN_MS.milliseconds) {
                stdoutJob.join()
                stderrJob.join()
                true
            } == true
            if (!outputDrained) {
                process.closeOutputStreams()
                stdoutJob.cancel()
                stderrJob.cancel()
            }
            try {
                send(NativeCommandEvent.Exit(exitCode))
            } finally {
                close()
            }
        }

        awaitClose {
            process.terminateIfAlive()
            process.closeOutputStreams()
            stdoutJob.cancel()
            stderrJob.cancel()
            exitJob.cancel()
        }
    }
}

private suspend fun java.io.Reader.emitChunksTo(
    channel: SendChannel<NativeCommandEvent>,
    event: (String) -> NativeCommandEvent,
) {
    val buffer = CharArray(DEFAULT_BUFFER_SIZE)
    while (currentCoroutineContext().isActive) {
        val read = try {
            withContext(Dispatchers.IO) { read(buffer) }
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) break
            throw e
        } catch (e: CancellationException) {
            throw e
        }
        if (read < 0) break
        if (read > 0) {
            channel.send(event(String(buffer, 0, read)))
        }
    }
}

private fun Process.terminateIfAlive() {
    if (!isAlive) return
    try {
        destroy()
        if (!waitFor(PROCESS_GRACEFUL_SHUTDOWN_MS, TimeUnit.MILLISECONDS) && isAlive) {
            destroyForcibly()
            waitFor(PROCESS_FORCED_SHUTDOWN_MS, TimeUnit.MILLISECONDS)
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        if (isAlive) {
            destroyForcibly()
        }
    }
}

private fun Process.closeOutputStreams() {
    runCatching { inputStream.close() }
    runCatching { errorStream.close() }
}

private const val PROCESS_OUTPUT_DRAIN_MS = 5_000L
private const val PROCESS_GRACEFUL_SHUTDOWN_MS = 200L
private const val PROCESS_FORCED_SHUTDOWN_MS = 1_000L

private class NativeCommandExited : Throwable(null, null, false, false)
