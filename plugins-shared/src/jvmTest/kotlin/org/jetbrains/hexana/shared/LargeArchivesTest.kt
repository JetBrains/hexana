package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.MappedFileBytes
import org.jetbrains.hexana.io.readBytes
import org.jetbrains.hexana.providers.TarBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Archives past 2 GiB whose second entry, and (for ZIP64) whole central directory, lie beyond what
 * an `Int` addresses. Written sparse, by hand, so the test stores almost nothing.
 */
class LargeArchivesTest {
    private val dir: Path = Files.createTempDirectory("hexana-large-archives")
    private val farOffset = 5L * 512 * 1024 * 1024 // 2.5 GiB

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun le(capacity: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun localHeader(name: ByteArray, data: ByteArray, crc: Int) = le(30 + name.size) {
        putInt(0x04034B50); putShort(20); putShort(0); putShort(0) // version, flags, stored
        putShort(0); putShort(0); putInt(crc); putInt(data.size); putInt(data.size)
        putShort(name.size.toShort()); putShort(0); put(name)
    }

    private fun centralEntry(name: ByteArray, data: ByteArray, crc: Int, localOffset: Long): ByteArray {
        val zip64 = localOffset >= 0xFFFFFFFFL
        val extra = if (zip64) le(12) { putShort(1); putShort(8); putLong(localOffset) } else ByteArray(0)
        return le(46 + name.size + extra.size) {
            putInt(0x02014B50); putShort(45); putShort(45); putShort(0); putShort(0)
            putShort(0); putShort(0); putInt(crc); putInt(data.size); putInt(data.size)
            putShort(name.size.toShort()); putShort(extra.size.toShort()); putShort(0)
            putShort(0); putShort(0); putInt(0)
            putInt(if (zip64) -1 else localOffset.toInt())
            put(name); put(extra)
        }
    }

    @Test
    fun `ZIP64 central directory and entries past 2 GiB`() {
        val first = "first.txt".encodeToByteArray() to "hello".encodeToByteArray()
        val second = "far/away.txt".encodeToByteArray() to "from 2.5 GiB".encodeToByteArray()
        fun crc(data: ByteArray) = CRC32().apply { update(data) }.value.toInt()

        val file = dir.resolve("big.zip")
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.write(localHeader(first.first, first.second, crc(first.second))); raf.write(first.second)
            raf.seek(farOffset)
            raf.write(localHeader(second.first, second.second, crc(second.second))); raf.write(second.second)
            val cdOffset = raf.filePointer
            val cd = centralEntry(first.first, first.second, crc(first.second), 0) +
                centralEntry(second.first, second.second, crc(second.second), farOffset)
            raf.write(cd)
            val zip64EocdOffset = raf.filePointer
            raf.write(le(56) {
                putInt(0x06064B50); putLong(44); putShort(45); putShort(45); putInt(0); putInt(0)
                putLong(2); putLong(2); putLong(cd.size.toLong()); putLong(cdOffset)
            })
            raf.write(le(20) { putInt(0x07064B50); putInt(0); putLong(zip64EocdOffset); putInt(1) })
            raf.write(le(22) {
                putInt(0x06054B50); putShort(0); putShort(0); putShort(-1); putShort(-1); putInt(-1); putInt(-1); putShort(0)
            })
        }
        val source = MappedFileBytes.open(file)
        val provider = assertIs<ZipBinaryProvider>(createBinaryProvider(source.window(0, 4096), "big.zip", source = source))

        assertEquals(listOf("first.txt", "far/away.txt"), provider.zipEntries.map { it.name })
        val far = provider.zipEntries[1]
        assertEquals(farOffset, far.localHeaderOffset)
        assertEquals("from 2.5 GiB", source.readBytes(far.dataOffset, far.uncompressedSize.toInt()).decodeToString())
        assertEquals(farOffset, provider.structureRanges.single { it.label == "far/away.txt" }.offset)
    }

    private fun tarHeader(name: String, size: Long): ByteArray {
        val header = ByteArray(512)
        fun put(text: String, at: Int) = text.encodeToByteArray().copyInto(header, at)
        put(name, 0)
        put("0000644\u0000", 100); put("0000000\u0000", 108); put("0000000\u0000", 116)
        put(size.toString(8).padStart(11, '0') + "\u0000", 124)
        put("00000000000\u0000", 136)
        header[156] = '0'.code.toByte()
        put("ustar\u000000", 257)
        "        ".encodeToByteArray().copyInto(header, 148)
        val sum = header.sumOf { it.toInt() and 0xFF }
        put(sum.toString(8).padStart(6, '0') + "\u0000 ", 148)
        return header
    }

    @Test
    fun `tar entry after a 2_5 GiB entry`() {
        val bigSize = farOffset
        val file = dir.resolve("big.tar")
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.write(tarHeader("big.bin", bigSize))
            raf.seek(512 + bigSize)
            raf.write(tarHeader("after.txt", 5))
            raf.write("after".encodeToByteArray() + ByteArray(507))
            raf.write(ByteArray(1024))
        }
        val source = MappedFileBytes.open(file)
        val provider = assertIs<TarBinaryProvider>(createBinaryProvider(source.window(0, 4096), "big.tar", source = source))

        assertEquals(listOf("big.bin", "after.txt"), provider.tarEntries.map { it.name })
        val after = provider.tarEntries[1]
        assertEquals(512 + bigSize + 512, after.dataOffset)
        assertEquals("after", source.readBytes(after.dataOffset, 5).decodeToString())
    }
}
