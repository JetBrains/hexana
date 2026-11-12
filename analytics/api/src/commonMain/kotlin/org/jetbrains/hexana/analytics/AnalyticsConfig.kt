package org.jetbrains.hexana.analytics

data class AnalyticsConfig(
    val apiKey: String = DEFAULT_API_KEY,
    val host: String = DEFAULT_HOST,
    val flushAt: Int = DEFAULT_FLUSH_AT,
    val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
) {
    /** A blank key means analytics is disabled: no client is created and nothing is sent. */
    val isEnabled: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_HOST = "https://eu.i.posthog.com"
        val DEFAULT_API_KEY: String get() = AnalyticsBuildConfig.API_KEY
        const val DEFAULT_FLUSH_AT = 20
        const val DEFAULT_FLUSH_INTERVAL_MS = 30_000L
        const val HOST_ENV_VAR = "HEXANA_POSTHOG_HOST"
        const val HOST_SYSTEM_PROPERTY = "hexana.posthog.host"
        const val FLUSH_AT_ENV_VAR = "HEXANA_POSTHOG_FLUSH_AT"
        const val FLUSH_AT_SYSTEM_PROPERTY = "hexana.posthog.flushAt"
        const val FLUSH_INTERVAL_MS_ENV_VAR = "HEXANA_POSTHOG_FLUSH_INTERVAL_MS"
        const val FLUSH_INTERVAL_MS_SYSTEM_PROPERTY = "hexana.posthog.flushIntervalMs"
    }
}
