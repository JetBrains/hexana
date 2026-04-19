package org.jetbrains.hexana.analytics

data class AnalyticsConfig(
    val apiKey: String,
    val host: String = "https://eu.i.posthog.com",
    val flushAt: Int = 20,
    val flushIntervalMs: Long = 30_000L
)
