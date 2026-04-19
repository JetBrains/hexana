package org.jetbrains.hexana.analytics

data class AnalyticsConfig(
    val apiKey: String = DEFAULT_API_KEY,
    val host: String = "https://eu.i.posthog.com",
    val flushAt: Int = 20,
    val flushIntervalMs: Long = 30_000L
) {
    companion object {
        const val DEFAULT_API_KEY = "POSTHOG_PROJECT_API_KEY_PLACEHOLDER"
        const val PROJECT_ID = 0
    }
}
