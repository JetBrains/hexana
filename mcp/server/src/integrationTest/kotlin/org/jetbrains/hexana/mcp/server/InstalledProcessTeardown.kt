package org.jetbrains.hexana.mcp.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.io.Closeable
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

private const val PROCESS_SHUTDOWN_TIMEOUT_SECONDS = 5L
private const val PROCESS_FORCE_TIMEOUT_SECONDS = 5L

internal suspend fun stopInstalledProcessTree(
    process: Process,
    logger: Logger,
    label: String,
) {
    withContext(NonCancellable + Dispatchers.IO) {
        if (!process.isAlive) {
            return@withContext
        }

        val initialHandles = process.processTreeHandles()
        logger.info(
            "Stopping {} process tree pid={} descendants={}",
            label,
            process.pid(),
            initialHandles.size - 1
        )
        initialHandles.destroyAlive(force = false)
        if (process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            val aliveDescendants = initialHandles.filter { it.isAlive }
            if (aliveDescendants.isEmpty()) {
                return@withContext
            }
            logger.warn(
                "{} root process exited, but descendants are still alive; forcing aliveProcesses={}",
                label,
                aliveDescendants.describeAlive()
            )
            aliveDescendants.destroyAlive(force = true)
            if (!aliveDescendants.waitForExit(PROCESS_FORCE_TIMEOUT_SECONDS)) {
                logger.error(
                    "{} descendants are still alive {}s after destroyForcibly(); aliveProcesses={}",
                    label,
                    PROCESS_FORCE_TIMEOUT_SECONDS,
                    aliveDescendants.describeAlive()
                )
            }
            return@withContext
        }

        logger.warn(
            "{} process tree did not exit within {}s after destroy(); forcing pid={} aliveProcesses={}",
            label,
            PROCESS_SHUTDOWN_TIMEOUT_SECONDS,
            process.pid(),
            initialHandles.describeAlive()
        )
        val forcedHandles = process.processTreeHandles()
        forcedHandles.destroyAlive(force = true)
        val rootExited = process.waitFor(PROCESS_FORCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val descendantsExited = forcedHandles.waitForExit(PROCESS_FORCE_TIMEOUT_SECONDS)
        if (!rootExited || !descendantsExited) {
            logger.error(
                "{} process tree is still alive {}s after destroyForcibly(); pid={} aliveProcesses={}",
                label,
                PROCESS_FORCE_TIMEOUT_SECONDS,
                process.pid(),
                process.processTreeHandles().describeAlive()
            )
        }
    }
}

internal fun closeInstalledProcessStreams(process: Process, logger: Logger, label: String) {
    process.inputStream.closeQuietly(logger, "$label stdout")
    process.errorStream.closeQuietly(logger, "$label stderr")
    process.outputStream.closeQuietly(logger, "$label stdin")
}

internal fun Process.exitCodeOrNull(): Int? {
    return if (isAlive) null else exitValue()
}

private fun Process.processTreeHandles(): List<ProcessHandle> {
    val root = toHandle()
    val descendants = root.descendants().iterator().asSequence().toList()
    return descendants + root
}

private fun List<ProcessHandle>.destroyAlive(force: Boolean) {
    forEach { handle ->
        if (handle.isAlive) {
            if (force) {
                handle.destroyForcibly()
            } else {
                handle.destroy()
            }
        }
    }
}

private fun List<ProcessHandle>.describeAlive(): String {
    return filter { it.isAlive }
        .joinToString(prefix = "[", postfix = "]") { handle ->
            "pid=${handle.pid()}"
        }
}

private fun List<ProcessHandle>.waitForExit(timeoutSeconds: Long): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    forEach { handle ->
        if (!handle.isAlive) {
            return@forEach
        }

        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0) {
            return false
        }

        try {
            handle.onExit().get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            return false
        }
    }
    return none { it.isAlive }
}

private fun Closeable.closeQuietly(logger: Logger, label: String) {
    runCatching { close() }
        .onFailure { logger.warn("Failed to close {}", label, it) }
}
