package org.jetbrains.hexana.ui

import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.HexanaProviderKind
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

/**
 * End-to-end guard for automatic PCAP decryption: a capture opened with an
 * `sslkeylog.txt` sitting next to it must build its **Packets** tab with the
 * key log already installed, without any user interaction.
 *
 * The assertion reads what the tab actually resolved to — the column headers
 * and prologue `BinaryEditorView` published when the tab replaced its
 * placeholder — rather than rebuilding a descriptor from the provider. That
 * distinction is the whole point: a rebuilt descriptor would look correct even
 * if the key log had been installed *after* the tab was built, which is the
 * ordering bug this test exists to pin.
 *
 * What it does not cover is the crypto itself; the plaintext is asserted in
 * `TlsDecryptionContextTest`, which can seal its own records.
 */
@Tag("hexana")
class HexanaPcapSslKeyLogAutoDecryptTest {

    @Test
    fun siblingKeyLogDecryptsPacketsTabOnOpen() {
        val withKeys = Files.createTempDirectory("hexana-pcap-with-keylog-")
        val withKeysCapture = withKeys.resolve("session.pcap").also { it.writeBytes(tlsCapture()) }
        withKeys.resolve("sslkeylog.txt").writeText(keyLogText())

        // Same capture bytes, no sibling key log: the control that shows the
        // Decrypted column comes from the key log and not from the capture.
        val withoutKeys = Files.createTempDirectory("hexana-pcap-no-keylog-")
        val withoutKeysCapture = withoutKeys.resolve("session.pcap").also { it.writeBytes(tlsCapture()) }

        hexanaIde(testName = "hexana-pcap-ssl-key-log-auto-decrypt") {
            closeProjectToolWindow()

            val decrypting = openAbsoluteFile(withKeysCapture)
            waitForPcapPanels(decrypting)
            val packets = decrypting.tableTabState("Packets")
            assertTrue(packets.resolved, "Packets tab never published its columns: $packets")
            assertTrue(
                "Decrypted" in packets.columns,
                "Packets tab resolved without the Decrypted column, so the sibling key log " +
                    "was not installed before the tab was built: ${packets.columns}",
            )
            assertTrue(
                packets.prologue.contains("1 sessions"),
                "Expected the Packets prologue to report the key log's single session: '${packets.prologue}'",
            )

            // Publication is not pcap-specific plumbing: the Header tab of the
            // same capture reports its own columns too.
            val header = decrypting.tableTabState("Header")
            assertTrue(header.resolved, "Header tab never published its columns: $header")
            assertEquals(listOf("Property", "Value"), header.columns)

            closeAllEditors()

            val plain = openAbsoluteFile(withoutKeysCapture)
            waitForPcapPanels(plain)
            val plainPackets = plain.tableTabState("Packets")
            assertTrue(plainPackets.resolved, "Packets tab never published its columns: $plainPackets")
            assertFalse(
                "Decrypted" in plainPackets.columns,
                "Packets tab grew a Decrypted column with no key log anywhere: ${plainPackets.columns}",
            )
            assertEquals("", plainPackets.prologue)
        }
    }

