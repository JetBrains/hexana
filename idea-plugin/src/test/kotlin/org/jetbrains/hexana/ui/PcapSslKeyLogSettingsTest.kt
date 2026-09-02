package org.jetbrains.hexana.ui

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.PcapBinaryProvider
import java.io.File
import java.nio.file.Files

/**
 * Covers remembering a capture's key log and handing it back on reopen. The
 * pairing is what spares the user the file picker every time, so both halves
 * matter: that a live pairing reattaches, and that a pairing pointing at a
 * file that is gone stops being retried on every open.
 */
class PcapSslKeyLogSettingsTest : UsefulTestCase() {

    private lateinit var directory: File
    private lateinit var settings: PcapSslKeyLogSettings

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("hexana-pcap-key-log-settings-").toFile()
        settings = PcapSslKeyLogSettings()
    }

    override fun tearDown() {
        try {
            directory.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testRemembersTheLastKeyLogPerCapture() {
        settings.remember("/captures/a.pcap", "/keys/a.txt")
        settings.remember("/captures/b.pcap", "/keys/b.txt")
        assertEquals("/keys/a.txt", settings.keyLogPathFor("/captures/a.pcap"))
        assertEquals("/keys/b.txt", settings.keyLogPathFor("/captures/b.pcap"))
        assertNull(settings.keyLogPathFor("/captures/never-paired.pcap"))

        // Changing the key log replaces the pairing rather than adding one.
        settings.remember("/captures/a.pcap", "/keys/a2.txt")
        assertEquals("/keys/a2.txt", settings.keyLogPathFor("/captures/a.pcap"))
        assertEquals(2, settings.state.entries.size)

        settings.forget("/captures/a.pcap")
        assertNull(settings.keyLogPathFor("/captures/a.pcap"))
        assertEquals("/keys/b.txt", settings.keyLogPathFor("/captures/b.pcap"))
    }

    fun testOldestPairingsFallOffTheEnd() {
        repeat(PcapSslKeyLogSettings.MAX_ENTRIES + 10) { index ->
            settings.remember("/captures/$index.pcap", "/keys/$index.txt")
        }
        assertEquals(PcapSslKeyLogSettings.MAX_ENTRIES, settings.state.entries.size)
        // The most recent survives, the first one paired does not.
        val newest = PcapSslKeyLogSettings.MAX_ENTRIES + 9
        assertEquals("/keys/$newest.txt", settings.keyLogPathFor("/captures/$newest.pcap"))
        assertNull(settings.keyLogPathFor("/captures/0.pcap"))
    }

    fun testRememberedKeyLogIsReattachedOnOpen() {
        val keyLog = File(directory, "sslkeylog.txt").also { it.writeText(keyLogText()) }
        val capture = File(directory, "session.pcap")
        settings.remember(capture.path, keyLog.path)

        val provider = pcapProvider(capture.path)
        assertTrue(restoreRememberedSslKeyLog(provider, settings))
        assertEquals(1, provider.sslKeyLog.sessionCount)

        // Reattaching over a key log that is already installed would throw
        // away whatever the user loaded by hand this session.
        assertFalse(restoreRememberedSslKeyLog(provider, settings))
    }

    fun testPairingIsForgottenWhenTheKeyLogIsGone() {
        val capture = File(directory, "session.pcap")
        settings.remember(capture.path, File(directory, "never-written.txt").path)

        val provider = pcapProvider(capture.path)
        assertFalse(restoreRememberedSslKeyLog(provider, settings))
        assertFalse(provider.sslKeyLog.isNotEmpty)
        assertNull(settings.keyLogPathFor(capture.path))
    }

    fun testAKeyLogWithNoKeysLeftIsForgotten() {
        val keyLog = File(directory, "emptied.txt").also { it.writeText("# nothing but a comment\n") }
        val capture = File(directory, "session.pcap")
        settings.remember(capture.path, keyLog.path)

        assertFalse(restoreRememberedSslKeyLog(pcapProvider(capture.path), settings))
        assertNull(settings.keyLogPathFor(capture.path))
    }

    fun testInMemoryCapturesHaveNothingToPairAgainst() {
        // An entry unpacked out of an archive has no path, so there is no
        // pairing to look up - and looking one up by name would attach a
        // stranger's key log.
        assertFalse(restoreRememberedSslKeyLog(pcapProvider(sourcePath = null), settings))
    }

    private fun pcapProvider(sourcePath: String?): PcapBinaryProvider =
        PcapBinaryProvider(ByteArray(64).toCommonByteBuffer(), sourcePath)

    /** One `CLIENT_RANDOM` line, which is one session. */
    private fun keyLogText(): String {
        val clientRandom = "aa".repeat(32)
        val secret = "bb".repeat(48)
        return "# Generated for a test.\nCLIENT_RANDOM $clientRandom $secret\n"
    }
}
