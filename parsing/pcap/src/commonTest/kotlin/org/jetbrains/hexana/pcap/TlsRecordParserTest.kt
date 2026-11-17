package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TlsRecordParserTest {

    private fun wrap(bytes: ByteArray) = bytes.toCommonByteBuffer()

    /** Builds a minimal TLS record header. */
    private fun tlsRecordHeader(
        contentType: Int = TlsContentType.HANDSHAKE,
        versionMajor: Int = 3,
        versionMinor: Int = 3,
        length: Int = 0,
    ): ByteArray {
        return byteArrayOf(
            contentType.toByte(),
            versionMajor.toByte(),
            versionMinor.toByte(),
            (length shr 8).toByte(),
            (length and 0xFF).toByte(),
        )
    }

    @Test
    fun detectsValidTlsRecord() {
        val data = tlsRecordHeader(TlsContentType.HANDSHAKE, 3, 3, 100)
        assertTrue(TlsRecordParser.isTlsRecord(wrap(data), 0, data.size))
    }

    @Test
    fun rejectsTooShort() {
        assertFalse(TlsRecordParser.isTlsRecord(wrap(ByteArray(3)), 0, 3))
    }

    @Test
    fun rejectsInvalidContentType() {
        val data = byteArrayOf(0x19.toByte(), 3, 3, 0, 10)
        assertFalse(TlsRecordParser.isTlsRecord(wrap(data), 0, data.size))
    }

    @Test
    fun rejectsInvalidVersion() {
        val data = byteArrayOf(22, 4, 0, 0, 10) // version 4.0
        assertFalse(TlsRecordParser.isTlsRecord(wrap(data), 0, data.size))
    }

    @Test
    fun rejectsTooLargeLength() {
        // length = 20000 > 16640
        val data = byteArrayOf(22, 3, 3, 0x4E.toByte(), 0x20.toByte())
        assertFalse(TlsRecordParser.isTlsRecord(wrap(data), 0, data.size))
    }

    @Test
    fun parsesRecordHeader() {
        val data = tlsRecordHeader(TlsContentType.APPLICATION_DATA, 3, 3, 256)
        val hdr = TlsRecordParser.parseRecordHeader(wrap(data), 0, data.size)
        assertNotNull(hdr)
        assertEquals(TlsContentType.APPLICATION_DATA, hdr.contentType)
        assertEquals(3, hdr.versionMajor)
        assertEquals(3, hdr.versionMinor)
        assertEquals(256, hdr.length)
        assertEquals("TLS 1.2", hdr.versionString)
        assertEquals("Application Data", hdr.contentTypeName)
    }

    @Test
    fun dissectsClientHello() {
        // TLS record: Handshake, TLS 1.0, length=5
        // Handshake: ClientHello (type=1), length=2, version=0x0303, random=...
        val record = tlsRecordHeader(TlsContentType.HANDSHAKE, 3, 1, 5)
        val handshake = byteArrayOf(
            TlsHandshakeType.CLIENT_HELLO.toByte(), // type
            0, 0, 2, // length (3 bytes)
            3, 3, // client version
        )
        val data = record + handshake
        val result = TlsRecordParser.dissect(wrap(data), 0, data.size)
        assertNotNull(result)
        assertTrue(result.info.contains("Client Hello"))
        assertFalse(result.hasEncryptedData)
    }

    @Test
    fun dissectsApplicationData() {
        val payload = ByteArray(100)
        val record = tlsRecordHeader(TlsContentType.APPLICATION_DATA, 3, 3, payload.size)
        val data = record + payload
        val result = TlsRecordParser.dissect(wrap(data), 0, data.size)
        assertNotNull(result)
        assertTrue(result.info.contains("Application Data"))
        assertTrue(result.hasEncryptedData)
        assertTrue(result.protocol.contains("1.2"))
    }

    @Test
    fun dissectsMultipleRecords() {
        val hs = tlsRecordHeader(TlsContentType.HANDSHAKE, 3, 3, 1) +
            byteArrayOf(TlsHandshakeType.SERVER_HELLO.toByte())
        val ccs = tlsRecordHeader(TlsContentType.CHANGE_CIPHER_SPEC, 3, 3, 1) +
            byteArrayOf(1)
        val data = hs + ccs
        val result = TlsRecordParser.dissect(wrap(data), 0, data.size)
        assertNotNull(result)
        assertTrue(result.info.contains("Server Hello"))
        assertTrue(result.info.contains("Change Cipher Spec"))
    }

    @Test
    fun returnsNullForNonTls() {
        val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertNull(TlsRecordParser.dissect(wrap(data), 0, data.size))
    }

    @Test
    fun extractsClientRandom() {
        // Handshake type=1 (ClientHello), length=38, version=0x0303, random=32 bytes
        val random = ByteArray(32) { (it + 1).toByte() }
        val handshake = byteArrayOf(
            1, // ClientHello
            0, 0, 34, // length
            3, 3, // version
        ) + random
        val result = TlsRecordParser.extractClientRandom(wrap(handshake), 0, handshake.size)
        assertNotNull(result)
        assertTrue(random.contentEquals(result))
    }

    @Test
    fun extractClientRandomReturnsNullForServerHello() {
        val handshake = byteArrayOf(2, 0, 0, 34, 3, 3) + ByteArray(32)
        assertNull(TlsRecordParser.extractClientRandom(wrap(handshake), 0, handshake.size))
    }

    @Test
    fun versionStrings() {
        assertEquals("SSL 3.0", TlsRecordHeader(22, 3, 0, 0).versionString)
        assertEquals("TLS 1.0", TlsRecordHeader(22, 3, 1, 0).versionString)
        assertEquals("TLS 1.1", TlsRecordHeader(22, 3, 2, 0).versionString)
        assertEquals("TLS 1.2", TlsRecordHeader(22, 3, 3, 0).versionString)
        assertEquals("TLS 1.3", TlsRecordHeader(22, 3, 4, 0).versionString)
    }
}
