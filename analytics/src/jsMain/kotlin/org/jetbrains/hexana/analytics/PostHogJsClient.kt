package org.jetbrains.hexana.analytics

@JsModule("posthog-node")
@JsNonModule
external object PostHogModule {
    @JsName("PostHog")
    class PostHog(apiKey: String, options: dynamic = definedExternally) {
        fun capture(params: dynamic)
        fun flush()
        fun shutdown()
    }
}

internal class PostHogJsClient(
    config: AnalyticsConfig,
    private val consentProvider: ConsentProvider,
    private val distinctIdProvider: DistinctIdProvider
) : AnalyticsClient {

    // Build the options object imperatively instead of using a js("({…})") literal
    // because Kotlin/JS `js()` blocks cannot reference Kotlin parameters after webpack
    // minification.  The resulting plain JS object is equivalent to `{ host: "…" }`.
    private val client: PostHogModule.PostHog = run {
        val options = js("{}")
        options.host = config.resolvedHost()
        options.flushAt = config.resolvedFlushAt()
        options.flushInterval = config.resolvedFlushIntervalMs().toDouble()
        PostHogModule.PostHog(config.apiKey, options)
    }

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
    distinctIdProvider: DistinctIdProvider,
    globalProperties: Map<String, Any>
): AnalyticsClient = PostHogJsClient(config, consentProvider, distinctIdProvider)

private fun AnalyticsConfig.resolvedHost(): String {
    if (host != AnalyticsConfig.DEFAULT_HOST) {
        return host
    }
    return processEnv(AnalyticsConfig.HOST_ENV_VAR) ?: host
}

private fun AnalyticsConfig.resolvedFlushAt(): Int {
    if (flushAt != AnalyticsConfig.DEFAULT_FLUSH_AT) {
        return flushAt.coerceAtLeast(1)
    }
    return processEnv(AnalyticsConfig.FLUSH_AT_ENV_VAR)?.toIntOrNull()?.takeIf { it > 0 }
        ?: flushAt.coerceAtLeast(1)
}

private fun AnalyticsConfig.resolvedFlushIntervalMs(): Long {
    if (flushIntervalMs != AnalyticsConfig.DEFAULT_FLUSH_INTERVAL_MS) {
        return flushIntervalMs.coerceAtLeast(1L)
    }
    return processEnv(AnalyticsConfig.FLUSH_INTERVAL_MS_ENV_VAR)?.toLongOrNull()?.takeIf { it > 0L }
        ?: flushIntervalMs.coerceAtLeast(1L)
}

private fun processEnv(name: String): String? {
    val env = js("typeof process !== 'undefined' && process.env ? process.env : null")
    if (env == null) return null
    val value = env[name]
    return (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
