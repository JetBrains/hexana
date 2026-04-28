@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.analytics.*

/** Safely converts a JS [Double] to [Long], clamping NaN, Infinity, and negative values to 0. */
private fun Double.safeToLong(): Long =
    if (isNaN() || isInfinite() || this < 0.0) 0L else toLong()

/**
 * Initializes the PostHog analytics client for the VS Code extension.
 *
 * [storagePath] is the path where the anonymous distinct ID is persisted
 * (typically `globalStorageUri.fsPath + "/hexana-analytics-id"`).
 *
 * [telemetryEnabled] reflects `vscode.env.isTelemetryEnabled`.
 *
 * [appName] is the editor display name (e.g. `vscode.env.appName`).
 * For stock VS Code this is `"Visual Studio Code"`, forks such as
 * Cursor or Windsurf report their own name.
 *
 * [appVersion] is the editor version string (e.g. `vscode.version`).
 */
@JsExport
fun initAnalytics(storagePath: String, telemetryEnabled: Boolean, appName: String, appVersion: String) {
    HexanaAnalytics.initialize(
        config = AnalyticsConfig(),
        consentProvider = ConsentProvider { telemetryEnabled },
        distinctIdProvider = NodeDistinctIdProvider(storagePath),
        globalProperties = mapOf(
            "surface" to "vscode_extension",
            "ide_type" to appName,
            "ide_build" to appVersion
        )
    )
}

/**
 * Captures a `wasm_file_opened` event.
 */
@JsExport
fun captureWasmFileOpened(fileSizeBytes: Double) {
    HexanaAnalytics.client.capture(
        HexanaEvent.WasmFileOpened(
            fileSizeBytes = fileSizeBytes.safeToLong(),
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
            fileSizeBytes = fileSizeBytes.safeToLong(),
            durationMs = durationMs.safeToLong(),
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
