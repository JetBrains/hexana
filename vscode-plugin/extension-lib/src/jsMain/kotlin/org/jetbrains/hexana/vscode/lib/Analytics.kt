@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.analytics.*
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryFormatDetector
import org.jetbrains.hexana.providers.ArscBinaryProvider
import org.jetbrains.hexana.providers.BinaryProviderKind
import org.jetbrains.hexana.providers.ClassFileBinaryProvider
import org.jetbrains.hexana.providers.ClrResourcesBinaryProvider
import org.jetbrains.hexana.providers.GgufBinaryProvider
import org.jetbrains.hexana.providers.SafetensorsBinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.PcapBinaryProvider
import org.jetbrains.hexana.providers.PdbBinaryProvider
import org.jetbrains.hexana.providers.PortablePdbBinaryProvider
import org.jetbrains.hexana.providers.ProtobufBinaryProvider
import org.jetbrains.hexana.providers.TarBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.providers.resolveBinaryProviderKind

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
 *
 * [pluginVersion] is the Hexana extension's own version (e.g.
 * `context.extension.packageJSON.version`) — distinct from [appVersion], which
 * is the host editor's. Without it, events cannot be segmented by release, so
 * a shift in the numbers cannot be told apart from a shift in user behaviour.
 */
@JsExport
fun initAnalytics(
    storagePath: String,
    telemetryEnabled: Boolean,
    appName: String,
    appVersion: String,
    pluginVersion: String,
) {
    initAnalyticsWith(AnalyticsConfig(), storagePath, telemetryEnabled, appName, appVersion, pluginVersion)
}

