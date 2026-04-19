package org.jetbrains.hexana.analytics

import com.posthog.server.PostHog
import com.posthog.server.PostHogConfig

internal class PostHogJvmClient(
    config: AnalyticsConfig,
    private val consentProvider: ConsentProvider,
    private val distinctIdProvider: DistinctIdProvider
) : AnalyticsClient {

    private val posthog = PostHog.with(
        PostHogConfig(apiKey = config.apiKey, host = config.host)
    )

    override fun capture(event: HexanaEvent) {
        if (!consentProvider.isAnalyticsAllowed()) return
        posthog.capture(
            distinctIdProvider.distinctId(),
            event.name,
            event.properties
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
    distinctIdProvider: DistinctIdProvider
): AnalyticsClient = PostHogJvmClient(config, consentProvider, distinctIdProvider)
