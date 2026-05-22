package org.jetbrains.hexana.document

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.hexana.io.toCommonByteBuffer

private fun sample(size: Int = 16): ByteArray = ByteArray(size) { (it and 0xFF).toByte() }

/** Wraps test-fixture byte literals so they flow into the buffer-typed factory. */
private fun docOf(bytes: ByteArray): BinaryDocument = binaryDocumentOf(bytes.toCommonByteBuffer())

class BinaryDocumentTest {

    @Test
    fun length_matches_backing_array() {
        val doc = docOf(sample(42))
        assertEquals(42L, doc.length)
    }

    @Test
    fun length_zero_for_empty_document() {
        val doc = docOf(ByteArray(0))
        assertEquals(0L, doc.length)
        assertEquals(0L, doc.byteSource().length)
    }

    @Test
    fun readByte_returns_indexed_value() {
        val doc = docOf(sample(10))
        for (i in 0L until 10L) {
            assertEquals(i.toByte(), doc.readByte(i))
        }
    }

    @Test
    fun readByte_throws_on_out_of_bounds() {
        val doc = docOf(sample(4))
        assertFailsWith<IndexOutOfBoundsException> { doc.readByte(-1L) }
        assertFailsWith<IndexOutOfBoundsException> { doc.readByte(4L) }
        assertFailsWith<IndexOutOfBoundsException> { doc.readByte(100L) }
    }

    @Test
    fun read_returns_fresh_copy() {
        val doc = docOf(sample(8))
        val first = doc.read(2L, 4)
        val second = doc.read(2L, 4)
        assertContentEquals(byteArrayOf(2, 3, 4, 5), first)
        assertContentEquals(first, second)
        // Independent copies — mutating one does not affect the other.
        first[0] = 99
        assertEquals(2, second[0])
    }

    @Test
    fun read_full_range() {
        val data = sample(16)
        val doc = docOf(data)
        assertContentEquals(data, doc.read(0L, 16))
    }

    @Test
    fun read_zero_length_at_end_is_legal() {
        val doc = docOf(sample(4))
        assertContentEquals(ByteArray(0), doc.read(4L, 0))
    }

    @Test
    fun read_past_end_throws() {
        val doc = docOf(sample(4))
        assertFailsWith<IndexOutOfBoundsException> { doc.read(2L, 5) }
        assertFailsWith<IndexOutOfBoundsException> { doc.read(5L, 1) }
    }

    @Test
    fun readInto_copies_into_destination() {
        val doc = docOf(sample(8))
        val dst = ByteArray(10) { 0x55 }
        doc.readInto(2L, dst, 3, 4)
        assertContentEquals(
            byteArrayOf(0x55, 0x55, 0x55, 2, 3, 4, 5, 0x55, 0x55, 0x55),
            dst,
        )
    }

    @Test
    fun snapshot_returns_self_for_immutable_document() {
        val doc = docOf(sample(4))
        val snap = doc.snapshot()
        // Same logical state; for the Phase 1 stub it is even reference-equal.
        assertSame(doc, snap)
        assertEquals(doc.length, snap.length)
    }

    @Test
    fun snapshot_is_a_BinaryDocumentSnapshot() {
        val doc = docOf(sample(4))
        // Type assertion: snapshot() returns the read-only marker type.
        val snap: BinaryDocumentSnapshot = doc.snapshot()
        assertEquals(4L, snap.length)
    }

    @Test
    fun edit_throws_in_phase_1() {
        val doc = docOf(sample(4))
        assertFailsWith<UnsupportedOperationException> { doc.edit() }
        assertFailsWith<UnsupportedOperationException> { doc.snapshot().edit() }
    }

    @Test
    fun originate_is_identity_for_valid_offsets() {
        val doc = docOf(sample(8))
        val loc = doc.originate(3L)
        assertNotNull(loc)
        assertEquals(3L, loc.originalOffset)
        assertEquals(5L, loc.length)
    }

    @Test
    fun originate_returns_null_for_out_of_bounds() {
        val doc = docOf(sample(4))
        assertNull(doc.originate(-1L))
        assertNull(doc.originate(4L))
        assertNull(doc.originate(100L))
    }

    @Test
    fun modificationStamp_is_zero_in_phase_1() {
        assertEquals(0L, docOf(sample(4)).modificationStamp)
    }

    @Test
    fun listener_registration_is_a_no_op() {
        val doc = docOf(sample(4))
        val l = DocumentListener { /* never fires */ }
        // Just confirms the methods are callable; no observable effect.
        doc.addListener(l)
        doc.removeListener(l)
        // Removing an unregistered listener is also tolerated.
        doc.removeListener(DocumentListener { })
    }

    @Test
    fun byteSource_cursor_reads_sequentially() {
        val doc = docOf(sample(8))
        val cur = doc.byteSource().cursor()
        assertEquals(0L, cur.position)
        assertTrue(cur.hasRemaining())

        assertEquals(0, cur.readByte())
        assertEquals(1, cur.readByte())
        assertEquals(2L, cur.position)

        assertContentEquals(byteArrayOf(2, 3, 4), cur.readBytes(3))
        assertEquals(5L, cur.position)

        cur.seek(0L)
        assertEquals(0, cur.readByte())
    }

    @Test
    fun byteSource_cursor_starting_at_offset() {
        val doc = docOf(sample(8))
        val cur = doc.byteSource().cursor(at = 4L)
        assertEquals(4L, cur.position)
        assertEquals(4, cur.readByte())
    }
}
