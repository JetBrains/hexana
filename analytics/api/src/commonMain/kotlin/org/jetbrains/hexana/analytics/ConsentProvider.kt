package org.jetbrains.hexana.analytics

/**
 * Determines whether the user has opted in to anonymous analytics.
 * Implementations are platform-specific:
 * - JVM/IDE: delegates to IntelliJ ConsentOptions
 * - JVM/MCP-server: opt-in via CLI flag
 * - JS/VS Code: reads from extension settings
 */
fun interface ConsentProvider {
    fun isAnalyticsAllowed(): Boolean
}
