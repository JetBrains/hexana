package org.jetbrains.hexana.analytics

import org.jetbrains.hexana.analytics.testing.FakePostHogServer
import java.time.Duration
import kotlin.test.Test

class PostHogJvmClientHttpTest {
    @Test
    fun consentAllowedSendsEventToConfiguredPostHogHost() {
        FakePostHogServer.start().use { server ->
            val client = newClient(server, consentAllowed = true)
            try {
                client.capture(
                    HexanaEvent.BinaryOpened(
                        format = HexanaEvent.BinaryFormat.WASM,
                        fileSizeBytes = 512,
                        surface = HexanaEvent.Surface.IDE_PLUGIN
                    )
                )

                server.awaitEvent("binary_opened")
                    .requireDistinctId(TEST_DISTINCT_ID)
                    .assertProperty("format", "wasm")
                    .assertProperty("file_size_bytes", 512)
                    .assertProperty("surface", "ide_plugin")
                    .assertProperty("ide_type", "IntelliJ IDEA")
                    .assertProperty("ide_build", "TEST-1")
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun eventPropertiesOverrideGlobalProperties() {
        FakePostHogServer.start().use { server ->
            val client = newClient(
                server,
                consentAllowed = true,
                globalProperties = mapOf(
                    "surface" to "global_surface",
                    "ide_type" to "IntelliJ IDEA",
                )
            )
            try {
                client.capture(
                    HexanaEvent.HexViewActivated(surface = HexanaEvent.Surface.IDE_PLUGIN)
                )

                server.awaitEvent("hex_view_activated")
                    .assertProperty("surface", "ide_plugin")
                    .assertProperty("ide_type", "IntelliJ IDEA")
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun consentDeniedSendsNoRequests() {
        FakePostHogServer.start().use { server ->
            val client = newClient(server, consentAllowed = false)
            try {
                client.capture(
                    HexanaEvent.BinaryOpened(
                        format = HexanaEvent.BinaryFormat.WASM,
                        fileSizeBytes = 512,
                        surface = HexanaEvent.Surface.IDE_PLUGIN
                    )
                )
                client.flush()

                server.assertNoRequests()
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun explicitFlushDeliversQueuedEventBelowFlushThreshold() {
        FakePostHogServer.start().use { server ->
            val client = newClient(server, consentAllowed = true, flushAt = 20)
            try {
                client.capture(
                    HexanaEvent.McpServerStarted(
                        transport = HexanaEvent.McpTransport.HTTP,
                        embeddedInIde = false
                    )
                )

                flushUntilEvents(client, server, expectedCount = 1)

                server.events().single()
                    .requireDistinctId(TEST_DISTINCT_ID)
                    .assertProperty("transport", "http")
                    .assertProperty("embedded_in_ide", false)
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun defaultHostCanBeOverriddenWithSystemProperty() {
        FakePostHogServer.start().use { server ->
            withSystemProperty(AnalyticsConfig.HOST_SYSTEM_PROPERTY, server.url) {
                val client = PostHogJvmClient(
                    config = AnalyticsConfig(
                        apiKey = "test-key",
                        flushAt = 1,
                        flushIntervalMs = 1_000,
                    ),
                    consentProvider = ConsentProvider { true },
                    distinctIdProvider = DistinctIdProvider { TEST_DISTINCT_ID },
                    globalProperties = emptyMap()
                )
                try {
                    client.capture(HexanaEvent.WatViewActivated(HexanaEvent.Surface.IDE_PLUGIN))

                    server.awaitEvent("wat_view_activated")
                        .requireDistinctId(TEST_DISTINCT_ID)
                        .assertProperty("surface", "ide_plugin")
                } finally {
                    client.shutdown()
                }
            }
        }
    }

    @Test
    fun defaultFlushSettingsCanBeOverriddenWithSystemProperties() {
        FakePostHogServer.start().use { server ->
            withSystemProperties(
                mapOf(
                    AnalyticsConfig.HOST_SYSTEM_PROPERTY to server.url,
                    AnalyticsConfig.FLUSH_AT_SYSTEM_PROPERTY to "1",
                    AnalyticsConfig.FLUSH_INTERVAL_MS_SYSTEM_PROPERTY to "1000",
                )
            ) {
                val client = PostHogJvmClient(
                    config = AnalyticsConfig(apiKey = "test-key"),
                    consentProvider = ConsentProvider { true },
                    distinctIdProvider = DistinctIdProvider { TEST_DISTINCT_ID },
                    globalProperties = emptyMap()
                )
                try {
                    client.capture(HexanaEvent.HexViewActivated(HexanaEvent.Surface.IDE_PLUGIN))

                    server.awaitEvent("hex_view_activated")
                        .requireDistinctId(TEST_DISTINCT_ID)
                        .assertProperty("surface", "ide_plugin")
                } finally {
                    client.shutdown()
                }
            }
        }
    }

    private fun newClient(
        server: FakePostHogServer,
        consentAllowed: Boolean,
        flushAt: Int = 1,
        globalProperties: Map<String, Any> = mapOf(
            "ide_type" to "IntelliJ IDEA",
            "ide_build" to "TEST-1",
        ),
    ): PostHogJvmClient {
        return PostHogJvmClient(
            config = AnalyticsConfig(
                apiKey = "test-key",
                host = server.url,
                flushAt = flushAt,
                flushIntervalMs = 1_000,
            ),
            consentProvider = ConsentProvider { consentAllowed },
            distinctIdProvider = DistinctIdProvider { TEST_DISTINCT_ID },
            globalProperties = globalProperties,
        )
    }

    private fun flushUntilEvents(client: AnalyticsClient, server: FakePostHogServer, expectedCount: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        do {
            client.flush()
            if (server.events().size >= expectedCount) {
                return
            }
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)

        server.awaitEvents(expectedCount, timeout = Duration.ZERO)
    }

    private fun withSystemProperty(key: String, value: String, block: () -> Unit) {
        withSystemProperties(mapOf(key to value), block)
    }

    private fun withSystemProperties(values: Map<String, String>, block: () -> Unit) {
        val previousValues = values.keys.associateWith { System.getProperty(it) }
        values.forEach { (key, value) -> System.setProperty(key, value) }
        try {
            block()
        } finally {
            previousValues.forEach { (key, previous) ->
                if (previous == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, previous)
                }
            }
        }
    }

    private companion object {
        const val TEST_DISTINCT_ID = "test-distinct-id"
    }
}
