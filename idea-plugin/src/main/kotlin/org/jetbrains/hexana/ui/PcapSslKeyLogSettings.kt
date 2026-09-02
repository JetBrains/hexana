package org.jetbrains.hexana.ui

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.annotations.XCollection
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.pcap.SslKeyLog
import org.jetbrains.hexana.providers.PcapBinaryProvider
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Remembers which SSL key log the user loaded for which capture, so
 * reopening the capture decrypts it again without a second trip through the
 * file picker. Only the two paths are stored - never key material.
 *
 * Application-level rather than project-level: a capture and its key log
 * usually live outside any project (a download directory, a `/tmp` dump),
 * and the pairing is a property of those two files, not of whichever project
 * happened to be open when they were paired.
 *
 * The list is most-recent-first and capped at [MAX_ENTRIES], which turns it
 * into an LRU and keeps the settings file from growing without bound.
 */
@Service
@State(name = "HexanaPcapSslKeyLogs", storages = [Storage("hexana-pcap-ssl-key-logs.xml")])
class PcapSslKeyLogSettings :
    SimplePersistentStateComponent<PcapSslKeyLogSettingsState>(PcapSslKeyLogSettingsState()) {

    companion object {
        const val MAX_ENTRIES = 50

        @JvmStatic
        fun getInstance(): PcapSslKeyLogSettings = service()
    }

    /** The key log remembered for [capturePath], or `null` when there is none. */
    fun keyLogPathFor(capturePath: String): String? =
        state.entries.firstOrNull { it.capturePath == capturePath }?.keyLogPath

    /**
     * Pairs [keyLogPath] with [capturePath], replacing any earlier pairing
     * for the same capture and moving it to the front.
     */
    fun remember(capturePath: String, keyLogPath: String) {
        val entry = PcapSslKeyLogEntry().also {
            it.capturePath = capturePath
            it.keyLogPath = keyLogPath
        }
        val kept = state.entries.filterNot { it.capturePath == capturePath }
        state.entries = (listOf(entry) + kept).take(MAX_ENTRIES).toMutableList()
    }

    /** Drops the pairing for [capturePath], if any. */
    fun forget(capturePath: String) {
        val kept = state.entries.filterNot { it.capturePath == capturePath }
        if (kept.size != state.entries.size) state.entries = kept.toMutableList()
    }
}

class PcapSslKeyLogSettingsState : BaseState() {
    @get:XCollection
    var entries by list<PcapSslKeyLogEntry>()
}

class PcapSslKeyLogEntry : BaseState() {
    var capturePath by string()
    var keyLogPath by string()
}

/**
 * Installs the key log remembered for [provider]'s capture, if one is
 * remembered and still readable. Returns `true` when a non-empty key log was
 * installed, so the caller can refresh what it derived from an empty one.
 *
 * A pairing whose key log has since been deleted, moved or emptied is
 * forgotten: leaving it would mean re-reading a file that will never parse
 * on every open of the capture. Read failures that are not the file's
 * absence (an unavailable mount, a permission change) are left alone, since
 * those tend to come back.
 *
 * Blocking: reads the file. Call from a background dispatcher, and before
 * the packet tabs are built so the "Packets" tab is decrypted on first
 * render rather than rebuilt afterwards.
 */
internal fun restoreRememberedSslKeyLog(
    provider: PcapBinaryProvider,
    settings: PcapSslKeyLogSettings = PcapSslKeyLogSettings.getInstance(),
): Boolean {
    if (provider.sslKeyLog.isNotEmpty) return false
    val capturePath = provider.sourcePath ?: return false
    val keyLogPath = settings.keyLogPathFor(capturePath) ?: return false
    val path = try {
        Path.of(keyLogPath)
    } catch (_: InvalidPathException) {
        settings.forget(capturePath)
        return false
    }
    if (!Files.isRegularFile(path)) {
        HexanaLog.LOG.info("Forgetting the SSL key log remembered for $capturePath: $keyLogPath is gone")
        settings.forget(capturePath)
        return false
    }
    val keyLog = try {
        SslKeyLog.parse(Files.readString(path))
    } catch (t: Throwable) {
        HexanaLog.LOG.warn("Failed to re-read the SSL key log remembered for $capturePath: $keyLogPath", t)
        return false
    }
    if (!keyLog.isNotEmpty) {
        HexanaLog.LOG.info("Forgetting the SSL key log remembered for $capturePath: $keyLogPath has no keys left")
        settings.forget(capturePath)
        return false
    }
    provider.sslKeyLog = keyLog
    HexanaLog.LOG.info("Reattached SSL key log $keyLogPath (${keyLog.sessionCount} sessions) to $capturePath")
    return true
}
