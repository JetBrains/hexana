package org.jetbrains.hexana.analytics

import kotlin.concurrent.Volatile

object HexanaAnalytics {
    @Volatile
    private var _client: AnalyticsClient = NoOpAnalyticsClient

    val client: AnalyticsClient get() = _client

    fun initialize(
        config: AnalyticsConfig,
        consentProvider: ConsentProvider,
        distinctIdProvider: DistinctIdProvider
    ) {
        _client = createPlatformClient(config, consentProvider, distinctIdProvider)
    }

    fun shutdown() {
        _client.shutdown()
        _client = NoOpAnalyticsClient
    }
}

internal expect fun createPlatformClient(
    config: AnalyticsConfig,
    consentProvider: ConsentProvider,
    distinctIdProvider: DistinctIdProvider
): AnalyticsClient

internal object NoOpAnalyticsClient : AnalyticsClient {
    override fun capture(event: HexanaEvent) = Unit
    override fun flush() = Unit
    override fun shutdown() = Unit
}
