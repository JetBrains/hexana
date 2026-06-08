@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.analytics.*
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryFormatDetector
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.shared.detectBinaryType

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
 * Captures a `binary_opened` event for a file opened in the VS Code custom
 * editor.
 *
 * [fileName] is the file name (or path); only its extension is used, and
 * only mapped to a bounded [HexanaEvent.GenericExt] bucket — the raw
 * extension string is never transmitted. [bytes] are the file contents the
 * editor already loaded; the container format is detected from them with the
 * same detectors the IDE plugin uses (Wasm component/core, native
 * ELF/Mach-O/PE/COFF), so the VS Code surface no longer reports every binary
 * as `wasm`.
 *
 * When the format resolves to [HexanaEvent.BinaryFormat.GENERIC] the event
 * is enriched with `generic_ext` (allowlisted extension) and
 * `detected_format` (magic-byte sniff), mirroring the IDE plugin. No raw
 * extension string and no file bytes are transmitted.
 *
 * The native-binary fields (`arch`, `is_stripped`, `has_debug_info`,
 * `symbol_count_bucket`) are still not surfaced here — the VS Code editor
 * does not run the deeper native introspection the IDE does.
 */
@JsExport
fun captureBinaryOpened(fileName: String, bytes: ByteArray) {
    val format = resolveBinaryFormat(fileName, bytes)
    val isGeneric = format == HexanaEvent.BinaryFormat.GENERIC
    HexanaAnalytics.client.capture(
        HexanaEvent.BinaryOpened(
            format = format,
            fileSizeBytes = bytes.size.toLong(),
            surface = HexanaEvent.Surface.VSCODE_EXTENSION,
            genericExt = if (isGeneric) HexanaEvent.GenericExt.fromExtension(extensionOf(fileName)) else null,
            detectedFormat = if (isGeneric) HexanaEvent.DetectedFormat.fromMagic(bytes) else null,
        )
    )
}

/**
 * Resolves the analytics [HexanaEvent.BinaryFormat] from file content,
 * mirroring the IDE plugin's `binaryFormatOf`: Wasm magic first, then native
 * formats, then archive / class by extension, else GENERIC. `.a` static
 * archives ([NativeBinaryFormat.Archive]) fall through to GENERIC, matching
 * the IDE.
 */
private fun resolveBinaryFormat(fileName: String, bytes: ByteArray): HexanaEvent.BinaryFormat {
    when (detectBinaryType(bytes)) {
        BinaryProviderType.Wasm -> return HexanaEvent.BinaryFormat.WASM
        BinaryProviderType.WasmComponent -> return HexanaEvent.BinaryFormat.WASM_COMPONENT
        BinaryProviderType.Generic -> Unit
    }
    when (NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer())) {
        is NativeBinaryFormat.Elf -> return HexanaEvent.BinaryFormat.ELF
        is NativeBinaryFormat.MachO -> return HexanaEvent.BinaryFormat.MACHO
        is NativeBinaryFormat.FatMachO -> return HexanaEvent.BinaryFormat.FAT_MACHO
        is NativeBinaryFormat.Pe -> return HexanaEvent.BinaryFormat.PE
        is NativeBinaryFormat.Coff -> return HexanaEvent.BinaryFormat.COFF
        NativeBinaryFormat.Archive, null -> Unit
    }
    return when (extensionOf(fileName)) {
        "class" -> HexanaEvent.BinaryFormat.CLASS
        "jar" -> HexanaEvent.BinaryFormat.JAR
        "war" -> HexanaEvent.BinaryFormat.WAR
        "apk" -> HexanaEvent.BinaryFormat.APK
        "zip" -> HexanaEvent.BinaryFormat.ZIP
        else -> HexanaEvent.BinaryFormat.GENERIC
    }
}

/** Lowercased file extension, or null when there is none. */
private fun extensionOf(fileName: String): String? =
    fileName.substringAfterLast('.', "").lowercase().ifEmpty { null }

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
