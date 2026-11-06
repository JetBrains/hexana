package org.jetbrains.hexana.pdb

import org.jetbrains.hexana.io.MappedFileBytes
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * An MSF file past 3 GiB whose block map, stream directory and PDB info stream all live in blocks
 * beyond 2 GiB — where a large-page PDB puts them. Sparse, so only a few blocks are written.
 */
class LargePdbTest {
    private val dir = Files.createTempDirectory("hexana-large-pdb")
    private val blockSize = 4096
    private val fileSize = 3L * 1024 * 1024 * 1024 + 16 * 4096L

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun le(capacity: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    @Test
    fun `streams in blocks past 2 GiB are read`() {
        val infoBlock = (5L * 512 * 1024 * 1024 / blockSize).toInt() // 2.5 GiB
        val directoryBlock = infoBlock + 100
        val blockMapBlock = infoBlock + 200
        val guid = ByteArray(16) { (it + 1).toByte() }
        val info = le(48) {
            putInt(20000404); putInt(0x12345678); putInt(7); put(guid)
            putInt(0) // named-stream string buffer size
            putInt(0); putInt(0) // hash table size, capacity
            putInt(0); putInt(0) // present / deleted bit vector word counts
        }
        val directory = le(16) { putInt(2); putInt(0); putInt(info.size); putInt(infoBlock) }

        val file = dir.resolve("big.pdb")
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.setLength(fileSize)
            raf.write("Microsoft C/C++ MSF 7.00\r\n\u001aDS\u0000\u0000\u0000".encodeToByteArray())
            raf.write(le(24) {
                putInt(blockSize); putInt(1); putInt((fileSize / blockSize).toInt())
                putInt(directory.size); putInt(0); putInt(blockMapBlock)
            })
            raf.seek(blockMapBlock.toLong() * blockSize); raf.write(le(4) { putInt(directoryBlock) })
            raf.seek(directoryBlock.toLong() * blockSize); raf.write(directory)
            raf.seek(infoBlock.toLong() * blockSize); raf.write(info)
        }

        val pdb = assertNotNull(PdbParser.parse(MappedFileBytes.open(file)))
        assertEquals(emptyList(), pdb.diagnostics)
        val parsedInfo = assertNotNull(pdb.info)
        assertEquals(0x12345678u, parsedInfo.signature)
        assertEquals(7u, parsedInfo.age)
        assertEquals("04030201-0605-0807-090A-0B0C0D0E0F10", parsedInfo.guid)
        assertEquals(blockMapBlock.toLong() * blockSize, pdb.blockMapRange!!.start)
        val stream = pdb.streams[1]
        assertEquals(infoBlock.toLong() * blockSize, stream.fileOffset(0))
        assertEquals(infoBlock.toLong() * blockSize, stream.blockRanges().single().start)
    }
}
