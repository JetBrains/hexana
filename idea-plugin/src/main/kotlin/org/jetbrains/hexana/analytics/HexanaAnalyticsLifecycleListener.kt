package org.jetbrains.hexana.analytics

import com.intellij.ide.AppLifecycleListener
import org.jetbrains.hexana.HexanaLog

/**
 * How long the IDE may wait for the final analytics flush.
 *
 * The flush is a blocking OkHttp call and the SDK sets no call timeout, so its
 * connect/read/write defaults (10s each) could otherwise stall quit for ~30s.
 * Two seconds is imperceptible on exit and enough for one batch on a working
 * connection. Comparable to the CLI's `CLI_ANALYTICS_SHUTDOWN_FLUSH_MILLIS`.
 */
internal const val ANALYTICS_SHUTDOWN_FLUSH_MILLIS = 2_000L

/**
 * Delivers queued analytics before the IDE exits.
 *
 * Without this the plugin's most common workflow — open the IDE, inspect one
 * binary, quit — reports nothing: `flushAt` is 20, a typical session captures a
 * handful of events, and the only thing that would ever ship them is the SDK's
 * 30s timer.
 */
internal class HexanaAnalyticsLifecycleListener : AppLifecycleListener {
    // Fires after settings are saved and after the final "can exit?" check, so
    // the exit is certain and nothing else will capture after us.
    override fun appWillBeClosed(isRestart: Boolean) {
        flushAndShutdownAnalytics()
    }
}

/**
 * Flushes and then tears down the analytics client, giving delivery at most
 * [timeoutMillis].
 *
 * Order matters: PostHog's `close()` stops the queue timer and drops whatever
 * is still queued, so shutting down without flushing first silently discards
 * events.
 *
 * Only the *flush* runs on the worker, and only because it is a blocking
 * network call that has to be bounded — the SDK sets no call timeout. The
 * teardown runs here, on the calling thread, after the join, and that
 * placement is the point of the split: [HexanaAnalytics.shutdown] resets
 * [HexanaAnalytics.client], and a worker that outlived its timeout would
 * otherwise do that *after* a caller had already installed a replacement,
 * leaving the service reporting Ready while every capture went to the no-op
 * client. A slow flush now costs at most [timeoutMillis] and a lost batch,
 * never a silently dead session.
 *
 * Cheap when analytics never started — [HexanaAnalytics.client] is a no-op
 * client until the first successful initialization, and even after that
 * [PostHogJvmClient] holds no SDK instance until a capture that consent
 * allowed. In both cases the worker finishes immediately.
 *
 * Blocks for up to [timeoutMillis]. That is fine on the EDT while the IDE is
 * exiting — the only thing it can delay is the quit — but not on any EDT path
 * where the IDE keeps running.
 */
internal fun flushAndShutdownAnalytics(timeoutMillis: Long = ANALYTICS_SHUTDOWN_FLUSH_MILLIS) {
    // Captured once: the worker must flush the client this call is retiring,
    // not whatever `HexanaAnalytics.client` happens to point at by the time it
    // gets scheduled.
    val retiring = HexanaAnalytics.client
    val worker = Thread({
        try {
            retiring.flush()
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is LinkageError) {
                throw error
            }
            HexanaLog.LOG.warn("Failed to flush Hexana analytics before shutdown", error)
        }
    }, "hexana-analytics-shutdown-flush")
    // Daemon: a hung request must never be what keeps the JVM alive.
    worker.isDaemon = true
    worker.start()
    try {
        worker.join(timeoutMillis)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    // Unconditional, and before returning: the caller is entitled to assume the
    // old client is retired the moment this returns, whether the flush
    // finished, timed out, or threw.
    HexanaAnalytics.shutdown()
}
