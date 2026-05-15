package org.jetbrains.hexana.zip

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ZipParserTest {
    @Test
    fun `parses a synthesized two-entry stored archive`() {
        val builder = StoredZipBuilder()
        builder.addStoredEntry("Foo.class", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        builder.addStoredEntry("hello.txt", "Hello, world!".encodeToByteArray())
        val bytes = builder.finish()

        val entries = ZipParser.parse(bytes.toCommonByteBuffer())

        assertEquals(2, entries.size)

        val foo = entries[0]
        assertEquals("Foo.class", foo.name)
        assertEquals(0, foo.compressionMethod)
        assertEquals(4L, foo.compressedSize)
        assertEquals(4L, foo.uncompressedSize)
        assertTrue(foo.isClass)
        // Data starts after local file header (30 bytes) + name (9 bytes).
        assertEquals(30L + foo.name.length, foo.dataOffset)
        // Verify that the data offset actually points at the magic 0xCAFEBABE.
        assertEquals(0xCA.toByte(), bytes[foo.dataOffset.toInt()])
        assertEquals(0xFE.toByte(), bytes[foo.dataOffset.toInt() + 1])

        val hello = entries[1]
        assertEquals("hello.txt", hello.name)
        assertEquals("Hello, world!".length.toLong(), hello.uncompressedSize)
        assertEquals(false, hello.isClass)
    }

    @Test
    fun `parses an empty archive`() {
        val bytes = StoredZipBuilder().finish()
        val entries = ZipParser.parse(bytes.toCommonByteBuffer())
        assertEquals(emptyList(), entries)
    }

    @Test
    fun `throws when the EOCD record is missing`() {
        val notAZip = ByteArray(64) { 0 }
        assertFails { ZipParser.parse(notAZip.toCommonByteBuffer()) }
    }
}

/**
 * Minimal builder for `stored` (method 0, no compression) ZIP archives —
 * enough to exercise the parser without a JVM `java.util.zip.ZipOutputStream`
 * (which doesn't exist on Kotlin/JS or Kotlin/Wasm). Field layout per PKWARE
 * APPNOTE 4.4.
 */
private class StoredZipBuilder {
    private data class Pending(
        val name: String,
        val data: ByteArray,
        val crc: Int,
        val localHeaderOffset: Int,
    )

    private val out = ArrayList<Byte>()
    private val pending = ArrayList<Pending>()

    fun addStoredEntry(name: String, data: ByteArray) {
        val crc = crc32(data)
        val localHeaderOffset = out.size
        // Local file header (30 bytes fixed + name + extra).
        u4(0x04034B50)                       // signature
        u2(20)                               // version needed (2.0)
        u2(0)                                // general purpose bit flags
        u2(0)                                // compression method (stored)
        u2(0); u2(0)                         // last mod time / date (placeholder)
        u4(crc.toLong() and 0xFFFFFFFFL)     // crc-32
        u4(data.size.toLong())               // compressed size
        u4(data.size.toLong())               // uncompressed size
        u2(name.length)                      // name length
        u2(0)                                // extra field length
        bytes(name.encodeToByteArray())
        bytes(data)
        pending += Pending(name, data, crc, localHeaderOffset)
    }

    fun finish(): ByteArray {
        val cdOffset = out.size
        for (entry in pending) {
            u4(0x02014B50)                                // CD entry signature
            u2(20)                                        // version made by
            u2(20)                                        // version needed
            u2(0)                                         // flags
            u2(0)                                         // method
            u2(0); u2(0)                                  // mod time / date
            u4(entry.crc.toLong() and 0xFFFFFFFFL)
            u4(entry.data.size.toLong())                  // compressed size
            u4(entry.data.size.toLong())                  // uncompressed size
            u2(entry.name.length)                         // name length
            u2(0)                                         // extra length
            u2(0)                                         // comment length
            u2(0)                                         // disk start
            u2(0)                                         // internal attrs
            u4(0)                                         // external attrs
            u4(entry.localHeaderOffset.toLong())          // local header offset
            bytes(entry.name.encodeToByteArray())
        }
        val cdSize = out.size - cdOffset
        // End of Central Directory Record.
        u4(0x06054B50)
        u2(0); u2(0)
        u2(pending.size); u2(pending.size)
        u4(cdSize.toLong())
        u4(cdOffset.toLong())
        u2(0)                                             // comment length
        return ByteArray(out.size) { out[it] }
    }

    private fun u2(value: Int) { u1(value); u1(value ushr 8) }
    private fun u4(value: Long) { u1(value.toInt()); u1((value ushr 8).toInt()); u1((value ushr 16).toInt()); u1((value ushr 24).toInt()) }
    private fun u4(value: Int) { u4(value.toLong() and 0xFFFFFFFFL) }
    private fun u1(value: Int) { out.add((value and 0xFF).toByte()) }
    private fun bytes(b: ByteArray) { for (x in b) out.add(x) }
}

private fun crc32(data: ByteArray): Int {
    var crc = 0xFFFFFFFF.toInt()
    for (b in data) {
        crc = crc xor (b.toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
        }
    }
    return crc xor 0xFFFFFFFF.toInt()
}
