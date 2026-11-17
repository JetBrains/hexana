package org.jetbrains.hexana.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import io.airlift.compress.zstd.ZstdCompressor

class JvmCodecTest {

    @Test
    fun `isCodecSupported returns true for supported codecs on jvm`() {
        for (codec in listOf(Codec.UNCOMPRESSED, Codec.SNAPPY, Codec.GZIP, Codec.ZSTD, Codec.LZ4)) {
            assertTrue(isCodecSupported(codec), "expected $codec to be supported on JVM")
        }
    }

    @Test
    fun `isCodecSupported returns false for unsupported codecs on jvm`() {
        // LZ4_RAW deferred (needs sun.misc.Unsafe raw addressing); BROTLI/LZO unimplemented.
        assertFalse(isCodecSupported(Codec.LZ4_RAW))
        assertFalse(isCodecSupported(Codec.BROTLI))
        assertFalse(isCodecSupported(Codec.LZO))
    }

    @Test
    fun `gzip round-trip`() {
        val original = "The quick brown fox jumps over the lazy dog".encodeToByteArray()
        val compressed = ByteArrayOutputStream().also { buf ->
            GZIPOutputStream(buf).use { gz -> gz.write(original) }
        }.toByteArray()

        val decompressed = decompress(Codec.GZIP, compressed, original.size)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun `zstd round-trip`() {
        val original = ByteArray(512) { (it % 127).toByte() }
        val maxCompressedLen = ZstdCompressor().maxCompressedLength(original.size)
        val compressedBuf = ByteArray(maxCompressedLen)
        val compressedLen = ZstdCompressor().compress(
            original, 0, original.size, compressedBuf, 0, maxCompressedLen
        )
        val compressed = compressedBuf.copyOf(compressedLen)

        val decompressed = decompress(Codec.ZSTD, compressed, original.size)
        assertContentEquals(original, decompressed)
    }
}
