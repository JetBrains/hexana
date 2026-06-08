package org.jetbrains.hexana.vscode.lib

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.analytics.AnalyticsConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VscodeAnalyticsTest {
    // Minimal core-Wasm header (magic + version) and a GGUF magic header for
    // the generic-breakdown path; both stay within signed-Byte range.
    private val wasmHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
    private val ggufHeader = byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x00, 0x00, 0x00, 0x00)

    @AfterTest
    fun tearDown() {
        shutdownAnalytics()
    }

    @Test
    fun telemetryEnabledSendsBinaryOpenedThroughExportedApi() = runTest {
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

                captureBinaryOpened("enabled.wasm", wasmHeader)
                flushUntilEvents(server, expectedCount = 1)

                server.events().singleEvent("binary_opened")
                    .requireDistinctId()
                    .assertProperty("format", "wasm")
                    .assertProperty("file_size_bytes", wasmHeader.size)
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
    fun genericOpenReportsExtAndDetectedFormat() = runTest {
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

                // A GGUF model named `.bin`: the container format falls
                // through to generic, the extension hint is `bin`, and the
                // magic sniff resolves to gguf.
                captureBinaryOpened("model.bin", ggufHeader)
                flushUntilEvents(server, expectedCount = 1)

                server.events().singleEvent("binary_opened")
                    .requireDistinctId()
                    .assertProperty("format", "generic")
                    .assertProperty("generic_ext", "bin")
                    .assertProperty("detected_format", "gguf")
                    .assertProperty("surface", "vscode_extension")
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

                captureBinaryOpened("disabled.wasm", wasmHeader)
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
                captureBinaryOpened("a.wasm", wasmHeader)
                flushAnalytics()
                server.assertNoRequests()

                shutdownAnalytics()
                initAnalytics(storagePath, telemetryEnabled = true, appName = "Cursor", appVersion = "1.2.3")
                captureBinaryOpened("b.wasm", wasmHeader)
                flushUntilEvents(server, expectedCount = 1)

                val event = server.events().singleEvent("binary_opened")
                    .requireDistinctId()
                    .assertProperty("file_size_bytes", wasmHeader.size)
                    .assertProperty("surface", "vscode_extension")
                    .assertProperty("ide_type", "Cursor")
                    .assertProperty("ide_build", "1.2.3")

                shutdownAnalytics()
                initAnalytics(storagePath, telemetryEnabled = true, appName = "Cursor", appVersion = "1.2.3")
                captureBinaryOpened("c.wasm", wasmHeader)
                flushUntilEvents(server, expectedCount = 2)

                val secondEvent = server.events()
                    .filter { it.name == "binary_opened" }
                    .last()
                    .requireDistinctId()
                    .assertProperty("file_size_bytes", wasmHeader.size)

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
