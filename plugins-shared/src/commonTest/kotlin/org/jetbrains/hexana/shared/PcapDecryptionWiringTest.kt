package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.pcap.PcapDissectedPacket
import org.jetbrains.hexana.pcap.SslKeyLog
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.PcapBinaryProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the shared wiring between a loaded SSL key log and the "Packets" tab.
 * Decryption itself is covered in `parsing/pcap`; what is asserted here is that
 * loading a key log is enough to change the tab — no separate decrypting entry
 * point — and that tab names are answerable without parsing.
 */
class PcapDecryptionWiringTest {

    private val provider = TableTabsProviderRegistry.providers
        .filterIsInstance<PcapTableTabsProvider>()
        .single()

    @Test
    fun `pcap provider is registered`() {
        // The single() above would have thrown; this states the intent.
        assertNotNull(provider)
    }

    @Test
    fun `tab names are known without parsing`() {
        val names = provider.availableTabNames(pcapProvider(), TableTabsContext())
        assertEquals(listOf("Header", "Packets") to emptyList<String>(), names)
    }

    @Test
    fun `non-pcap providers get no cheap names and no tabs`() {
        val other = GenericBinaryProvider(ByteArray(64).toCommonByteBuffer())
        assertNull(provider.availableTabNames(other, TableTabsContext()))
        assertEquals(emptyList(), provider.tableTabs(other))
    }

    @Test
    fun `without a key log there is no Decrypted column and no prologue about keys`() {
        val packets = packetsTab(pcapProvider())
        assertFalse(packets.columns.any { it.name == "Decrypted" })
        assertNull(packets.prologueText)
    }

    @Test
    fun `loading a key log alone adds the Decrypted column`() {
        val pcap = pcapProvider()
        // Same provider instance, no separate decrypting factory: installing the
        // key log and rebuilding is the whole mechanism the hosts rely on.
        assertFalse(packetsTab(pcap).columns.any { it.name == "Decrypted" })
        pcap.sslKeyLog = keyLog(sessions = 2)
        val packets = packetsTab(pcap)
        assertTrue(packets.columns.any { it.name == "Decrypted" })
        assertEquals("Info", packets.columns[packets.columns.size - 2].name)
    }

    @Test
    fun `prologue reports the session count`() {
        val pcap = pcapProvider()
        pcap.sslKeyLog = keyLog(sessions = 3)
        val prologue = assertNotNull(packetsTab(pcap).prologueText)
        assertContains(prologue, "3 sessions")
    }

    @Test
    fun `both tabs are produced and the advertised names match them`() {
        val pcap = pcapProvider()
        pcap.sslKeyLog = keyLog(sessions = 1)
        val tabs = provider.tableTabs(pcap)
        assertEquals(listOf("Header", "Packets"), tabs.map { it.name })
        val (ordinary, grouped) = assertNotNull(provider.availableTabNames(pcap, TableTabsContext()))
        assertEquals(tabs.map { it.name }, ordinary)
        assertEquals(emptyList(), grouped)
    }

    /**
     * Hosts that resolve tabs one name at a time - the IntelliJ editor gives
     * each advertised name its own coroutine - must not end up building the
     * whole tab list per name: for a capture with a key log that repeats the
     * handshake scan and the key derivation concurrently over one buffer. So
     * every advertised name has to resolve on its own here.
     */
    @Test
    fun `each advertised name resolves on its own`() {
        val pcap = pcapProvider()
        pcap.sslKeyLog = keyLog(sessions = 2)
        val context = TableTabsContext()
        val header = assertNotNull(provider.singleTab("Header", pcap, context))
        assertEquals("Header", header.name)
        assertEquals("PCAP Capture File", header.prologueText)
        val packets = assertNotNull(provider.singleTab("Packets", pcap, context))
        assertEquals("Packets", packets.name)
        assertTrue(packets.columns.any { it.name == "Decrypted" })
        assertContains(assertNotNull(packets.prologueText), "2 sessions")
        assertNull(provider.singleTab("Nonexistent", pcap, context))
        assertNull(provider.singleTab("Header", GenericBinaryProvider(ByteArray(64).toCommonByteBuffer()), context))
    }

    /**
     * The detail panel is what makes decrypted plaintext readable at all, since
     * those bytes are nowhere in the file for the hex view to point at. Without
     * a key log there is nothing to open, so the hook stays absent.
     */
    @Test
    fun `the row detail hook appears only once a key log is loaded`() {
        val pcap = pcapProvider()
        assertNull(packetsTab(pcap).rowDetailProvider)
        pcap.sslKeyLog = keyLog(sessions = 1)
        val provider = assertNotNull(packetsTab(pcap).rowDetailProvider)
        // These synthetic packets carry no real records, so no row decrypts and
        // every one of them reports nothing to show rather than stale bytes.
        assertNull(provider(0))
    }

