package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [structureRangeOrNull] and for the invariants every provider's
 * [BinaryProvider.structureRanges] has to satisfy, exercised through the PCAP
 * provider because a capture file is cheap to synthesize byte-for-byte.
 *
 * The invariants matter because the hex viewers sort the ranges by offset and then
 * binary-search them to find the region under the caret: a zero-length or
 * out-of-bounds region would make that search point at bytes that are not there.
 */
class StructureRangesTest {

    @Test
    fun rejectsRegionsOutsideTheFile() {
        assertNull(structureRangeOrNull(0L, 0L, "empty", fileSize = 100))
        assertNull(structureRangeOrNull(0L, -1L, "negative size", fileSize = 100))
        assertNull(structureRangeOrNull(-1L, 10L, "negative offset", fileSize = 100))
        assertNull(structureRangeOrNull(95L, 10L, "overruns", fileSize = 100))
        assertNull(structureRangeOrNull(100L, 1L, "starts at eof", fileSize = 100))
    }

    @Test
    fun rejectsRegionsWhoseEndOverflows() {
        // Parquet page offsets and Arrow block lengths are 64-bit values straight out of
        // the file, so a hostile one can make `offset + size` wrap negative. Checked
        // naively, that wrapped sum compares as "inside the file".
        assertNull(structureRangeOrNull(Long.MAX_VALUE, 10L, "wrapping end", fileSize = 100))
        assertNull(structureRangeOrNull(1L shl 62, 1L shl 62, "wrapping sum", fileSize = 100))
        assertNull(
            structureRangeOrNull(Long.MAX_VALUE, Long.MAX_VALUE, "both maxed", fileSize = 100),
        )
    }

    @Test
    fun keepsRegionsInsideTheFile() {
        val whole = assertNotNull(structureRangeOrNull(0L, 100L, "whole", fileSize = 100))
        assertEquals(0L, whole.offset)
        assertEquals(100L, whole.size)
        assertEquals("whole", whole.label)
        assertNull(whole.tabName)

        val tail = assertNotNull(structureRangeOrNull(90L, 10L, "tail", fileSize = 100, tabName = "Packets"))
        assertEquals("Packets", tail.tabName)
    }

    @Test
    fun pcapRangesCoverHeaderAndEveryPacket() {
        val payloads = listOf(ByteArray(20) { 1 }, ByteArray(8) { 2 }, ByteArray(64) { 3 })
        val bytes = syntheticPcap(payloads)
        val provider = PcapBinaryProvider(bytes.toCommonByteBuffer())

        val ranges = provider.structureRanges
        // Packets are labelled one-based, matching the Packets table's "No." column.
        assertEquals(
            listOf("Header", "Packet 1", "Packet 2", "Packet 3"),
            ranges.map { it.label },
        )
        assertEquals(listOf("Header", "Packets", "Packets", "Packets"), ranges.map { it.tabName })

        // The header ends where the first record begins, and each record spans its
        // 16-byte header plus its payload, so the ranges tile the whole file.
        assertEquals(0L, ranges.first().offset)
        assertEquals(PCAP_GLOBAL_HEADER_SIZE.toLong(), ranges.first().size)
        assertEquals(
            payloads.map { (PCAP_PACKET_HEADER_SIZE + it.size).toLong() },
            ranges.drop(1).map { it.size },
        )
        assertRangeInvariants(ranges, bytes.size)
        assertEquals(bytes.size.toLong(), ranges.last().let { it.offset + it.size })
    }

    @Test
    fun pcapWithTruncatedRecordDropsIt() {
        // A record header declaring 40 payload bytes with only 4 present: the parser
        // stops at it, so the ranges end with the last intact packet.
        val bytes = syntheticPcap(listOf(ByteArray(16) { 1 })) +
            leBytes { u32(0); u32(0); u32(40); u32(40); raw(ByteArray(4)) }
        val provider = PcapBinaryProvider(bytes.toCommonByteBuffer())

        val ranges = provider.structureRanges
        assertEquals(listOf("Header", "Packet 1"), ranges.map { it.label })
        assertRangeInvariants(ranges, bytes.size)
    }

    @Test
    fun captureWithNoPacketsStillReportsItsHeader() {
        // A capture whose filter matched nothing is a valid file: 24 bytes of header and
        // no records. Reporting nothing would leave it with no regions and, in turn, no
        // structure view at all.
        val bytes = syntheticPcap(emptyList())
        assertEquals(PCAP_GLOBAL_HEADER_SIZE, bytes.size)

        val ranges = PcapBinaryProvider(bytes.toCommonByteBuffer()).structureRanges

        assertEquals(listOf("Header"), ranges.map { it.label })
        assertEquals(PCAP_GLOBAL_HEADER_SIZE.toLong(), ranges.single().size)
        assertRangeInvariants(ranges, bytes.size)
    }

