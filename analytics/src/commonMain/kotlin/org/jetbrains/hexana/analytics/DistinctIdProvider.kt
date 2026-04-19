package org.jetbrains.hexana.analytics

/**
 * Returns a stable anonymous ID for the current installation.
 * Must not contain any PII. The ID is generated once and persisted locally.
 */
fun interface DistinctIdProvider {
    fun distinctId(): String
}
