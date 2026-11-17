package org.jetbrains.hexana.analytics

/**
 * Thread-safe, non-blocking analytics client.
 * On JVM: events are enqueued and dispatched on a background thread (never EDT).
 * On JS: events are dispatched asynchronously via Promises.
 * All methods silently no-op when consent is denied.
 */
interface AnalyticsClient {
    fun capture(event: HexanaEvent)
    fun flush()
    fun shutdown()
}
