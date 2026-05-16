package org.jetbrains.hexana.analytics

import com.posthog.server.PostHog
import com.posthog.server.PostHogConfig

internal class PostHogJvmClient(
    config: AnalyticsConfig,
    private val consentProvider: ConsentProvider,
    private val distinctIdProvider: DistinctIdProvider,
    private val globalProperties: Map<String, Any>
) : AnalyticsClient {

    private val posthog = PostHog.with(
        PostHogConfig(apiKey = config.apiKey, host = config.resolvedHost()).apply {
            flushAt = config.resolvedFlushAt()
            flushIntervalSeconds = config.flushIntervalSeconds()
        }
    )

    override fun capture(event: HexanaEvent) {
        if (!consentProvider.isAnalyticsAllowed()) return
        val mergedProperties = globalProperties + event.properties
        posthog.capture(
            distinctIdProvider.distinctId(),
            event.name,
            mergedProperties
        )
    }

    override fun flush() {
        posthog.flush()
    }

    override fun shutdown() {
        posthog.close()
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
