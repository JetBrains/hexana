package org.jetbrains.hexana.document.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Unit tests for the piece-list primitive. These exercise the byte-level
 * behaviour expected by the spec (logical offsets, growth/shrink semantics)
 * in isolation so the editing-state-machine tests can layer on top.
 */
class PieceListTest {

    private fun materialize(list: PieceList, original: ByteArray, added: GrowableByteBuffer): ByteArray {
        val out = ByteArray(list.length.toInt())
        list.copyInto(0L, out, 0, out.size, original, added)
        return out
    }

    @Test
    fun starts_at_original_full_length() {
        val original = byteArrayOf(0x01, 0x02, 0x03)
        val added = GrowableByteBuffer()
        val list = PieceList(listOf(Piece(Source.ORIGINAL, 0L, 3L)))
        assertEquals(3L, list.length)
        assertContentEquals(original, materialize(list, original, added))
    }

    @Test
    fun replace_drops_inner_pieces_and_inserts_added() {
        val original = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val added = GrowableByteBuffer()
        val list = PieceList(listOf(Piece(Source.ORIGINAL, 0L, 5L)))

        // Append 2 bytes to ADDED so we have something to point at.
        val addedStart = added.append(byteArrayOf(0xAA.toByte(), 0xBB.toByte())).toLong()
        list.replace(start = 1L, end = 3L, insertion = Piece(Source.ADDED, addedStart, 2L))

        assertEquals(5L, list.length)
        assertContentEquals(
            byteArrayOf(0x10, 0xAA.toByte(), 0xBB.toByte(), 0x40, 0x50),
            materialize(list, original, added),
        )
    }

    @Test
    fun replace_with_insertion_longer_than_removed_grows_doc() {
        val original = byteArrayOf(0xA0.toByte(), 0xB0.toByte())
        val added = GrowableByteBuffer()
        val list = PieceList(listOf(Piece(Source.ORIGINAL, 0L, 2L)))

        val addedStart = added.append(byteArrayOf(0x10, 0x20, 0x30)).toLong()
        list.replace(start = 0L, end = 1L, insertion = Piece(Source.ADDED, addedStart, 3L))

        assertEquals(4L, list.length)
        assertContentEquals(
            byteArrayOf(0x10, 0x20, 0x30, 0xB0.toByte()),
            materialize(list, original, added),
        )
    }

    @Test
    fun delete_at_end_shrinks_doc() {
        val original = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val added = GrowableByteBuffer()
        val list = PieceList(listOf(Piece(Source.ORIGINAL, 0L, 5L)))

        list.replace(start = 3L, end = 5L, insertion = null)

        assertEquals(3L, list.length)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), materialize(list, original, added))
    }

    @Test
    fun insert_at_start_prepends() {
        val original = byteArrayOf(0xCC.toByte())
        val added = GrowableByteBuffer()
        val list = PieceList(listOf(Piece(Source.ORIGINAL, 0L, 1L)))

        val addedStart = added.append(byteArrayOf(0xFF.toByte())).toLong()
        list.replace(start = 0L, end = 0L, insertion = Piece(Source.ADDED, addedStart, 1L))

        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xCC.toByte()), materialize(list, original, added))
    }

    @Test
    fun snapshotPieces_is_independent_of_subsequent_mutations() {
        val original = byteArrayOf(0x01, 0x02, 0x03)
        val added = GrowableByteBuffer()
        val list = PieceList(listOf(Piece(Source.ORIGINAL, 0L, 3L)))

        val frozen = list.snapshotPieces()

        // Mutate live list afterwards.
        list.replace(0L, 3L, insertion = null)

        // The frozen snapshot still describes the original three bytes.
        val frozenList = PieceList(frozen)
        assertEquals(3L, frozenList.length)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), materialize(frozenList, original, added))
    }

    @Test
    fun getByte_walks_correct_piece() {
        val original = byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte())
        val added = GrowableByteBuffer()
        val addedStart = added.append(byteArrayOf(0xB0.toByte(), 0xB1.toByte())).toLong()
        val list = PieceList(
            listOf(
                Piece(Source.ORIGINAL, 0L, 2L),
                Piece(Source.ADDED, addedStart, 2L),
                Piece(Source.ORIGINAL, 2L, 1L),
            ),
        )

        // Logical layout: A0 A1 | B0 B1 | A2
        assertEquals(0xA0.toByte(), list.getByte(0L, original, added))
        assertEquals(0xA1.toByte(), list.getByte(1L, original, added))
        assertEquals(0xB0.toByte(), list.getByte(2L, original, added))
        assertEquals(0xB1.toByte(), list.getByte(3L, original, added))
        assertEquals(0xA2.toByte(), list.getByte(4L, original, added))
    }
}