    private fun waitForPcapPanels(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 2.minutes.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (
                lastSnapshot.providerKind == HexanaProviderKind.GENERIC.id &&
                lastSnapshot.panelsResolved &&
                setOf("Header", "Packets").all(lastSnapshot.panelNames::contains)
            ) return
            Thread.sleep(250)
        }
        error("Timed out waiting for PCAP Header and Packets tabs: $lastSnapshot")
    }

    // ---- Fixture ----

    /**
     * A classic PCAP (link type 1) carrying one TLS 1.3 session: ClientHello,
     * ServerHello and two Application Data records. The Application Data
     * bodies are opaque filler — the tab's structure is what is asserted, and
     * that depends on the key log being present, not on the records opening.
     */
    private fun tlsCapture(): ByteArray {
        val payloads = listOf(
            true to clientHelloRecord(),
            false to serverHelloRecord(),
            true to applicationDataRecord(size = 64),
            false to applicationDataRecord(size = 96),
        )
        var out = globalHeader()
        payloads.forEachIndexed { index, (fromClient, record) ->
            val packet = ethernetIpv4TcpPacket(fromClient, record)
            out += packetHeader(tsSec = 1_700_000_000L + index, length = packet.size)
            out += packet
        }
        return out
    }

    /**
     * A key log for [CLIENT_RANDOM_BYTES]. One client random means one
     * session, which is what the Packets prologue is asserted to report.
     */
    private fun keyLogText(): String {
        val clientRandom = hex(CLIENT_RANDOM_BYTES)
        val clientSecret = hex(ByteArray(32) { (it * 3 + 7).toByte() })
        val serverSecret = hex(ByteArray(32) { (it * 5 + 11).toByte() })
        return """
            # Generated by the Hexana UI driver test.
            CLIENT_TRAFFIC_SECRET_0 $clientRandom $clientSecret
            SERVER_TRAFFIC_SECRET_0 $clientRandom $serverSecret
        """.trimIndent() + "\n"
    }

    /** Trimmed to what the handshake scan reads: type, length, version, random. */
    private fun clientHelloRecord(): ByteArray {
        val body = byteArrayOf(0x03, 0x03) + CLIENT_RANDOM_BYTES
        return tlsRecord(HANDSHAKE, byteArrayOf(CLIENT_HELLO, 0, 0, body.size.toByte()) + body)
    }

    /** Carries the cipher suite plus the `supported_versions` extension that signals TLS 1.3. */
    private fun serverHelloRecord(): ByteArray {
        val supportedVersions = byteArrayOf(0x00, 0x2B, 0x00, 0x02, 0x03, 0x04)
        val body = byteArrayOf(0x03, 0x03) + ByteArray(32) { (0x40 + it).toByte() } +
            byteArrayOf(0x00) + // session_id_length
            byteArrayOf(0x13, 0x01) + // TLS_AES_128_GCM_SHA256
            byteArrayOf(0x00) + // compression_method
            byteArrayOf(0x00, supportedVersions.size.toByte()) + supportedVersions
        return tlsRecord(HANDSHAKE, byteArrayOf(SERVER_HELLO, 0, 0, body.size.toByte()) + body)
    }

    private fun applicationDataRecord(size: Int): ByteArray =
        tlsRecord(APPLICATION_DATA, ByteArray(size) { (it * 31 + 13).toByte() })

    private fun tlsRecord(contentType: Byte, payload: ByteArray): ByteArray =
        byteArrayOf(
            contentType, 0x03, 0x03,
            (payload.size shr 8).toByte(), (payload.size and 0xFF).toByte(),
        ) + payload

    private fun ethernetIpv4TcpPacket(fromClient: Boolean, payload: ByteArray): ByteArray {
        val ethernet = ByteArray(14).also { it[12] = 0x08 } // IPv4
        val ipTotalLength = 20 + 20 + payload.size
        val ip = ByteArray(20)
        ip[0] = 0x45 // IPv4, 5-word header
        ip[2] = (ipTotalLength shr 8).toByte()
        ip[3] = (ipTotalLength and 0xFF).toByte()
        ip[8] = 64 // TTL
        ip[9] = 6 // TCP
        val client = byteArrayOf(10, 0, 0, 1)
        val server = byteArrayOf(10, 0, 0, 2)
        (if (fromClient) client else server).copyInto(ip, 12)
        (if (fromClient) server else client).copyInto(ip, 16)

        val srcPort = if (fromClient) CLIENT_PORT else SERVER_PORT
        val dstPort = if (fromClient) SERVER_PORT else CLIENT_PORT
        val tcp = ByteArray(20)
        tcp[0] = (srcPort shr 8).toByte()
        tcp[1] = (srcPort and 0xFF).toByte()
        tcp[2] = (dstPort shr 8).toByte()
        tcp[3] = (dstPort and 0xFF).toByte()
        tcp[12] = 0x50 // data offset = 5 words
        tcp[13] = 0x18 // PSH + ACK

        return ethernet + ip + tcp + payload
    }

    private fun globalHeader(): ByteArray = ByteArray(24).also { buf ->
        // 0xA1B2C3D4 little-endian, version 2.4, microsecond timestamps.
        buf[0] = 0xD4.toByte(); buf[1] = 0xC3.toByte()
        buf[2] = 0xB2.toByte(); buf[3] = 0xA1.toByte()
        buf[4] = 2; buf[6] = 4
        putU32Le(buf, 16, 65_535)
        putU32Le(buf, 20, LINK_TYPE_ETHERNET)
    }

    private fun packetHeader(tsSec: Long, length: Int): ByteArray = ByteArray(16).also { buf ->
        putU32Le(buf, 0, tsSec)
        putU32Le(buf, 8, length.toLong())
        putU32Le(buf, 12, length.toLong())
    }

    private fun putU32Le(buf: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> buf[offset + index] = ((value shr (index * 8)) and 0xFF).toByte() }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val LINK_TYPE_ETHERNET = 1L
        const val CLIENT_PORT = 49_167
        const val SERVER_PORT = 443
        const val HANDSHAKE: Byte = 0x16
        const val APPLICATION_DATA: Byte = 0x17
        const val CLIENT_HELLO: Byte = 0x01
        const val SERVER_HELLO: Byte = 0x02
        val CLIENT_RANDOM_BYTES = ByteArray(32) { (it + 1).toByte() }
    }
}