    @Test
    fun parquetWithNoRowGroupsStillReportsItsFooter() {
        // An empty table is a valid Parquet file: magic, a footer describing no row
        // groups, and magic again. All of it but the leading magic is footer.
        val bytes = emptyParquet()
        val provider = ParquetBinaryProvider(bytes.toCommonByteBuffer())
        // Fixture self-check - if the hand-written footer stopped decoding, the range
        // assertions below would pass for the wrong reason.
        assertEquals(emptyList(), assertNotNull(provider.metadata).rowGroups)

        val ranges = provider.structureRanges

        assertEquals(listOf("Footer"), ranges.map { it.label })
        val footer = ranges.single()
        assertEquals(PARQUET_MAGIC_SIZE.toLong(), footer.offset)
        assertEquals(bytes.size.toLong(), footer.offset + footer.size)
        assertRangeInvariants(ranges, bytes.size)
    }

    @Test
    fun descriptorSetRangesAreLabelledWithTheirProtoFileNames() {
        val names = listOf("a.proto", "google/protobuf/any.proto", "b.proto")
        val bytes = syntheticDescriptorSet(names)
        val provider = ProtobufBinaryProvider(bytes.toCommonByteBuffer())

        // The names are matched to the ranges by position, so the parsed set has to
        // agree with the wire walk on how many files there are.
        assertEquals(names, assertNotNull(provider.parsedDescriptorSet).files.map { it.name })
        val ranges = provider.structureRanges
        assertEquals(names, ranges.map { it.label })
        assertEquals(names.map { "Files" }, ranges.map { it.tabName })
        assertRangeInvariants(ranges, bytes.size)
        assertEquals(bytes.size.toLong(), ranges.last().let { it.offset + it.size })
    }

    @Test
    fun unrecognizedBytesHaveNoRanges() {
        val provider = GenericBinaryProvider(ByteArray(64) { it.toByte() }.toCommonByteBuffer())
        assertEquals(emptyList(), provider.structureRanges)
    }
}

/**
 * Asserts what every hex-viewer consumer of a range list relies on: each region holds
 * bytes that exist, and once sorted by offset the regions do not overlap, so a binary
 * search over their offsets lands on exactly one of them.
 */
internal fun assertRangeInvariants(ranges: List<StructureRange>, fileSize: Int) {
    assertTrue(ranges.isNotEmpty(), "expected at least one range")
    var previousEnd = 0L
    for (range in ranges.sortedBy { it.offset }) {
        assertTrue(range.size > 0L, "${range.label} is empty")
        assertTrue(range.offset >= 0L, "${range.label} starts before the file")
        assertTrue(
            range.offset + range.size <= fileSize.toLong(),
            "${range.label} ends past the file at ${range.offset + range.size} of $fileSize",
        )
        assertTrue(
            range.offset >= previousEnd,
            "${range.label} at ${range.offset} overlaps the region ending at $previousEnd",
        )
        previousEnd = range.offset + range.size
    }
}

private const val PCAP_GLOBAL_HEADER_SIZE = 24
private const val PCAP_PACKET_HEADER_SIZE = 16

/**
 * A classic little-endian PCAP capture: the 24-byte global header followed by one
 * record per payload. `d4 c3 b2 a1` is the native-byte-order magic.
 */
private fun syntheticPcap(payloads: List<ByteArray>): ByteArray = leBytes {
    raw(byteArrayOf(0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte()))
    u16(2); u16(4)      // version 2.4
    u32(0); u32(0)      // thiszone, sigfigs
    u32(65535)          // snaplen
    u32(1)              // network = LINKTYPE_ETHERNET
    payloads.forEachIndexed { index, payload ->
        u32(index.toLong()) // ts_sec
        u32(0)              // ts_usec
        u32(payload.size.toLong())
        u32(payload.size.toLong())
        raw(payload)
    }
}

/**
 * A `FileDescriptorSet` holding one `FileDescriptorProto` per name: field 1 of the set
 * is `repeated file`, and field 1 of each descriptor is its `name`. Both are
 * length-delimited, and every length here is small enough to be a one-byte varint.
 */
private fun syntheticDescriptorSet(names: List<String>): ByteArray = leBytes {
    for (name in names) {
        val encoded = name.encodeToByteArray()
        check(encoded.size < 0x80) { "name needs a multi-byte varint length" }
        val descriptor = byteArrayOf(0x0A, encoded.size.toByte()) + encoded
        raw(byteArrayOf(0x0A, descriptor.size.toByte()))
        raw(descriptor)
    }
}

private const val PARQUET_MAGIC_SIZE = 4

/**
 * The smallest valid Parquet file: `PAR1`, a Thrift-compact `FileMetaData` that is a
 * lone STOP byte (so every field takes its default and there are no row groups), the
 * footer length, and `PAR1` again.
 */
private fun emptyParquet(): ByteArray = leBytes {
    raw("PAR1".encodeToByteArray())
    raw(byteArrayOf(0))    // FileMetaData: STOP
    u32(1)                 // footer length
    raw("PAR1".encodeToByteArray())
}

private fun leBytes(build: LeBytes.() -> Unit): ByteArray = LeBytes().apply(build).toByteArray()

/** Tiny little-endian byte builder, used only by these tests. */
private class LeBytes {
    private val out = ArrayList<Byte>()
    fun u16(value: Int) { out.add(value.toByte()); out.add((value ushr 8).toByte()) }
    fun u32(value: Long) { for (shift in 0..24 step 8) out.add((value ushr shift).toByte()) }
    fun raw(bytes: ByteArray) { for (byte in bytes) out.add(byte) }
    fun toByteArray(): ByteArray = ByteArray(out.size) { out[it] }
}
