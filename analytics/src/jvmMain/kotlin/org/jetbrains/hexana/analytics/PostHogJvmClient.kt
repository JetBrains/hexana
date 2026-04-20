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
        PostHogConfig(apiKey = config.apiKey, host = config.host)
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
