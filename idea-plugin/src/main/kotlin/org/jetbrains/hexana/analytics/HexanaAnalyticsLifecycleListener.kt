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
 * Flushes and then tears down the analytics client, waiting at most
 * [timeoutMillis] for delivery.
 *
 * Order matters: PostHog's `close()` stops the queue timer and drops whatever
 * is still queued, so shutting down without flushing first silently discards
 * events.
 *
 * Cheap when analytics never started — [HexanaAnalytics.client] is a no-op
 * client until the first successful initialization, and even after that
 * [PostHogJvmClient] holds no SDK instance until a capture that consent
 * allowed. In both cases the worker finishes immediately.
 *
 * Callers must wait rather than fire and forget: the worker ends by resetting
 * [HexanaAnalytics.client], so letting it run loose alongside a concurrent
 * re-initialization would tear down the *replacement* client.
 *
 * Waiting means this blocks for up to [timeoutMillis]. That is fine on the
 * EDT while the IDE is exiting — the only thing it can delay is the quit — but
 * not on any EDT path where the IDE keeps running.
 */
internal fun flushAndShutdownAnalytics(timeoutMillis: Long = ANALYTICS_SHUTDOWN_FLUSH_MILLIS) {
    val worker = Thread({
        try {
            HexanaAnalytics.client.flush()
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is LinkageError) {
                throw error
            }
            HexanaLog.LOG.warn("Failed to flush Hexana analytics before shutdown", error)
        } finally {
            HexanaAnalytics.shutdown()
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
}
