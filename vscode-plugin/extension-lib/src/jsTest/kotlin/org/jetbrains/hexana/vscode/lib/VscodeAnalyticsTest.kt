package org.jetbrains.hexana.vscode.lib

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.analytics.AnalyticsConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VscodeAnalyticsTest {
    @AfterTest
    fun tearDown() {
        shutdownAnalytics()
    }

    @Test
    fun telemetryEnabledSendsWasmFileOpenedThroughExportedApi() = runTest {
        if (!isNodeTestRuntime()) return@runTest

        val server = FakePostHogNodeServer.start()
        try {
            withProcessEnv(AnalyticsConfig.HOST_ENV_VAR, server.url) {
                initAnalytics(
                    storagePath = temporaryStoragePath(),
                    telemetryEnabled = true,
                    appName = "Visual Studio Code",
                    appVersion = "1.102.0",
                )

                captureWasmFileOpened(512.0)
                flushUntilEvents(server, expectedCount = 1)

                server.events().singleEvent("wasm_file_opened")
                    .requireDistinctId()
                    .assertProperty("file_size_bytes", 512)
                    .assertProperty("surface", "vscode_extension")
                    .assertProperty("ide_type", "Visual Studio Code")
                    .assertProperty("ide_build", "1.102.0")
            }
        } finally {
            shutdownAnalytics()
            server.close()
        }
    }

    @Test
    fun telemetryDisabledSendsNoRequestsThroughExportedApi() = runTest {
        if (!isNodeTestRuntime()) return@runTest

        val server = FakePostHogNodeServer.start()
        try {
            withProcessEnv(AnalyticsConfig.HOST_ENV_VAR, server.url) {
                initAnalytics(
                    storagePath = temporaryStoragePath(),
                    telemetryEnabled = false,
                    appName = "Visual Studio Code",
                    appVersion = "1.102.0",
                )

                captureWasmFileOpened(512.0)
                flushAnalytics()

                server.assertNoRequests()
            }
        } finally {
            shutdownAnalytics()
            server.close()
        }
    }

    @Test
    fun reinitializingAnalyticsAppliesTelemetryStateChanges() = runTest {
        if (!isNodeTestRuntime()) return@runTest

        val server = FakePostHogNodeServer.start()
        val storagePath = temporaryStoragePath()
        try {
            withProcessEnv(AnalyticsConfig.HOST_ENV_VAR, server.url) {
                initAnalytics(storagePath, telemetryEnabled = false, appName = "Cursor", appVersion = "1.2.3")
                captureWasmFileOpened(128.0)
                flushAnalytics()
                server.assertNoRequests()

                shutdownAnalytics()
                initAnalytics(storagePath, telemetryEnabled = true, appName = "Cursor", appVersion = "1.2.3")
                captureWasmFileOpened(256.0)
                flushUntilEvents(server, expectedCount = 1)

                val event = server.events().singleEvent("wasm_file_opened")
                    .requireDistinctId()
                    .assertProperty("file_size_bytes", 256)
                    .assertProperty("surface", "vscode_extension")
                    .assertProperty("ide_type", "Cursor")
                    .assertProperty("ide_build", "1.2.3")

                shutdownAnalytics()
                initAnalytics(storagePath, telemetryEnabled = true, appName = "Cursor", appVersion = "1.2.3")
                captureWasmFileOpened(384.0)
                flushUntilEvents(server, expectedCount = 2)

                val secondEvent = server.events()
                    .filter { it.name == "wasm_file_opened" }
                    .last()
                    .requireDistinctId()
                    .assertProperty("file_size_bytes", 384)

                assertEquals(event.distinctId, secondEvent.distinctId, "Expected VS Code analytics ID to persist")
            }
        } finally {
            shutdownAnalytics()
            server.close()
        }
    }

    private suspend fun flushUntilEvents(server: FakePostHogNodeServer, expectedCount: Int) {
        val deadline = currentTimeMillis() + 5_000
        do {
            flushAnalytics()
            if (server.events().size >= expectedCount) {
                return
            }
            delay(25)
        } while (currentTimeMillis() < deadline)

        server.awaitEvents(expectedCount, timeoutMs = 0)
    }

    private suspend fun withProcessEnv(key: String, value: String, block: suspend () -> Unit) {
        val process = js("process")
        val previous = process.env[key]
        process.env[key] = value
        try {
            block()
        } finally {
            if (previous == null) {
                deleteDynamicProperty(process.env, key)
            } else {
                process.env[key] = previous
            }
        }
    }

    private fun temporaryStoragePath(): String {
        val fs = js("eval('require')('fs')")
        val os = js("eval('require')('os')")
        val path = js("eval('require')('path')")
        val directory = fs.mkdtempSync(path.join(os.tmpdir(), "hexana-vscode-analytics-"))
        return path.join(directory, "analytics-id") as String
    }
}

private fun deleteDynamicProperty(target: dynamic, key: String) {
    js("Reflect").deleteProperty(target, key)
}

private fun isNodeTestRuntime(): Boolean =
    js("typeof process !== 'undefined' && process.versions && process.versions.node != null") as Boolean

private fun currentTimeMillis(): Double =
    js("Date.now()") as Double