    @Test
    fun `a decrypted row is labelled by its packet number, protocol and size`() {
        val detail = assertNotNull(
            decryptedRowDetail(
                PcapDissectedPacket(
                    source = "10.0.0.1",
                    destination = "10.0.0.2",
                    protocol = "HTTP",
                    info = "GET / HTTP/1.0",
                    decryptedData = "GET / HTTP/1.0\r\n\r\n".encodeToByteArray(),
                ),
                index = 10,
            ),
        )
        assertEquals("Decrypted TLS", detail.title)
        assertEquals("packet #11 · HTTP · 18 bytes", detail.subtitle)
        assertEquals(18, detail.bytes.size)
    }

    @Test
    fun `a row that did not decrypt has no detail`() {
        val packet = PcapDissectedPacket("10.0.0.1", "10.0.0.2", "TLS 1.2", "Application Data")
        assertNull(decryptedRowDetail(packet, index = 0))
    }

    /**
     * `availableTabNames` advertises "Packets" from the provider type alone, so
     * an empty capture leaves a name with nothing behind it. Hosts handle that
     * by dropping the placeholder — assert the descriptor really is absent so
     * the contract stays explicit.
     */
    @Test
    fun `an empty capture advertises Packets but produces no such tab`() {
        val empty = pcapProvider(payloads = emptyList())
        assertEquals(
            listOf("Header", "Packets"),
            assertNotNull(provider.availableTabNames(empty, TableTabsContext())).first,
        )
        assertEquals(listOf("Header"), provider.tableTabs(empty).map { it.name })
        assertNull(provider.singleTab("Packets", empty, TableTabsContext()))
    }

    // ---- fixture ----

    private fun packetsTab(pcap: PcapBinaryProvider): TableTabDescriptor =
        assertNotNull(provider.tableTabs(pcap).firstOrNull { it.name == "Packets" })

    /** A key log with [sessions] distinct CLIENT_RANDOM entries. */
    private fun keyLog(sessions: Int): SslKeyLog {
        val text = (0 until sessions).joinToString("\n") { i ->
            val random = hexByte(i + 1).repeat(32)
            val secret = hexByte(i + 0x40).repeat(48)
            "CLIENT_RANDOM $random $secret"
        }
        return SslKeyLog.parse(text).also { assertEquals(sessions, it.sessionCount) }
    }

    /** Two lowercase hex digits. `String.format` is JVM-only. */
    private fun hexByte(value: Int): String {
        val digits = "0123456789abcdef"
        return "${digits[(value shr 4) and 0xF]}${digits[value and 0xF]}"
    }

    private fun pcapProvider(payloads: List<ByteArray> = listOf(ByteArray(8) { 0x16 })): PcapBinaryProvider {
        val bytes = buildClassicPcap(payloads)
        assertTrue(PcapBinaryProvider.isPcapFile(bytes.toCommonByteBuffer()))
        return PcapBinaryProvider(bytes.toCommonByteBuffer())
    }

    /**
     * Minimal classic pcap: little-endian global header with link type 1
     * (Ethernet) plus one Ethernet/IPv4/TCP packet per payload. Only the shape
     * the parser reads is filled in — checksums are left zero.
     */
    private fun buildClassicPcap(payloads: List<ByteArray>): ByteArray {
        val out = mutableListOf<Byte>()
        fun u16le(v: Int) { out += (v and 0xFF).toByte(); out += ((v shr 8) and 0xFF).toByte() }
        fun u32le(v: Long) {
            for (shift in 0..24 step 8) out += ((v shr shift) and 0xFF).toByte()
        }
        u32le(0xA1B2C3D4L) // magic
        u16le(2); u16le(4) // version 2.4
        u32le(0L); u32le(0L) // thiszone, sigfigs
        u32le(65535L) // snaplen
        u32le(1L) // link type: Ethernet

        for ((index, payload) in payloads.withIndex()) {
            val packet = ethernetIpv4Tcp(payload)
            u32le(1000L + index); u32le(0L)
            u32le(packet.size.toLong()); u32le(packet.size.toLong())
            out += packet.toList()
        }
        return out.toByteArray()
    }

    private fun ethernetIpv4Tcp(payload: ByteArray): ByteArray {
        val ethernet = ByteArray(14).also { it[12] = 0x08 } // EtherType 0x0800
        val ip = ByteArray(20)
        ip[0] = 0x45 // IPv4, 5-word header
        val total = 20 + 20 + payload.size
        ip[2] = ((total shr 8) and 0xFF).toByte()
        ip[3] = (total and 0xFF).toByte()
        ip[9] = 6 // TCP
        byteArrayOf(10, 0, 0, 1).copyInto(ip, 12)
        byteArrayOf(10, 0, 0, 2).copyInto(ip, 16)
        val tcp = ByteArray(20)
        tcp[0] = 0xC0.toByte(); tcp[1] = 0x0F.toByte() // source port 49167
        tcp[2] = 0x01; tcp[3] = 0xBB.toByte() // destination port 443
        tcp[12] = 0x50 // data offset 5 words
        tcp[13] = 0x18 // PSH | ACK
        return ethernet + ip + tcp + payload
    }
}
