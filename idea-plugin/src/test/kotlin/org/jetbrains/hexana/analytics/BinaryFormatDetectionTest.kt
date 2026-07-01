package org.jetbrains.hexana.analytics

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the GENERIC-bucket breakdown: the magic-byte sniffer in
 * [genericDetailOf]. Extension-to-bucket mapping is covered at the enum
 * level in `HexanaEventTest.genericExtFromExtensionAllowlist`.
 */
class BinaryFormatDetectionTest {

    private fun detect(vararg head: Int): HexanaEvent.DetectedFormat? {
        // Pad to 16 bytes so the sniffer always has a full header window.
        val bytes = ByteArray(16) { i -> head.getOrElse(i) { 0 }.toByte() }
        val provider = GenericBinaryProvider(bytes.toCommonByteBuffer())
        return genericDetailOf(file = null, provider = provider).detected
    }

    @Test
    fun detectsGguf() = assertEquals(HexanaEvent.DetectedFormat.GGUF, detect(0x47, 0x47, 0x55, 0x46))

    @Test
    fun detectsParquet() = assertEquals(HexanaEvent.DetectedFormat.PARQUET, detect(0x50, 0x41, 0x52, 0x31))

    @Test
    fun detectsArrowIpc() =
        assertEquals(HexanaEvent.DetectedFormat.ARROW_IPC, detect(0x41, 0x52, 0x52, 0x4F, 0x57, 0x31))

    @Test
    fun detectsSpirvLittleEndian() = assertEquals(HexanaEvent.DetectedFormat.SPIRV, detect(0x03, 0x02, 0x23, 0x07))

    @Test
    fun detectsSpirvBigEndian() = assertEquals(HexanaEvent.DetectedFormat.SPIRV, detect(0x07, 0x23, 0x02, 0x03))

    @Test
    fun detectsDex() = assertEquals(HexanaEvent.DetectedFormat.DEX, detect(0x64, 0x65, 0x78, 0x0A))

    @Test
    fun detectsSqlite() =
        assertEquals(HexanaEvent.DetectedFormat.SQLITE, detect(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65))

    @Test
    fun detectsZip() = assertEquals(HexanaEvent.DetectedFormat.ZIP, detect(0x50, 0x4B, 0x03, 0x04))

    @Test
    fun detectsGzip() = assertEquals(HexanaEvent.DetectedFormat.GZIP, detect(0x1F, 0x8B, 0x08))

    @Test
    fun detectsPng() = assertEquals(HexanaEvent.DetectedFormat.PNG, detect(0x89, 0x50, 0x4E, 0x47))

    @Test
    fun detectsZstd() = assertEquals(HexanaEvent.DetectedFormat.ZSTD, detect(0x28, 0xB5, 0x2F, 0xFD))

    @Test
    fun detectsSevenZip() =
        assertEquals(HexanaEvent.DetectedFormat.SEVENZIP, detect(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C))

    @Test
    fun detectsXz() = assertEquals(HexanaEvent.DetectedFormat.XZ, detect(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00))

    @Test
    fun detectsBzip2() = assertEquals(HexanaEvent.DetectedFormat.BZIP2, detect(0x42, 0x5A, 0x68, 0x39))

    @Test
    fun detectsRar() = assertEquals(HexanaEvent.DetectedFormat.RAR, detect(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))

    @Test
    fun detectsLz4() = assertEquals(HexanaEvent.DetectedFormat.LZ4, detect(0x04, 0x22, 0x4D, 0x18))

    @Test
    fun detectsCab() = assertEquals(HexanaEvent.DetectedFormat.CAB, detect(0x4D, 0x53, 0x43, 0x46))

    @Test
    fun detectsTarByUstarAtOffset257() {
        // tar has no offset-0 magic; "ustar" lives at byte 257. Build a full
        // 512-byte record with an arbitrary filename head and the marker in place.
        val bytes = ByteArray(512)
        "hello.txt".encodeToByteArray().copyInto(bytes)
        byteArrayOf(0x75, 0x73, 0x74, 0x61, 0x72).copyInto(bytes, destinationOffset = 257)
        val provider = GenericBinaryProvider(bytes.toCommonByteBuffer())
        assertEquals(HexanaEvent.DetectedFormat.TAR, genericDetailOf(file = null, provider = provider).detected)
    }

    @Test
    fun unknownMagicIsNull() = assertNull(detect(0xDE, 0xAD, 0xBE, 0xEF))

    @Test
    fun shorterThanFourBytesIsNull() {
        val provider = GenericBinaryProvider(byteArrayOf(0x47, 0x47).toCommonByteBuffer())
        assertNull(genericDetailOf(file = null, provider = provider).detected)
    }

    @Test
    fun nullProviderYieldsNoMagicAndOtherExt() {
        val detail = genericDetailOf(file = null, provider = null)
        assertNull(detail.detected)
        assertEquals(HexanaEvent.GenericExt.OTHER, detail.ext)
    }
}