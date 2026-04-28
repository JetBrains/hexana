@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.analytics.*

/**
 * Initializes the PostHog analytics client for the VS Code extension.
 *
 * [storagePath] is the path where the anonymous distinct ID is persisted
 * (typically `globalStorageUri.fsPath + "/hexana-analytics-id"`).
 *
 * [telemetryEnabled] reflects `vscode.env.isTelemetryEnabled`.
 */
@JsExport
fun initAnalytics(storagePath: String, telemetryEnabled: Boolean) {
    HexanaAnalytics.initialize(
        config = AnalyticsConfig(),
        consentProvider = ConsentProvider { telemetryEnabled },
        distinctIdProvider = NodeDistinctIdProvider(storagePath),
        globalProperties = mapOf("surface" to "vscode_extension")
    )
}

/**
 * Captures a `wasm_file_opened` event.
 */
@JsExport
fun captureWasmFileOpened(fileSizeBytes: Double) {
    HexanaAnalytics.client.capture(
        HexanaEvent.WasmFileOpened(
            fileSizeBytes = fileSizeBytes.toLong(),
            surface = HexanaEvent.Surface.VSCODE_EXTENSION
        )
    )
}

/**
 * Captures a `wasm_file_parsed` event.
 */
@JsExport
fun captureWasmFileParsed(fileSizeBytes: Double, durationMs: Double, success: Boolean, functionCount: Int?) {
    HexanaAnalytics.client.capture(
        HexanaEvent.WasmFileParsed(
            fileSizeBytes = fileSizeBytes.toLong(),
            durationMs = durationMs.toLong(),
            success = success,
            functionCount = functionCount,
            surface = HexanaEvent.Surface.VSCODE_EXTENSION
        )
    )
}

/**
 * Captures a `hex_tab_selected` event.
 */
@JsExport
fun captureHexTabSelected(tabName: String) {
    HexanaAnalytics.client.capture(
        HexanaEvent.HexTabSelected(tabName = tabName,
            surface = HexanaEvent.Surface.VSCODE_EXTENSION)
    )
}

/**
 * Captures a `wat_view_activated` event.
 */
@JsExport
fun captureWatViewActivated() {
    HexanaAnalytics.client.capture(
        HexanaEvent.WatViewActivated(surface = HexanaEvent.Surface.VSCODE_EXTENSION)
    )
}

/**
 * Captures a `hex_view_activated` event.
 */
@JsExport
fun captureHexViewActivated() {
    HexanaAnalytics.client.capture(
        HexanaEvent.HexViewActivated(surface = HexanaEvent.Surface.VSCODE_EXTENSION)
    )
}

/**
 * Captures a `wasm_unparsed_opcodes` event.
 */
@JsExport
fun captureWasmUnparsedOpcodes(unparsedCount: Int, totalFunctions: Int) {
    HexanaAnalytics.client.capture(
        HexanaEvent.WasmUnparsedOpcodes(
            unparsedCount = unparsedCount,
            totalFunctions = totalFunctions
        )
    )
}

/**
 * Flushes any pending analytics events.
 */
@JsExport
fun flushAnalytics() {
    HexanaAnalytics.client.flush()
}

/**
 * Shuts down the analytics client and flushes pending events.
 */
@JsExport
fun shutdownAnalytics() {
    HexanaAnalytics.shutdown()
}
