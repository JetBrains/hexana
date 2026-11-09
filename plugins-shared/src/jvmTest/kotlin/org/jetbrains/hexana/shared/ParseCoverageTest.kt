package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.pcap.MAX_CAPTURE_PACKETS
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.ParseCoverage
import org.jetbrains.hexana.providers.PcapBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Which providers miss structures when they parse only the head of a larger file. Each case
 * parses an in-memory head and asks about a file declared [far] larger, as a host does for a
 * file past what it can load.
 */
class ParseCoverageTest {
    private val far = 5L * 1024 * 1024 * 1024

    @Test
    fun `a file of no known format has nothing to miss`() {
        val provider = assertIs<GenericBinaryProvider>(createBinaryProvider(ByteArray(4096) { 0x5A }.toCommonByteBuffer(), "blob.bin"))
        assertEquals(ParseCoverage.WholeFile, provider.parseCoverage(far))
    }

    @Test
    fun `Wasm sections may continue past the head`() {
        val head = byteArrayOf(0, 0x61, 0x73, 0x6D, 1, 0, 0, 0)
        val provider = assertIs<WasmBinaryProvider>(createBinaryProvider(head.toCommonByteBuffer(), "m.wasm"))
        assertEquals(ParseCoverage.Prefix(head.size.toLong(), "Wasm sections"), provider.parseCoverage(far))
        // A head that is the whole file misses nothing.
        assertEquals(ParseCoverage.WholeFile, provider.parseCoverage(head.size.toLong()))
    }

    @Test
    fun `PE with an overlay past the head is covered, one cut inside a section is not`() {
        val exe = File("../parsing/native-fixtures/test-data/pe/sample-clr.exe").readBytes()
        val whole = assertIs<PeBinaryProvider>(createBinaryProvider(exe.toCommonByteBuffer(), "sample.exe"))
        // Everything past the image — an installer's payload, say — is a blob.
        assertEquals(ParseCoverage.WholeFile, whole.parseCoverage(far))

        val lastSectionEnd = whole.sections!!.maxOf { it.rawDataPointer.toLong() + it.rawDataSize.toLong() }
        val cut = assertIs<PeBinaryProvider>(
            createBinaryProvider(exe.copyOf(lastSectionEnd.toInt() - 1).toCommonByteBuffer(), "sample.exe"),
        )
        assertEquals(ParseCoverage.Prefix(lastSectionEnd - 1, "PE sections and tables"), cut.parseCoverage(far))
    }

    private fun capture(packets: Int): ByteArray =
        ByteBuffer.allocate(24 + packets * 20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0xA1B2C3D4.toInt()); putShort(2); putShort(4); putInt(0); putInt(0); putInt(65535); putInt(1)
            repeat(packets) { putInt(it); putInt(0); putInt(4); putInt(4); putInt(0x0BADF00D) }
        }.array()

    @Test
    fun `a capture that reached its packet cap in the head lists as much as a small one`() {
        val full = assertIs<PcapBinaryProvider>(createBinaryProvider(capture(MAX_CAPTURE_PACKETS + 5).toCommonByteBuffer(), "c.pcap"))
        assertEquals(MAX_CAPTURE_PACKETS, full.parsedPcapFile!!.packets.size)
        assertEquals(ParseCoverage.WholeFile, full.parseCoverage(far))

        val few = capture(3)
        val short = assertIs<PcapBinaryProvider>(createBinaryProvider(few.toCommonByteBuffer(), "c.pcap"))
        assertEquals(ParseCoverage.Prefix(few.size.toLong(), "packets"), short.parseCoverage(far))
    }

    @Test
    fun `an archive head lists the members it holds`() {
        fun header(name: String, size: Int) =
            (name.padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "644".padEnd(8) + size.toString().padEnd(10) + "`\n")
                .encodeToByteArray()
        val archive = "!<arch>\n".encodeToByteArray() +
            header("a.o/", 4) + ByteArray(4) +
            header("b.o/", 1000) + ByteArray(1000)
        val head = archive.copyOf(8 + 64 + 60 + 10) // into b.o's payload
        val provider = assertIs<ArchiveBinaryProvider>(createBinaryProvider(head.toCommonByteBuffer(), "lib.a"))
        assertEquals(listOf("a.o"), provider.members.map { it.name })
        assertTrue(provider.archiveFile!!.diagnostics.any { it.code == "archive.truncated" })
        assertEquals(ParseCoverage.Prefix(head.size.toLong(), "archive members"), provider.parseCoverage(far))
    }
}
