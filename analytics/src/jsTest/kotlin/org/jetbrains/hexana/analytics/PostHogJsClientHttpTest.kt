package org.jetbrains.hexana.analytics

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.jetbrains.hexana.analytics.testing.FakePostHogNodeServer
import org.jetbrains.hexana.analytics.testing.flushUntilEvents
import org.jetbrains.hexana.analytics.testing.singleEvent
import kotlin.test.Test

class PostHogJsClientHttpTest {
    @Test
    fun consentAllowedSendsEventToConfiguredPostHogHost() = runTest {
        val server = FakePostHogNodeServer.start()
        val client = newClient(server, consentAllowed = true)
        try {
            client.capture(
                HexanaEvent.WasmFileOpened(
                    fileSizeBytes = 512,
                    surface = HexanaEvent.Surface.VSCODE_EXTENSION
                )
            )

            server.awaitEvent("wasm_file_opened")
                .requireDistinctId(TEST_DISTINCT_ID)
                .assertProperty("file_size_bytes", 512)
                .assertProperty("surface", "vscode_extension")
                .assertProperty("ide_type", "Visual Studio Code")
                .assertProperty("ide_build", "1.102.0")
        } finally {
            shutdown(client, server)
        }
    }

    @Test
    fun eventPropertiesOverrideGlobalProperties() = runTest {
        val server = FakePostHogNodeServer.start()
        val client = newClient(
            server,
            consentAllowed = true,
            globalProperties = mapOf(
                "surface" to "global_surface",
                "ide_type" to "Visual Studio Code",
            )
        )
        try {
            client.capture(
                HexanaEvent.HexViewActivated(surface = HexanaEvent.Surface.VSCODE_EXTENSION)
            )

            server.awaitEvent("hex_view_activated")
                .assertProperty("surface", "vscode_extension")
                .assertProperty("ide_type", "Visual Studio Code")
        } finally {
            shutdown(client, server)
        }
    }

    @Test
    fun consentDeniedSendsNoRequests() = runTest {
        val server = FakePostHogNodeServer.start()
        val client = newClient(server, consentAllowed = false)
        try {
            client.capture(
                HexanaEvent.WasmFileOpened(
                    fileSizeBytes = 512,
                    surface = HexanaEvent.Surface.VSCODE_EXTENSION
                )
            )
            client.flush()

            server.assertNoRequests()
        } finally {
            shutdown(client, server)
        }
    }

    @Test
    fun explicitFlushDeliversQueuedEventBelowFlushThreshold() = runTest {
        val server = FakePostHogNodeServer.start()
        val client = newClient(server, consentAllowed = true, flushAt = 20)
        try {
            client.capture(
                HexanaEvent.WatViewActivated(surface = HexanaEvent.Surface.VSCODE_EXTENSION)
            )

            flushUntilEvents(client, server, expectedCount = 1)

            server.events().singleEvent("wat_view_activated")
                .requireDistinctId(TEST_DISTINCT_ID)
                .assertProperty("surface", "vscode_extension")
        } finally {
            shutdown(client, server)
        }
    }

    @Test
    fun defaultHostCanBeOverriddenWithEnvironmentVariable() = runTest {
        val server = FakePostHogNodeServer.start()
        withProcessEnv(AnalyticsConfig.HOST_ENV_VAR, server.url) {
            val client = PostHogJsClient(
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
                client.capture(HexanaEvent.HexTabSelected("Functions", HexanaEvent.Surface.VSCODE_EXTENSION))

                    server.awaitEvent("hex_tab_selected")
                        .requireDistinctId(TEST_DISTINCT_ID)
                        .assertProperty("tab_name", "Functions")
                        .assertProperty("surface", "vscode_extension")
                } finally {
                    shutdown(client, server)
                }
            }
        }

    @Test
    fun nodeDistinctIdProviderPersistsIdAcrossClients() = runTest {
        val server = FakePostHogNodeServer.start()
        val storagePath = temporaryStoragePath()
        val firstClient = newClient(
            server = server,
            consentAllowed = true,
            distinctIdProvider = NodeDistinctIdProvider(storagePath)
        )
        try {
            firstClient.capture(HexanaEvent.WatViewActivated(HexanaEvent.Surface.VSCODE_EXTENSION))
            server.awaitEvent("wat_view_activated")
        } finally {
            firstClient.shutdown()
            delay(100)
        }

        val secondClient = newClient(
            server = server,
            consentAllowed = true,
            distinctIdProvider = NodeDistinctIdProvider(storagePath)
        )
        try {
            secondClient.capture(HexanaEvent.HexViewActivated(HexanaEvent.Surface.VSCODE_EXTENSION))

            val events = server.awaitEvents(2)
            val firstDistinctId = events.singleEvent("wat_view_activated").distinctId
            events.singleEvent("hex_view_activated")
                .requireDistinctId(firstDistinctId)
        } finally {
            shutdown(secondClient, server)
        }
    }

    private fun newClient(
        server: FakePostHogNodeServer,
        consentAllowed: Boolean,
        flushAt: Int = 1,
        distinctIdProvider: DistinctIdProvider = DistinctIdProvider { TEST_DISTINCT_ID },
        globalProperties: Map<String, Any> = mapOf(
            "ide_type" to "Visual Studio Code",
            "ide_build" to "1.102.0",
        ),
    ): PostHogJsClient {
        return PostHogJsClient(
            config = AnalyticsConfig(
                apiKey = "test-key",
                host = server.url,
                flushAt = flushAt,
                flushIntervalMs = 1_000,
            ),
            consentProvider = ConsentProvider { consentAllowed },
            distinctIdProvider = distinctIdProvider,
            globalProperties = globalProperties,
        )
    }

    private suspend fun withProcessEnv(key: String, value: String, block: suspend () -> Unit) {
        val process = js("process")
        val previous = process.env[key]
        process.env[key] = value
        try {
            block()
        } finally {
            if (previous == null) {
                js("delete process.env[key]")
            } else {
                process.env[key] = previous
            }
        }
    }

    private suspend fun shutdown(client: AnalyticsClient, server: FakePostHogNodeServer) {
        client.shutdown()
        delay(100)
        server.close()
    }

    private fun temporaryStoragePath(): String {
        val fs = js("eval('require')('fs')")
        val os = js("eval('require')('os')")
        val path = js("eval('require')('path')")
        val directory = fs.mkdtempSync(path.join(os.tmpdir(), "hexana-analytics-"))
        return path.join(directory, "analytics-id") as String
    }

    private companion object {
        const val TEST_DISTINCT_ID = "test-distinct-id"
    }
}
