package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the cost of [ProtobufMessageBinaryProvider.structureRanges].
 *
 * The regions are also derivable by filtering `decodedFields` to depth 0, which is
 * what an earlier version did — but that runs the full recursive decode, copying and
 * formatting every nested payload, and measured two orders of magnitude slower on a
 * multi-megabyte message. Rather than assert a wall-clock budget, this counts payload
 * reads: the wire walk only reads tags and lengths and skips payloads by moving the
 * cursor, so any routing back through the full decode shows up as bulk reads here.
 */
class ProtobufMessageStructureRangesTest {

    @Test
    fun structureRangesNeverReadPayloadBytes() {
        val source = CountingBuffer(syntheticMessage(repeats = 500).toCommonByteBuffer())
        val provider = ProtobufMessageBinaryProvider(source)

        val ranges = provider.structureRanges
        assertEquals(500 * 4, ranges.size, "expected four top-level fields per repeat")
        assertEquals(
            0,
            source.bulkReads,
            "structureRanges must skip payloads, not copy them; got ${source.bulkReads} bulk reads",
        )
        // Sanity: the full decode this avoids does copy payloads.
        val decodeSource = CountingBuffer(syntheticMessage(repeats = 500).toCommonByteBuffer())
        ProtobufMessageBinaryProvider(decodeSource).decodedFields
        assertTrue(
            decodeSource.bulkReads > 0,
            "expected the full decode to copy payloads, so the assertion above is meaningful",
        )
    }

    @Test
    fun structureRangesLabelFieldsByNumberAndWireType() {
        val provider = ProtobufMessageBinaryProvider(syntheticMessage(repeats = 1).toCommonByteBuffer())

        assertEquals(
            listOf("#1 varint", "#2 len", "#3 i32", "#4 len"),
            provider.structureRanges.map { it.label },
        )
    }

    /** [repeats] copies of: varint, string, i32, bytes — one of every wire type. */
    private fun syntheticMessage(repeats: Int): ByteArray {
        val out = ArrayList<Byte>()
        fun byte(value: Int) = out.add(value.toByte())
        fun bytes(value: ByteArray) = value.forEach { out.add(it) }
        repeat(repeats) {
            byte(0x08); byte(0x2A)                                      // #1 varint 42
            byte(0x12); byte(11); bytes("hello world".encodeToByteArray()) // #2 string
            byte(0x1D); bytes(ByteArray(4))                             // #3 i32
            byte(0x22); byte(20); bytes(ByteArray(20) { (it + 1).toByte() }) // #4 bytes
        }
        return ByteArray(out.size) { out[it] }
    }
}

/**
 * Delegating buffer that counts bulk reads — `get(dst, offset, size)`, the call a
 * payload copy goes through. Views share the counter so a decode that slices its way
 * into submessages is still observed.
 */
private class CountingBuffer(
    private val delegate: CommonByteBuffer,
    private val counter: IntArray = IntArray(1),
) : CommonByteBuffer {
    val bulkReads: Int get() = counter[0]

    override fun get(dst: ByteArray, offset: Int, size: Int) {
        counter[0]++
        delegate.get(dst, offset, size)
    }

    override fun duplicate(): CommonByteBuffer = CountingBuffer(delegate.duplicate(), counter)
    override fun slice(offset: Int, length: Int): CommonByteBuffer =
        CountingBuffer(delegate.slice(offset, length), counter)

    override fun get(): Byte = delegate.get()
    override fun get(index: Int): Byte = delegate[index]
    override fun put(value: Byte) = delegate.put(value)
    override fun put(src: ByteArray, offset: Int, size: Int) = delegate.put(src, offset, size)
    override fun putIntLE(value: Int) = delegate.putIntLE(value)
    override fun getIntLE(): Int = delegate.getIntLE()
    override fun position(): Int = delegate.position()
    override fun position(index: Int): CommonByteBuffer {
        delegate.position(index)
        return this
    }
    override fun remaining(): Int = delegate.remaining()
    override fun hasRemaining(): Boolean = delegate.hasRemaining()
    override fun getFloatLE(): Float = delegate.getFloatLE()
    override fun getDoubleLE(): Double = delegate.getDoubleLE()
    override fun limit(): Int = delegate.limit()
}
