package org.jetbrains.hexana.analytics

import com.posthog.server.PostHog
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * The underlying PostHog SDK instance is created lazily, on the first capture
 * that consent actually allows — never at construction time. `PostHog.with`
 * spins up an executor and a non-daemon `java.util.Timer` that wakes every
 * `flushIntervalSeconds` for the life of the process, so building it eagerly
 * costs an always-on thread for users who opted out and would never have sent
 * anything.
 *
 * A consequence worth relying on: flipping consent from denied to allowed needs
 * no re-initialization, because the next allowed capture creates the client.
 */
internal class PostHogJvmClient(
    private val config: AnalyticsConfig,
    private val consentProvider: ConsentProvider,
    private val distinctIdProvider: DistinctIdProvider,
    private val globalProperties: Map<String, Any>
) : AnalyticsClient {

    private val posthog = AtomicReference<PostHogInterface?>(null)
    private val creationLock = Any()

    /**
     * Set by [shutdown], and never cleared: retirement is terminal.
     *
     * A capture that passed the consent check just before a concurrent
     * [shutdown] would otherwise reach [postHogOrCreate] afterwards and
     * allocate a *fresh* SDK on a client nothing points at any more — one with
     * a live flush timer that nobody will ever close, since
     * [HexanaAnalytics.initialize] builds a new [PostHogJvmClient] rather than
     * reviving this one. Every consent toggle could leak such an instance.
     */
    @Volatile
    private var retired = false

    override fun capture(event: HexanaEvent) {
        if (!consentProvider.isAnalyticsAllowed()) return
        val mergedProperties = globalProperties + event.properties
        postHogOrCreate()?.capture(
            distinctIdProvider.distinctId(),
            event.name,
            mergedProperties
        )
    }

    /**
     * Flushes only an already-created client. Must never create one: a flush on
     * a client that never captured anything would allocate the SDK just to send
     * an empty batch — and on the IDE shutdown path it would do so for users
     * who opted out.
     */
    override fun flush() {
        posthog.get()?.flush()
    }

    /**
     * Note that `close()` does *not* flush — it stops the queue timer and drops
     * whatever is still queued. Callers that care about pending events must
     * [flush] first.
     *
     * Terminal: this client captures nothing afterwards, so a capture racing
     * the teardown cannot resurrect the SDK behind it.
     */
    override fun shutdown() {
        synchronized(creationLock) {
            retired = true
            posthog.getAndSet(null)?.close()
        }
    }

    /**
     * Whether the underlying SDK has actually been allocated. Exists so tests
     * can assert the "nothing is built until a consented capture" contract,
     * which is otherwise invisible from the outside.
     */
    internal fun hasSdkClient(): Boolean = posthog.get() != null

    /** Returns `null` once [shutdown] has retired this client. */
    private fun postHogOrCreate(): PostHogInterface? {
        posthog.get()?.let { return it }
        synchronized(creationLock) {
            posthog.get()?.let { return it }
            if (retired) return null
            val created = PostHog.with(
                PostHogConfig(apiKey = config.apiKey, host = config.resolvedHost()).apply {
                    flushAt = config.resolvedFlushAt()
                    flushIntervalSeconds = config.flushIntervalSeconds()
                    // Hexana uses neither feature flags nor experiments. Both
                    // default to true and only add runtime surface for us.
                    preloadFeatureFlags = false
                    sendFeatureFlagEvent = false
                }
            )
            posthog.set(created)
            return created
        }
    }
}

internal actual fun createPlatformClient(
    config: AnalyticsConfig,
    consentProvider: ConsentProvider,
    distinctIdProvider: DistinctIdProvider,
    globalProperties: Map<String, Any>
): AnalyticsClient = PostHogJvmClient(config, consentProvider, distinctIdProvider, globalProperties)

private fun AnalyticsConfig.resolvedHost(): String {
    if (host != AnalyticsConfig.DEFAULT_HOST) {
        return host
    }
    return System.getProperty(AnalyticsConfig.HOST_SYSTEM_PROPERTY).nonBlank()
        ?: System.getenv(AnalyticsConfig.HOST_ENV_VAR).nonBlank()
        ?: host
}

private fun AnalyticsConfig.flushIntervalSeconds(): Int {
    return (resolvedFlushIntervalMs() / 1_000L)
        .coerceAtLeast(1L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

private fun AnalyticsConfig.resolvedFlushAt(): Int {
    if (flushAt != AnalyticsConfig.DEFAULT_FLUSH_AT) {
        return flushAt.coerceAtLeast(1)
    }
    return System.getProperty(AnalyticsConfig.FLUSH_AT_SYSTEM_PROPERTY).positiveInt()
        ?: System.getenv(AnalyticsConfig.FLUSH_AT_ENV_VAR).positiveInt()
        ?: flushAt.coerceAtLeast(1)
}

private fun AnalyticsConfig.resolvedFlushIntervalMs(): Long {
    if (flushIntervalMs != AnalyticsConfig.DEFAULT_FLUSH_INTERVAL_MS) {
        return flushIntervalMs.coerceAtLeast(1L)
    }
    return System.getProperty(AnalyticsConfig.FLUSH_INTERVAL_MS_SYSTEM_PROPERTY).positiveLong()
        ?: System.getenv(AnalyticsConfig.FLUSH_INTERVAL_MS_ENV_VAR).positiveLong()
        ?: flushIntervalMs.coerceAtLeast(1L)
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.positiveInt(): Int? = nonBlank()
    ?.toIntOrNull()
    ?.takeIf { it > 0 }

private fun String?.positiveLong(): Long? = nonBlank()
    ?.toLongOrNull()
    ?.takeIf { it > 0L }
