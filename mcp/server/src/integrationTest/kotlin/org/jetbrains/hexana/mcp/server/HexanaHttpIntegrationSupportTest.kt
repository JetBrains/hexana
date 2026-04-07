package org.jetbrains.hexana.mcp.server

import org.junit.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HexanaHttpIntegrationSupportTest {
    @Test
    fun httpReadyTimeoutUsesDefaultWhenPropertyMissingOrBlank() {
        assertEquals(Duration.ofSeconds(20), HexanaInstalledServer.httpReadyTimeout(null))
        assertEquals(Duration.ofSeconds(20), HexanaInstalledServer.httpReadyTimeout("   "))
    }

    @Test
    fun httpReadyTimeoutUsesConfiguredMilliseconds() {
        assertEquals(Duration.ofMillis(1500), HexanaInstalledServer.httpReadyTimeout("1500"))
    }

    @Test
    fun httpReadyTimeoutRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> {
            HexanaInstalledServer.httpReadyTimeout("fast")
        }
        assertFailsWith<IllegalArgumentException> {
            HexanaInstalledServer.httpReadyTimeout("0")
        }
        assertFailsWith<IllegalArgumentException> {
            HexanaInstalledServer.httpReadyTimeout("-1")
        }
    }
}
