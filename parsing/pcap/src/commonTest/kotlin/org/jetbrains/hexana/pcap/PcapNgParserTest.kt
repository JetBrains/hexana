package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcapNgParserTest {

    /**
     * Builds a minimal little-endian PCAP-NG Section Header Block.
     * Block layout: type(4) + totalLen(4) + BOM(4) + major(2) + minor(2) + sectionLen(8) + trailingLen(4)
     * = 28 bytes minimum.
     */
    private fun leSectionHeaderBlock(): ByteArray {
        val buf = ByteArray(28)
        // Block type: 0x0A0D0D0A
        buf[0] = 0x0A; buf[1] = 0x0D; buf[2] = 0x0D; buf[3] = 0x0A
        // Total length: 28 (LE)
        putU32LE(buf, 4, 28)
        // BOM: 0x1A2B3C4D stored as LE on disk → bytes 4D 3C 2B 1A
        buf[8] = 0x4D; buf[9] = 0x3C; buf[10] = 0x2B; buf[11] = 0x1A
        // Version 1.0 (LE)
        putU16LE(buf, 12, 1)
        putU16LE(buf, 14, 0)
        // Section length: -1 (unspecified)
        for (i in 16..23) buf[i] = 0xFF.toByte()
        // Trailing total length
        putU32LE(buf, 24, 28)
        return buf
    }

    /**
     * Builds a little-endian Interface Description Block.
     * Body: linkType(2) + reserved(2) + snapLen(4) = 8 bytes body.
     * Block: type(4) + totalLen(4) + body(8) + trailingLen(4) = 20 bytes.
     */
    private fun leInterfaceDescBlock(linkType: Int = 1, snapLen: Long = 65535): ByteArray {
        val totalLen = 20
        val buf = ByteArray(totalLen)
        putU32LE(buf, 0, 1) // BT_IDB
        putU32LE(buf, 4, totalLen.toLong())
        putU16LE(buf, 8, linkType)
        putU16LE(buf, 10, 0) // reserved
        putU32LE(buf, 12, snapLen)
        putU32LE(buf, 16, totalLen.toLong())
        return buf
    }

    /**
     * Builds a little-endian Enhanced Packet Block.
     * Body: interfaceId(4) + tsHigh(4) + tsLow(4) + capLen(4) + origLen(4) + data + padding + trailingLen(4).
     */
    private fun leEnhancedPacketBlock(
        interfaceId: Int = 0,
        tsHigh: Long = 0,
        tsLow: Long = 0,
        data: ByteArray = ByteArray(0),
    ): ByteArray {
        val capLen = data.size
        val paddedLen = (capLen + 3) and 3.inv()
        val totalLen = 32 + paddedLen // type(4)+len(4)+body(20)+data(padded)+trailingLen(4)
        val buf = ByteArray(totalLen)
        putU32LE(buf, 0, 6) // BT_EPB
        putU32LE(buf, 4, totalLen.toLong())
        putU32LE(buf, 8, interfaceId.toLong())
        putU32LE(buf, 12, tsHigh)
        putU32LE(buf, 16, tsLow)
        putU32LE(buf, 20, capLen.toLong()) // captured length
        putU32LE(buf, 24, capLen.toLong()) // original length
        data.copyInto(buf, 28)
        putU32LE(buf, totalLen - 4, totalLen.toLong())
        return buf
    }

    private fun putU32LE(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value and 0xFF).toByte()
        buf[off + 1] = ((value shr 8) and 0xFF).toByte()
        buf[off + 2] = ((value shr 16) and 0xFF).toByte()
        buf[off + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putU16LE(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value and 0xFF).toByte()
        buf[off + 1] = ((value shr 8) and 0xFF).toByte()
    }

    @Test
    fun detectsPcapNgMagic() {
        assertTrue(PcapNgParser.isPcapNg(leSectionHeaderBlock().toCommonByteBuffer()))
    }

    @Test
    fun rejectsNonPcapNg() {
        assertFalse(PcapNgParser.isPcapNg(ByteArray(28).toCommonByteBuffer()))
    }

    @Test
    fun rejectsTooShort() {
        assertFalse(PcapNgParser.isPcapNg(ByteArray(8).toCommonByteBuffer()))
    }

    @Test
    fun parsesMinimalFile() {
        val shb = leSectionHeaderBlock()
        val idb = leInterfaceDescBlock(linkType = 1, snapLen = 65535)
        val file = PcapNgParser.parse((shb + idb).toCommonByteBuffer())
        assertNotNull(file)
        assertEquals(1, file.interfaces.size)
        assertEquals(1L, file.interfaces[0].linkType)
        assertEquals(65535L, file.interfaces[0].snapLen)
        assertEquals(0, file.packets.size)
    }

    @Test
    fun parsesPackets() {
        val shb = leSectionHeaderBlock()
        val idb = leInterfaceDescBlock()
        val pktData = ByteArray(10) { it.toByte() }
        val epb = leEnhancedPacketBlock(tsHigh = 0, tsLow = 1_000_000, data = pktData)

        val allBytes = shb + idb + epb
        val file = PcapNgParser.parse(allBytes.toCommonByteBuffer())
        assertNotNull(file)
        assertEquals(1, file.packets.size)

        val p = file.packets[0]
        assertEquals(1L, p.tsSec)
        assertEquals(0L, p.tsUsec)
        assertEquals(10L, p.inclLen)
        assertEquals(10L, p.origLen)
    }

    @Test
    fun parseReturnsNullForBadMagic() {
        assertNull(PcapNgParser.parse(ByteArray(28).toCommonByteBuffer()))
    }

    @Test
    fun multiplePackets() {
        val shb = leSectionHeaderBlock()
        val idb = leInterfaceDescBlock()
        val epb1 = leEnhancedPacketBlock(tsLow = 1_000_000, data = ByteArray(4))
        val epb2 = leEnhancedPacketBlock(tsLow = 2_000_000, data = ByteArray(8))

        val file = PcapNgParser.parse((shb + idb + epb1 + epb2).toCommonByteBuffer())
        assertNotNull(file)
        assertEquals(2, file.packets.size)
        assertEquals(1L, file.packets[0].tsSec)
        assertEquals(2L, file.packets[1].tsSec)
    }

    @Test
    fun headerReflectsInterface() {
        val shb = leSectionHeaderBlock()
        val idb = leInterfaceDescBlock(linkType = 113, snapLen = 262144)
        val file = PcapNgParser.parse((shb + idb).toCommonByteBuffer())
        assertNotNull(file)
        assertEquals(113L, file.header.network)
        assertEquals(262144L, file.header.snapLen)
        assertEquals("Linux cooked capture", file.header.networkName)
    }
}
