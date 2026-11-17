package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.LargeBytes
import org.jetbrains.hexana.io.MappedFileBytes
import org.jetbrains.hexana.io.SparseLargeBytes
import org.jetbrains.hexana.io.asLargeBytes
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.io.readBytes
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.ArchiveMemberKind
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.ParseCoverage
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /**
     * Parses [whole] the way the VS Code webview does (`StructureBytesFetcher`): over a
     * [SparseLargeBytes] holding only [headSize] bytes, fetching what each round missed (plus the
     * tail, in the first round that misses anything) until a round misses nothing. Returns the
     * last provider, the rounds it took and the bytes fetched past the head.
     */
    private fun <P : BinaryProvider> converge(
        whole: LargeBytes,
        name: String,
        touch: (P) -> Unit,
        headSize: Int = 4096,
    ): Triple<P, Int, Long> {
        var sparse = SparseLargeBytes.of(whole.window(0, headSize), whole.size)
        var fetched = 0L
        var tailFetched = false
        repeat(64) { round ->
            @Suppress("UNCHECKED_CAST")
            val provider = createBinaryProvider(sparse.window(0, headSize), name, fileSize = whole.size, source = sparse) as P
            touch(provider)
            val wanted = sparse.misses.toMutableList()
            if (wanted.isEmpty()) return Triple(provider, round, fetched)
            if (!tailFetched) {
                tailFetched = true
                wanted += maxOf(headSize.toLong(), whole.size - 1024 * 1024) until whole.size
            }
            sparse = sparse.withChunks(
                wanted.map { range ->
                    val length = (range.last - range.first + 1).toInt()
                    fetched += length
                    SparseLargeBytes.Chunk(range.first, whole.readBytes(range.first, length))
                },
            )
        }
        error("no convergence")
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

        val (sparse, rounds, fetched) = converge<ZipBinaryProvider>(source, "big.zip", { it.zipEntries })
        assertEquals(provider.zipEntries, sparse.zipEntries)
        assertTrue(rounds <= 3, "$rounds rounds")
        assertTrue(fetched <= 2L * 1024 * 1024, "$fetched bytes fetched")
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

        val (sparse, rounds, fetched) = converge<TarBinaryProvider>(source, "big.tar", { it.tarEntries })
        assertEquals(provider.tarEntries, sparse.tarEntries)
        assertTrue(rounds <= 3, "$rounds rounds")
        assertTrue(fetched <= 2L * 1024 * 1024, "$fetched bytes fetched")
    }

    private fun arHeader(name: String, size: Long): ByteArray =
        (name.padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "644".padEnd(8) + size.toString().padEnd(10) + "`\n")
            .encodeToByteArray()
            .also { check(it.size == 60) }

    @Test
    fun `ar member after a 2_5 GiB member`() {
        val bigSize = farOffset + 1 // odd, so the padding byte is read past 2 GiB too
        val elf = java.io.File("../parsing/native-fixtures/test-data/elf/sample.o").readBytes()
        val file = dir.resolve("big.a")
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.write("!<arch>\n".encodeToByteArray())
            raf.write(arHeader("big.bin/", bigSize))
            raf.seek(8 + 60 + bigSize)
            raf.write('\n'.code)
            raf.write(arHeader("tail.o/", elf.size.toLong()))
            raf.write(elf)
            if (elf.size % 2 != 0) raf.write('\n'.code)
        }
        val source = MappedFileBytes.open(file)
        val provider = assertIs<ArchiveBinaryProvider>(createBinaryProvider(source.window(0, 4096), "big.a", source = source))

        assertEquals(listOf("big.bin", "tail.o"), provider.members.map { it.name })
        val (big, tail) = provider.members
        assertEquals(bigSize.toULong(), big.size)
        assertEquals(8 + 60 + bigSize + 1 + 60, tail.contentRange.start)
        assertEquals(elf.toList(), provider.memberBytes(tail)!!.toByteArray().toList())
        assertEquals(ArchiveMemberKind.ELF, provider.classifyMember(tail).kind)
        // Too large to extract into one buffer, so not offered for opening.
        assertNull(provider.memberBytes(big))
        assertFalse(provider.classifyMember(big).openable)
        assertEquals(8 + 60 + bigSize + 1, provider.structureRanges.single { it.label == "tail.o" }.offset)
        assertEquals(ParseCoverage.WholeFile, provider.parseCoverage(source.size))

        val (sparse, rounds, fetched) = converge<ArchiveBinaryProvider>(source, "big.a", { p ->
            p.members.forEach { p.classifyMember(it) }
        })
        assertEquals(provider.members, sparse.members)
        assertEquals(ArchiveMemberKind.ELF, sparse.classifyMember(sparse.members[1]).kind)
        assertTrue(rounds <= 4, "$rounds rounds")
        assertTrue(fetched <= 2L * 1024 * 1024, "$fetched bytes fetched")
    }

    @Test
    fun `ELF tables at the end of the file converge from a head`() {
        val elf = java.io.File("../parsing/native-fixtures/test-data/elf/sample.elf").readBytes()
        val whole = elf.toCommonByteBuffer().asLargeBytes()
        val full = assertIs<ElfBinaryProvider>(createBinaryProvider(elf.toCommonByteBuffer(), "sample.elf"))
        val (sparse, rounds, _) = converge<ElfBinaryProvider>(whole, "sample.elf", { p ->
            p.sections; p.symbols; p.programHeaders; p.structureRanges
        }, headSize = 256)
        assertEquals(full.sections!!.map { it.name }, sparse.sections!!.map { it.name })
        assertEquals(full.symbols!!.map { it.name }, sparse.symbols!!.map { it.name })
        assertEquals(full.structureRanges, sparse.structureRanges)
        assertTrue(rounds in 1..8, "$rounds rounds")
    }
}
