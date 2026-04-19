package org.jetbrains.hexana.analytics

@JsModule("posthog-node")
@JsNonModule
external class PostHogJs(apiKey: String, options: dynamic = definedExternally) {
    fun capture(params: dynamic)
    fun flush()
    fun shutdown()
}

internal class PostHogJsClient(
    config: AnalyticsConfig,
    private val consentProvider: ConsentProvider,
    private val distinctIdProvider: DistinctIdProvider
) : AnalyticsClient {

    private val client = PostHogJs(
        config.apiKey,
        js("({host: config.host})")
    )

    override fun capture(event: HexanaEvent) {
        if (!consentProvider.isAnalyticsAllowed()) return
        val params = js("{}")
        params.distinctId = distinctIdProvider.distinctId()
        params.event = event.name
        val props = js("{}")
        for ((key, value) in event.properties) {
            props[key] = value
        }
        params.properties = props
        client.capture(params)
    }

    override fun flush() {
        client.flush()
    }

    override fun shutdown() {
        client.shutdown()
    }
}

internal actual fun createPlatformClient(
    config: AnalyticsConfig,
    consentProvider: ConsentProvider,
    distinctIdProvider: DistinctIdProvider
): AnalyticsClient = PostHogJsClient(config, consentProvider, distinctIdProvider)