internal fun initAnalyticsWith(
    config: AnalyticsConfig,
    storagePath: String,
    telemetryEnabled: Boolean,
    appName: String,
    appVersion: String,
    pluginVersion: String,
) {
    HexanaAnalytics.initialize(
        config = config,
        consentProvider = ConsentProvider { telemetryEnabled },
        distinctIdProvider = NodeDistinctIdProvider(storagePath),
        globalProperties = mapOf(
            "surface" to HexanaEvent.Surface.VSCODE_EXTENSION.value,
            "ide_type" to appName,
            "ide_build" to appVersion,
            "plugin_version" to pluginVersion
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
fun captureBinaryOpened(fileName: String, bytes: ByteArray) = captureBinaryOpenedOfSize(fileName, bytes, bytes.size.toDouble())

/**
 * [captureBinaryOpened] for a file the host loaded only the head of: [bytes] is that head and
 * [fileSize] the size on disk, which is what `file_size_bytes` reports.
 */
@JsExport
fun captureBinaryOpenedOfSize(fileName: String, bytes: ByteArray, fileSize: Double) {
    val format = resolveBinaryFormat(fileName, bytes)
    val isGeneric = format == HexanaEvent.BinaryFormat.GENERIC
    val genericHeader = if (isGeneric && bytes.size >= 8) HexanaEvent.GenericHeader.of(bytes) else null
    HexanaAnalytics.client.capture(
        HexanaEvent.BinaryOpened(
            format = format,
            fileSizeBytes = fileSize.safeToLong(),
            surface = HexanaEvent.Surface.VSCODE_EXTENSION,
            genericExt = if (isGeneric) HexanaEvent.GenericExt.fromExtension(extensionOf(fileName)) else null,
            detectedFormat = if (isGeneric) HexanaEvent.DetectedFormat.fromMagic(bytes) else null,
            genericHeader = genericHeader,
        )
    )
}

/**
 * Resolves the analytics [HexanaEvent.BinaryFormat] from file content,
 * mirroring the IDE plugin's `binaryFormatOf`: whatever the shared provider factory
 * resolves the bytes to first, then the content checks `nativeOrGenericBinaryProvider`
 * runs (in its order), then native formats, else GENERIC. Only the cheap static
 * detectors run here — no provider is built.
 *
 * The host hands over already-inflated bytes under the inner name (`app.jar.gz` →
 * `app.jar`), and the editor picks its provider from content, so once every content
 * check has failed the user sees a generic view: the extension is not consulted
 * again, or a malformed `.wasm` / `.dex` would be reported as a format it is not
 * displayed as. The extension only names the zip subtype (jar / war / apk).
 */
private fun resolveBinaryFormat(fileName: String, bytes: ByteArray): HexanaEvent.BinaryFormat {
    // The same call the editor makes, so the reported format is the one the user is
    // actually looking at rather than a second, drifting classification.
    when (resolveBinaryProviderKind(bytes.toCommonByteBuffer(), fileName)) {
        BinaryProviderKind.Wasm -> return HexanaEvent.BinaryFormat.WASM
        BinaryProviderKind.WasmComponent -> return HexanaEvent.BinaryFormat.WASM_COMPONENT
        BinaryProviderKind.Parquet -> return HexanaEvent.BinaryFormat.PARQUET
        is BinaryProviderKind.Arrow -> return HexanaEvent.BinaryFormat.ARROW_IPC
        // A Protobuf message is only ever reached through an explicit `.pb` open, not
        // auto-detected, so it reports GENERIC exactly as it does in the IDE.
        BinaryProviderKind.ProtobufMessage -> return HexanaEvent.BinaryFormat.GENERIC
        BinaryProviderKind.NativeOrGeneric -> Unit
    }
    contentFormatOf(fileName, bytes)?.let { return it }
    when (NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer())) {
        is NativeBinaryFormat.Elf -> return HexanaEvent.BinaryFormat.ELF
        is NativeBinaryFormat.MachO -> return HexanaEvent.BinaryFormat.MACHO
        is NativeBinaryFormat.FatMachO -> return HexanaEvent.BinaryFormat.FAT_MACHO
        is NativeBinaryFormat.Pe -> return HexanaEvent.BinaryFormat.PE
        is NativeBinaryFormat.Coff -> return HexanaEvent.BinaryFormat.COFF
        NativeBinaryFormat.Archive -> return HexanaEvent.BinaryFormat.ARCHIVE
        null -> Unit
    }
    return HexanaEvent.BinaryFormat.GENERIC
}

/**
 * The content checks of `nativeOrGenericBinaryProvider`, in the same order, so a
 * format the editor recognises is never reported as GENERIC. The class check also
 * precedes the native detector there: both key off 0xCAFEBABE (Java class file vs
 * Mach-O universal).
 */
private fun contentFormatOf(fileName: String, bytes: ByteArray): HexanaEvent.BinaryFormat? {
    val buffer = bytes.toCommonByteBuffer()
    return when {
        ZipBinaryProvider.isZip(buffer) -> HexanaEvent.BinaryFormat.forZipContent(extensionOf(fileName))
        TarBinaryProvider.isTar(buffer) -> HexanaEvent.BinaryFormat.TAR
        ClassFileBinaryProvider.isClassFile(buffer) -> HexanaEvent.BinaryFormat.CLASS
        DexBinaryProvider.isDexFile(buffer) -> HexanaEvent.BinaryFormat.DEX
        ArscBinaryProvider.isArscFile(buffer) -> HexanaEvent.BinaryFormat.ARSC
        ClrResourcesBinaryProvider.isResourcesFile(buffer) -> HexanaEvent.BinaryFormat.DOTNET_RESOURCES
        GgufBinaryProvider.isGgufFile(buffer) -> HexanaEvent.BinaryFormat.GGUF
        SafetensorsBinaryProvider.isSafetensorsFile(buffer) -> HexanaEvent.BinaryFormat.SAFETENSORS
        PdbBinaryProvider.isPdbFile(buffer) -> HexanaEvent.BinaryFormat.PDB
        PortablePdbBinaryProvider.isPortablePdbFile(buffer) -> HexanaEvent.BinaryFormat.PORTABLE_PDB
        PcapBinaryProvider.isPcapFile(buffer) -> HexanaEvent.BinaryFormat.PCAP
        ProtobufBinaryProvider.isFileDescriptorSet(buffer) -> HexanaEvent.BinaryFormat.PROTOBUF
        else -> null
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
