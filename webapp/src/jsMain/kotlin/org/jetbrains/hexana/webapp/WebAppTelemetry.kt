package org.jetbrains.hexana.webapp

import kotlinx.browser.window
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.analytics.browser.BrowserAnalyticsClient
import org.jetbrains.hexana.analytics.browser.createBrowserAnalyticsClient

private const val WEBAPP_TELEMETRY_CONSENT_KEY = "hexana.webapp.telemetryEnabled"

internal interface WebAppTelemetry {
    val isEnabled: Boolean

    fun initialize()

    fun setEnabled(enabled: Boolean)

    fun captureBinaryOpened(fileSizeBytes: Int, parserFormat: String?)

    fun captureTabSelected(tabName: String)
}

internal fun interface WebAppTelemetryFactory {
    fun create(): WebAppTelemetry
}

internal interface WebAppTelemetryStorage {
    fun get(key: String): String?

    fun set(key: String, value: String)
}

internal class BrowserWebAppTelemetry(
    private val storage: WebAppTelemetryStorage = browserWebAppTelemetryStorage,
    private val analyticsClient: BrowserAnalyticsClient = createBrowserAnalyticsClient(),
) : WebAppTelemetry {
    override var isEnabled: Boolean = readPersistedConsent()
        private set
    private var initialized = false

    override fun initialize() {
        if (initialized) return
        initialized = true
        analyticsClient.initialize()
        updateConsent()
    }

    override fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        persistConsent(enabled)
        if (initialized) updateConsent()
    }

    override fun captureBinaryOpened(fileSizeBytes: Int, parserFormat: String?) {
        capture(
            HexanaEvent.BinaryOpened(
                format = parserFormat.toBoundedBinaryFormat(),
                fileSizeBytes = fileSizeBytes.coerceIn(0, MAX_BROWSER_FILE_SIZE_BYTES.toInt()).toLong(),
                surface = HexanaEvent.Surface.WEBAPP,
            ),
        )
    }

    override fun captureTabSelected(tabName: String) {
        capture(
            HexanaEvent.HexTabSelected(
                tabName = tabName.toBoundedTabName(),
                surface = HexanaEvent.Surface.WEBAPP,
            ),
        )
    }

    private fun capture(event: HexanaEvent) {
        if (!isEnabled) return
        initialize()
        analyticsClient.capture(event)
    }

    private fun updateConsent() {
        analyticsClient.setConsent(isEnabled)
    }

    private fun readPersistedConsent(): Boolean = try {
        storage.get(WEBAPP_TELEMETRY_CONSENT_KEY) != "false"
    } catch (_: Throwable) {
        false
    }

    private fun persistConsent(enabled: Boolean) {
        try {
            storage.set(WEBAPP_TELEMETRY_CONSENT_KEY, enabled.toString())
        } catch (_: Throwable) {
            // A browser can disable localStorage; keep the in-memory consent choice for this session.
        }
    }
}

private fun String.toBoundedTabName(): String = when (this) {
    "Sections", "Hex", "Wat", "Top", "Functions", "Imports", "Exports", "Tables", "Memories", "Globals", "Data", "Custom", "Disassembly" -> this
    else -> "other"
}

private fun String?.toBoundedBinaryFormat(): HexanaEvent.BinaryFormat = when (this) {
    "core_wasm" -> HexanaEvent.BinaryFormat.WASM
    "wasm_component" -> HexanaEvent.BinaryFormat.WASM_COMPONENT
    "zip" -> HexanaEvent.BinaryFormat.ZIP
    "class" -> HexanaEvent.BinaryFormat.CLASS
    "dex" -> HexanaEvent.BinaryFormat.DEX
    "pcap" -> HexanaEvent.BinaryFormat.PCAP
    "protobuf" -> HexanaEvent.BinaryFormat.PROTOBUF
    "elf" -> HexanaEvent.BinaryFormat.ELF
    "macho" -> HexanaEvent.BinaryFormat.MACHO
    "fat_macho" -> HexanaEvent.BinaryFormat.FAT_MACHO
    "pe" -> HexanaEvent.BinaryFormat.PE
    "coff" -> HexanaEvent.BinaryFormat.COFF
    "archive" -> HexanaEvent.BinaryFormat.ARCHIVE
    "parquet" -> HexanaEvent.BinaryFormat.PARQUET
    "arrow" -> HexanaEvent.BinaryFormat.ARROW_IPC
    "pdb" -> HexanaEvent.BinaryFormat.PDB
    "portable-pdb" -> HexanaEvent.BinaryFormat.PORTABLE_PDB
    "arsc" -> HexanaEvent.BinaryFormat.ARSC
    "dotnet-resources" -> HexanaEvent.BinaryFormat.DOTNET_RESOURCES
    "generic" -> HexanaEvent.BinaryFormat.GENERIC
    else -> HexanaEvent.BinaryFormat.OTHER
}

private object BrowserWebAppTelemetryStorage : WebAppTelemetryStorage {
    override fun get(key: String): String? = window.localStorage.getItem(key)

    override fun set(key: String, value: String) {
        window.localStorage.setItem(key, value)
    }
}

private val browserWebAppTelemetryStorage: WebAppTelemetryStorage = BrowserWebAppTelemetryStorage

internal val browserWebAppTelemetryFactory = WebAppTelemetryFactory { BrowserWebAppTelemetry() }
