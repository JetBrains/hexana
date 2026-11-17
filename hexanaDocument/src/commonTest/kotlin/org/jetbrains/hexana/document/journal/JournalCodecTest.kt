package org.jetbrains.hexana.document.journal

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.jetbrains.hexana.io.toCommonByteBuffer

/**
 * Covers the load-side validation surface from §8/§9 of the design spec —
 * one test per [MismatchReason] plus the happy path. Encode round-trip is
 * already covered by [JournalSerializationTest]; here we focus on
 * [JournalCodec.loadFromJson].
 */
class JournalCodecTest {

    private fun b64(vararg bytes: Int): String =
        Base64.encode(ByteArray(bytes.size) { bytes[it].toByte() })

    private fun goodJournal(original: ByteArray): Journal = Journal(
        originalHash = Journal.ORIGINAL_HASH_PREFIX + Sha256.hex(original.toCommonByteBuffer()),
        originalLength = original.size.toLong(),
        entries = listOf(
            JournalEntry(
                "2026-05-08T00:00:00Z",
                listOf(JournalOp.Replace(0L, 1L, b64(0xAA))),
            ),
        ),
    )

    @Test
    fun encode_roundtrip_via_loadFromJson() {
        val original = byteArrayOf(0x01, 0x02, 0x03)
        val journal = goodJournal(original)
        val text = JournalCodec.encode(journal)
        val result = JournalCodec.loadFromJson(text, original.toCommonByteBuffer())
        val loaded = assertIs<JournalLoadResult.Loaded>(result)
        assertEquals(journal, loaded.journal)
    }

    @Test
    fun loaded_when_hash_and_length_match() {
        val original = byteArrayOf(0x10, 0x20)
        val text = JournalCodec.encode(goodJournal(original))
        val result = JournalCodec.loadFromJson(text, original.toCommonByteBuffer())
        assertIs<JournalLoadResult.Loaded>(result)
    }

    @Test
    fun mismatch_HASH_when_bytes_have_changed() {
        val originalA = byteArrayOf(0x01, 0x02, 0x03)
        val text = JournalCodec.encode(goodJournal(originalA))
        // Same length, different content → HASH mismatch (length check passes
        // first, hash check second).
        val originalB = byteArrayOf(0x01, 0x02, 0x04)
        val result = JournalCodec.loadFromJson(text, originalB.toCommonByteBuffer())
        val mm = assertIs<JournalLoadResult.Mismatch>(result)
        assertEquals(MismatchReason.HASH, mm.reason)
        // Parsed cleanly — Mismatch.journal is non-null for HASH/LENGTH.
        assertEquals(originalA.size.toLong(), mm.journal?.originalLength)
    }

    @Test
    fun mismatch_LENGTH_when_file_is_resized() {
        val originalA = byteArrayOf(0x01, 0x02, 0x03)
        val text = JournalCodec.encode(goodJournal(originalA))
        val originalB = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val result = JournalCodec.loadFromJson(text, originalB.toCommonByteBuffer())
        val mm = assertIs<JournalLoadResult.Mismatch>(result)
        assertEquals(MismatchReason.LENGTH, mm.reason)
        assertEquals(originalA.size.toLong(), mm.journal?.originalLength)
    }

    @Test
    fun mismatch_CORRUPT_when_json_is_malformed() {
        val original = byteArrayOf(0x01, 0x02)
        val result = JournalCodec.loadFromJson("{this is not json", original.toCommonByteBuffer())
        val mm = assertIs<JournalLoadResult.Mismatch>(result)
        assertEquals(MismatchReason.CORRUPT, mm.reason)
        // Spec §8: pure layer doesn't surface a half-parsed object on CORRUPT.
        assertNull(mm.journal)
    }

    @Test
    fun mismatch_CORRUPT_when_required_fields_missing() {
        val original = byteArrayOf(0x01, 0x02)
        // Valid JSON but missing every Journal field — kotlinx surfaces this
        // as SerializationException → CORRUPT.
        val result = JournalCodec.loadFromJson("""{}""", original.toCommonByteBuffer())
        val mm = assertIs<JournalLoadResult.Mismatch>(result)
        assertEquals(MismatchReason.CORRUPT, mm.reason)
    }

    @Test
    fun mismatch_SCHEMA_UNKNOWN_when_schema_is_in_the_future() {
        val original = byteArrayOf(0x01, 0x02)
        val text = """
            {"schema":99,"originalHash":"sha256:00","originalLength":2,"entries":[]}
        """.trimIndent()
        val result = JournalCodec.loadFromJson(text, original.toCommonByteBuffer())
        val mm = assertIs<JournalLoadResult.Mismatch>(result)
        assertEquals(MismatchReason.SCHEMA_UNKNOWN, mm.reason)
        // We don't surface a parsed object for unknown schemas — we don't
        // trust that future-version semantics survive a v1 reader.
        assertNull(mm.journal)
    }

    @Test
    fun schema_check_runs_before_length_and_hash_checks() {
        // Schema=99 with length+hash that would otherwise pass — confirms the
        // ordering: schema check fires first so we never accidentally accept
        // a future-version journal just because its hash happened to match.
        val original = byteArrayOf(0x01, 0x02)
        val correctHash = Journal.ORIGINAL_HASH_PREFIX + Sha256.hex(original.toCommonByteBuffer())
        val text = """
            {"schema":99,"originalHash":"$correctHash","originalLength":2,"entries":[]}
        """.trimIndent()
        val result = JournalCodec.loadFromJson(text, original.toCommonByteBuffer())
        val mm = assertIs<JournalLoadResult.Mismatch>(result)
        assertEquals(MismatchReason.SCHEMA_UNKNOWN, mm.reason)
    }
}