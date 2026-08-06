package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcapParserTest {

    /** Builds a minimal little-endian PCAP global header (24 bytes). */
    private fun leGlobalHeader(
        snapLen: Long = 65535,
        network: Long = 1, // Ethernet
    ): ByteArray {
        val buf = ByteArray(24)
        // Magic: 0xA1B2C3D4 in LE → D4 C3 B2 A1
        buf[0] = 0xD4.toByte(); buf[1] = 0xC3.toByte()
        buf[2] = 0xB2.toByte(); buf[3] = 0xA1.toByte()
        // Version 2.4 LE
        buf[4] = 2; buf[5] = 0; buf[6] = 4; buf[7] = 0
        // thiszone = 0, sigfigs = 0 (bytes 8..15 stay zero)
        // snaplen LE
        putU32LE(buf, 16, snapLen)
        // network LE
        putU32LE(buf, 20, network)
        return buf
    }

    /** Builds a 16-byte LE packet record header. */
    private fun lePacketHeader(tsSec: Long, tsUsec: Long, inclLen: Long, origLen: Long): ByteArray {
        val buf = ByteArray(16)
        putU32LE(buf, 0, tsSec)
        putU32LE(buf, 4, tsUsec)
        putU32LE(buf, 8, inclLen)
        putU32LE(buf, 12, origLen)
        return buf
    }

    private fun putU32LE(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value and 0xFF).toByte()
        buf[off + 1] = ((value shr 8) and 0xFF).toByte()
        buf[off + 2] = ((value shr 16) and 0xFF).toByte()
        buf[off + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun wrap(bytes: ByteArray): CommonByteBuffer = bytes.toCommonByteBuffer()

    @Test
    fun detectsPcapMagic() {
        assertTrue(PcapParser.isPcap(wrap(leGlobalHeader())))
    }

    @Test
    fun rejectsNonPcap() {
        assertFalse(PcapParser.isPcap(wrap(ByteArray(24))))
    }

    @Test
    fun rejectsTooShort() {
        assertFalse(PcapParser.isPcap(wrap(ByteArray(4))))
    }

    @Test
    fun parsesGlobalHeader() {
        val header = PcapParser.parseGlobalHeader(wrap(leGlobalHeader(snapLen = 262144, network = 113)))
        assertNotNull(header)
        assertEquals(2, header.versionMajor)
        assertEquals(4, header.versionMinor)
        assertEquals(262144L, header.snapLen)
        assertEquals(113L, header.network)
        assertEquals("Linux cooked capture", header.networkName)
        assertFalse(header.bigEndian)
    }

    @Test
    fun parsesPackets() {
        val globalHeader = leGlobalHeader()
        val pkt1Data = ByteArray(10) { it.toByte() }
        val pkt1Header = lePacketHeader(tsSec = 1000, tsUsec = 500, inclLen = 10, origLen = 14)
        val pkt2Data = ByteArray(5) { (it + 20).toByte() }
        val pkt2Header = lePacketHeader(tsSec = 1001, tsUsec = 0, inclLen = 5, origLen = 5)

        val allBytes = globalHeader + pkt1Header + pkt1Data + pkt2Header + pkt2Data
        val file = PcapParser.parse(wrap(allBytes))
        assertNotNull(file)
        assertEquals(2, file.packets.size)

        val p1 = file.packets[0]
        assertEquals(1000L, p1.tsSec)
        assertEquals(500L, p1.tsUsec)
        assertEquals(10L, p1.inclLen)
        assertEquals(14L, p1.origLen)
        assertEquals(24L, p1.fileOffset) // right after global header

        val p2 = file.packets[1]
        assertEquals(1001L, p2.tsSec)
        assertEquals(5L, p2.inclLen)
        assertEquals(50L, p2.fileOffset) // 24 + 16 + 10
    }

    @Test
    fun parseReturnsNullForBadMagic() {
        assertNull(PcapParser.parse(wrap(ByteArray(24))))
    }

    @Test
    fun bigEndianHeader() {
        val buf = ByteArray(24)
        // Magic: 0xD4C3B2A1 in LE = big-endian PCAP → A1 B2 C3 D4 on disk
        // But readMagic reads LE, so for BE magic (0xD4C3B2A1) the bytes on disk are:
        // A1 B2 C3 D4
        buf[0] = 0xA1.toByte(); buf[1] = 0xB2.toByte()
        buf[2] = 0xC3.toByte(); buf[3] = 0xD4.toByte()
        // Version 2.4 BE
        buf[4] = 0; buf[5] = 2; buf[6] = 0; buf[7] = 4
        // thiszone, sigfigs = 0
        // snaplen = 65535 BE
        buf[16] = 0; buf[17] = 0; buf[18] = 0xFF.toByte(); buf[19] = 0xFF.toByte()
        // network = 1 BE
        buf[20] = 0; buf[21] = 0; buf[22] = 0; buf[23] = 1

        val header = PcapParser.parseGlobalHeader(wrap(buf))
        assertNotNull(header)
        assertTrue(header.bigEndian)
        assertEquals(2, header.versionMajor)
        assertEquals(4, header.versionMinor)
        assertEquals(65535L, header.snapLen)
        assertEquals(1L, header.network)
    }
}
