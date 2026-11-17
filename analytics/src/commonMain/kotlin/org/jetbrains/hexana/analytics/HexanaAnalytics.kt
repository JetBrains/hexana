package org.jetbrains.hexana.analytics

import kotlin.concurrent.Volatile

object HexanaAnalytics {
    @Volatile
    private var _client: AnalyticsClient = NoOpAnalyticsClient

    val client: AnalyticsClient get() = _client

    fun initialize(
        config: AnalyticsConfig,
        consentProvider: ConsentProvider,
        distinctIdProvider: DistinctIdProvider,
        globalProperties: Map<String, Any> = emptyMap()
    ) {
        _client = if (config.isEnabled) {
            createPlatformClient(config, consentProvider, distinctIdProvider, globalProperties)
        } else {
            NoOpAnalyticsClient
        }
    }

    fun shutdown() {
        _client.shutdown()
        _client = NoOpAnalyticsClient
    }
}

internal expect fun createPlatformClient(
    config: AnalyticsConfig,
    consentProvider: ConsentProvider,
    distinctIdProvider: DistinctIdProvider,
    globalProperties: Map<String, Any>
): AnalyticsClient

internal object NoOpAnalyticsClient : AnalyticsClient {
    override fun capture(event: HexanaEvent) = Unit
    override fun flush() = Unit
    override fun shutdown() = Unit
}
