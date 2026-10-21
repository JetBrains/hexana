package org.jetbrains.hexana.analytics

import com.intellij.mock.MockVirtualFile
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.ClassFileBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.ProtobufMessageBinaryProvider
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the GENERIC-bucket breakdown: the magic-byte sniffer in
 * [genericDetailOf]. Extension-to-bucket mapping is covered at the enum
 * level in `HexanaEventTest.genericExtFromExtensionAllowlist`.
 *
 * Also covers [binaryFormatOf] for the supported formats whose providers extend
 * [GenericBinaryProvider] or used to fall through to the extension switch, so
 * none of them is reported under the GENERIC catch-all.
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
    fun detectsNpy() = assertEquals(HexanaEvent.DetectedFormat.NPY, detect(0x93, 0x4E, 0x55, 0x4D, 0x50, 0x59))

    @Test
    fun detectsLlvmBitcode() = assertEquals(HexanaEvent.DetectedFormat.BITCODE, detect(0x42, 0x43, 0xC0, 0xDE))

    @Test
    fun detectsPcapLittleEndian() = assertEquals(HexanaEvent.DetectedFormat.PCAP, detect(0xD4, 0xC3, 0xB2, 0xA1))

    @Test
    fun detectsPcapBigEndian() = assertEquals(HexanaEvent.DetectedFormat.PCAP, detect(0xA1, 0xB2, 0xC3, 0xD4))

    @Test
    fun detectsPcapng() = assertEquals(HexanaEvent.DetectedFormat.PCAPNG, detect(0x0A, 0x0D, 0x0D, 0x0A))

    @Test
    fun detectsJpeg() = assertEquals(HexanaEvent.DetectedFormat.JPEG, detect(0xFF, 0xD8, 0xFF, 0xE0))

    @Test
    fun detectsGif() = assertEquals(HexanaEvent.DetectedFormat.GIF, detect(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))

    @Test
    fun detectsBmp() = assertEquals(HexanaEvent.DetectedFormat.BMP, detect(0x42, 0x4D))

    @Test
    fun detectsWebp() =
        // "RIFF" at 0, 4-byte size, then "WEBP" at offset 8.
        assertEquals(
            HexanaEvent.DetectedFormat.WEBP,
            detect(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50),
        )

    @Test
    fun detectsTiffLittleEndian() = assertEquals(HexanaEvent.DetectedFormat.TIFF, detect(0x49, 0x49, 0x2A, 0x00))

    @Test
    fun detectsTiffBigEndian() = assertEquals(HexanaEvent.DetectedFormat.TIFF, detect(0x4D, 0x4D, 0x00, 0x2A))

    @Test
    fun detectsMatroska() = assertEquals(HexanaEvent.DetectedFormat.MATROSKA, detect(0x1A, 0x45, 0xDF, 0xA3))

    @Test
    fun detectsAvro() = assertEquals(HexanaEvent.DetectedFormat.AVRO, detect(0x4F, 0x62, 0x6A, 0x01))

    @Test
    fun detectsRpm() = assertEquals(HexanaEvent.DetectedFormat.RPM, detect(0xED, 0xAB, 0xEE, 0xDB))

    @Test
    fun detectsOleCompound() =
        assertEquals(
            HexanaEvent.DetectedFormat.OLE_COMPOUND,
            detect(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1),
        )

    @Test
    fun detectsWoff() = assertEquals(HexanaEvent.DetectedFormat.WOFF, detect(0x77, 0x4F, 0x46, 0x46))

    @Test
    fun detectsWoff2() = assertEquals(HexanaEvent.DetectedFormat.WOFF2, detect(0x77, 0x4F, 0x46, 0x32))

    @Test
    fun detectsOtf() = assertEquals(HexanaEvent.DetectedFormat.OTF, detect(0x4F, 0x54, 0x54, 0x4F))

    @Test
    fun detectsMp3() = assertEquals(HexanaEvent.DetectedFormat.MP3, detect(0x49, 0x44, 0x33))

    @Test
    fun detectsOgg() = assertEquals(HexanaEvent.DetectedFormat.OGG, detect(0x4F, 0x67, 0x67, 0x53))

    @Test
    fun detectsFlac() = assertEquals(HexanaEvent.DetectedFormat.FLAC, detect(0x66, 0x4C, 0x61, 0x43))

    @Test
    fun detectsWav() =
        // "RIFF" at 0, 4-byte size, then "WAVE" at offset 8.
        assertEquals(
            HexanaEvent.DetectedFormat.WAV,
            detect(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45),
        )

    @Test
    fun detectsIsoBmffByFtypAtOffset4() =
        // "ftyp" box tag lives at offset 4, after the 4-byte box size.
        assertEquals(
            HexanaEvent.DetectedFormat.ISO_BMFF,
            detect(0x00, 0x00, 0x00, 0x00, 0x66, 0x74, 0x79, 0x70),
        )

    @Test
    fun detectsTfliteByFlatBufferIdAtOffset4() =
        // "TFL3" identifier lives at offset 4, after the 4-byte root-table offset.
        assertEquals(HexanaEvent.DetectedFormat.TFLITE, detect(0x00, 0x00, 0x00, 0x00, 0x54, 0x46, 0x4C, 0x33))

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

    @Test
    fun classFileProviderReportsClassRegardlessOfFileName() {
        // Java 11 class-file header: 0xCAFEBABE, minor 0, major 55. The provider
        // type is content-verified, so the format must be CLASS even with no file.
        val head = ByteArray(16) { i ->
            intArrayOf(0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00, 0x00, 0x37).getOrElse(i) { 0 }.toByte()
        }
        val provider = ClassFileBinaryProvider(head.toCommonByteBuffer())
        assertEquals(HexanaEvent.BinaryFormat.CLASS, binaryFormatOf(file = null, provider = provider))
    }
    private fun formatOf(fileName: String?, vararg head: Int): HexanaEvent.BinaryFormat {
        val bytes = ByteArray(64) { i -> head.getOrElse(i) { 0 }.toByte() }
        val provider = nativeOrGenericBinaryProvider(bytes.toCommonByteBuffer())
        return binaryFormatOf(fileName?.let { MockVirtualFile(it) }, provider)
    }

    private val zipHead = intArrayOf(0x50, 0x4B, 0x03, 0x04)

    @Test
    fun zipFamilyContainersReportZip() {
        for (name in listOf("lib.aar", "pkg.whl", "pkg.nupkg", "ext.vsix", "app.ipa", "doc.docx", "data.bin", "archive.zip")) {
            assertEquals(name, HexanaEvent.BinaryFormat.ZIP, formatOf(name, *zipHead))
        }
        assertEquals(HexanaEvent.BinaryFormat.ZIP, formatOf(null, *zipHead))
    }

    @Test
    fun jarWarApkKeepTheirOwnFormat() {
        assertEquals(HexanaEvent.BinaryFormat.JAR, formatOf("lib.jar", *zipHead))
        assertEquals(HexanaEvent.BinaryFormat.WAR, formatOf("app.WAR", *zipHead))
        assertEquals(HexanaEvent.BinaryFormat.APK, formatOf("app.apk", *zipHead))
    }

    @Test
    fun dexReportsDex() =
        assertEquals(HexanaEvent.BinaryFormat.DEX, formatOf("classes.bin", 0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00))

    @Test
    fun pcapReportsPcap() = assertEquals(HexanaEvent.BinaryFormat.PCAP, formatOf("capture.bin", 0xD4, 0xC3, 0xB2, 0xA1, 0x02, 0x00, 0x04, 0x00))

    @Test
    fun staticArchiveReportsArchive() =
        // "!<arch>\n"
        assertEquals(HexanaEvent.BinaryFormat.ARCHIVE, formatOf("libfoo.a", 0x21, 0x3C, 0x61, 0x72, 0x63, 0x68, 0x3E, 0x0A))

    @Test
    fun explicitProtobufMessageStaysGeneric() {
        val provider = ProtobufMessageBinaryProvider(ByteArray(8).toCommonByteBuffer())
        assertEquals(HexanaEvent.BinaryFormat.GENERIC, binaryFormatOf(MockVirtualFile("msg.pb"), provider))
    }

    @Test
    fun providerlessZipFamilyExtensionReportsZip() {
        assertEquals(HexanaEvent.BinaryFormat.ZIP, binaryFormatOf(MockVirtualFile("lib.aar"), provider = null))
        assertEquals(HexanaEvent.BinaryFormat.APK, binaryFormatOf(MockVirtualFile("app.apk"), provider = null))
        assertEquals(HexanaEvent.BinaryFormat.ZIP, binaryFormatOf(MockVirtualFile("lib.aar.gz"), provider = null))
        assertEquals(HexanaEvent.BinaryFormat.TAR, binaryFormatOf(MockVirtualFile("src.tar.gz"), provider = null))
        assertEquals(HexanaEvent.BinaryFormat.GENERIC, binaryFormatOf(MockVirtualFile("blob.xyz"), provider = null))
    }
}
