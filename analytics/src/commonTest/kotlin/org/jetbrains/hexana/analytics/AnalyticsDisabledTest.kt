package org.jetbrains.hexana.analytics

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnalyticsDisabledTest {
    @AfterTest
    fun tearDown() {
        HexanaAnalytics.shutdown()
    }

    @Test
    fun blankApiKeyDisablesAnalytics() {
        assertFalse(AnalyticsConfig(apiKey = "").isEnabled)
        assertFalse(AnalyticsConfig(apiKey = "   ").isEnabled)
        assertTrue(AnalyticsConfig(apiKey = "test-key").isEnabled)
    }

    @Test
    fun blankApiKeyNeverCreatesPlatformClient() {
        HexanaAnalytics.initialize(
            config = AnalyticsConfig(apiKey = ""),
            consentProvider = ConsentProvider { true },
            distinctIdProvider = DistinctIdProvider { "test-distinct-id" },
        )

        assertSame(NoOpAnalyticsClient, HexanaAnalytics.client)
    }
}
