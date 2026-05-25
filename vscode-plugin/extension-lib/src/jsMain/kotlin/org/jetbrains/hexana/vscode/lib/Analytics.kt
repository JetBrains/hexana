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
 * Captures a `binary_opened` event.
 *
 * [format] is the snake_case PostHog string for the binary container
 * format (`wasm`, `wasm_component`, `elf`, `macho`, `pe`, `coff`,
 * `fat_macho`, `jar`, `war`, `zip`, `apk`, `class`, `generic`). Unknown
 * values fall back to `generic` so the event is never dropped on the JS
 * side.
 *
 * The optional native-binary fields (`arch`, `is_stripped`,
 * `has_debug_info`, `symbol_count_bucket`) are not surfaced through the
 * JS API yet — the VS Code extension does not run native-binary
 * introspection. They can be added to the JS signature once it does.
 */
@JsExport
fun captureBinaryOpened(fileSizeBytes: Double, format: String) {
    val resolvedFormat = HexanaEvent.BinaryFormat.fromValue(format)
        ?: HexanaEvent.BinaryFormat.GENERIC
    HexanaAnalytics.client.capture(
        HexanaEvent.BinaryOpened(
            format = resolvedFormat,
            fileSizeBytes = fileSizeBytes.safeToLong(),
            surface = HexanaEvent.Surface.VSCODE_EXTENSION,
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
 *
 * [binaryFormat] is the snake_case PostHog string for the container
 * format of the currently-open binary (e.g. `wasm`, `elf`, `macho`).
 * `null` is accepted for callers that don't have format detection wired
 * yet; unknown strings resolve to null as well, in which case the
 * `binary_format` property is omitted from the event entirely.
 */
@JsExport
fun captureHexTabSelected(tabName: String, binaryFormat: String? = null) {
    val resolvedFormat = binaryFormat?.let { HexanaEvent.BinaryFormat.fromValue(it) }
    HexanaAnalytics.client.capture(
        HexanaEvent.HexTabSelected(
            tabName = tabName,
            surface = HexanaEvent.Surface.VSCODE_EXTENSION,
            binaryFormat = resolvedFormat,
        )
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
