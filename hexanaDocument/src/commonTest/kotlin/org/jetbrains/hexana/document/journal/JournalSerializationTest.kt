package org.jetbrains.hexana.document.journal

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trips a [Journal] through the JSON encoder/decoder. Locks in the wire
 * shape: the `op` discriminator field, the offset/start/end/data fields, and
 * `originalHash` / `originalLength` / `entries` at the top level. Future
 * refactors that change any of those will break these tests before they break
 * a real on-disk sidecar.
 */
class JournalSerializationTest {

    private val json = JournalJson

    @Test
    fun roundtrip_with_all_three_op_kinds() {
        val original = Journal(
            originalHash = "sha256:fb1c00deadbeefcafe000000000000000000000000000000000000000000000000",
            originalLength = 12345L,
            entries = listOf(
                JournalEntry(
                    timestamp = "2026-05-08T12:34:56Z",
                    ops = listOf(
                        JournalOp.Replace(start = 16, end = 20, data = "AAEC0w=="),
                        JournalOp.Insert(offset = 100, data = "AA=="),
                        JournalOp.Delete(start = 200, end = 210),
                    ),
                ),
            ),
        )
        val encoded = json.encodeToString(Journal.serializer(), original)
        val decoded = json.decodeFromString(Journal.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun wire_shape_uses_op_discriminator_per_spec() {
        val journal = Journal(
            originalHash = "sha256:00",
            originalLength = 0L,
            entries = listOf(
                JournalEntry(
                    timestamp = "2026-05-08T00:00:00Z",
                    ops = listOf(
                        JournalOp.Replace(0L, 1L, "QQ=="),
                        JournalOp.Insert(0L, "Qg=="),
                        JournalOp.Delete(0L, 1L),
                    ),
                ),
            ),
        )
        val encoded = json.encodeToString(Journal.serializer(), journal)

        // Discriminator field must be `op`, not `type` (kotlinx default).
        assertContains(encoded, """"op":"replace"""")
        assertContains(encoded, """"op":"insert"""")
        assertContains(encoded, """"op":"delete"""")

        // Replace + Insert carry `data`; delete does not.
        assertContains(encoded, """"data":"QQ=="""")
        assertContains(encoded, """"data":"Qg=="""")
        assertTrue(
            "delete entry must not include `data`",
        ) { !encoded.substringAfter(""""op":"delete"""").substringBefore("}").contains(""""data"""") }
    }

    @Test
    fun empty_entries_decodes() {
        val src = """{"schema":1,"originalHash":"sha256:0","originalLength":0,"entries":[]}"""
        val decoded = json.decodeFromString(Journal.serializer(), src)
        assertEquals(0, decoded.entries.size)
    }

    @Test
    fun unknown_schema_field_is_preserved_after_decode() {
        // schema=2 still parses as the same Journal shape — schema gate happens
        // at the load layer (JournalLoadResult.Mismatch reason=SCHEMA_UNKNOWN),
        // not at the serializer level.
        val src = """{"schema":2,"originalHash":"sha256:0","originalLength":0,"entries":[]}"""
        val decoded = json.decodeFromString(Journal.serializer(), src)
        assertEquals(2, decoded.schema)
    }
}
