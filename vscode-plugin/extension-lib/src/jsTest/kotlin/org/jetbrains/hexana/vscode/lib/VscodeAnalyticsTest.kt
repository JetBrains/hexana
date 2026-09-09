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
                    pluginVersion = "9.9.9",
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
                    pluginVersion = "9.9.9",
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
    fun archiveContainerMagicResolvesDetectedFormat() = runTest {
        if (!isNodeTestRuntime()) return@runTest

        val server = FakePostHogNodeServer.start()
        try {
            withProcessEnv(AnalyticsConfig.HOST_ENV_VAR, server.url) {
                initAnalytics(
                    storagePath = temporaryStoragePath(),
                    telemetryEnabled = true,
                    appName = "Visual Studio Code",
                    appVersion = "1.102.0",
                    pluginVersion = "9.9.9",
                )

                // One file per newly-supported container magic. Each name has an
                // unrecognized extension so it falls through to generic and is
                // classified by its magic bytes alone. tar is the offset-257 case.
                captureBinaryOpened("a.blob", bytes(0x28, 0xB5, 0x2F, 0xFD)) // zstd
                captureBinaryOpened("b.blob", bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) // 7z
                captureBinaryOpened("c.blob", bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) // xz
                captureBinaryOpened("d.blob", bytes(0x42, 0x5A, 0x68, 0x39)) // bzip2 "BZh9"
                captureBinaryOpened("e.blob", bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)) // rar
                captureBinaryOpened("f.blob", bytes(0x04, 0x22, 0x4D, 0x18)) // lz4
                captureBinaryOpened("g.blob", bytes(0x4D, 0x53, 0x43, 0x46)) // cab "MSCF"
                captureBinaryOpened("h.blob", tarRecord()) // tar "ustar" at offset 257

                flushUntilEvents(server, expectedCount = 8)

                val detected = server.events()
                    .filter { it.name == "binary_opened" }
                    .map { "${it.properties["detected_format"]}" }
                    .toSet()
                assertEquals(
                    setOf("zstd", "sevenzip", "xz", "bzip2", "rar", "lz4", "cab", "tar"),
                    detected,
                )
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
                    pluginVersion = "9.9.9",
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
                initAnalytics(storagePath, telemetryEnabled = false, appName = "Cursor", appVersion = "1.2.3", pluginVersion = "9.9.9")
                captureBinaryOpened("a.wasm", wasmHeader)
                flushAnalytics()
                server.assertNoRequests()

                shutdownAnalytics()
                initAnalytics(storagePath, telemetryEnabled = true, appName = "Cursor", appVersion = "1.2.3", pluginVersion = "9.9.9")
                captureBinaryOpened("b.wasm", wasmHeader)
                flushUntilEvents(server, expectedCount = 1)

                val event = server.events().singleEvent("binary_opened")
                    .requireDistinctId()
                    .assertProperty("file_size_bytes", wasmHeader.size)
                    .assertProperty("surface", "vscode_extension")
                    .assertProperty("ide_type", "Cursor")
                    .assertProperty("ide_build", "1.2.3")
                    .assertProperty("plugin_version", "9.9.9")

                shutdownAnalytics()
                initAnalytics(storagePath, telemetryEnabled = true, appName = "Cursor", appVersion = "1.2.3", pluginVersion = "9.9.9")
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

/** Builds a byte array from int literals, allowing values above 0x7F. */
private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** A 512-byte tar record: arbitrary filename head plus "ustar" at offset 257. */
private fun tarRecord(): ByteArray {
    val record = ByteArray(512)
    "hello.txt".encodeToByteArray().copyInto(record)
    bytes(0x75, 0x73, 0x74, 0x61, 0x72).copyInto(record, destinationOffset = 257)
    return record
}

private fun isNodeTestRuntime(): Boolean =
    js("typeof process !== 'undefined' && process.versions && process.versions.node != null") as Boolean

private fun currentTimeMillis(): Double =
    js("Date.now()") as Double
