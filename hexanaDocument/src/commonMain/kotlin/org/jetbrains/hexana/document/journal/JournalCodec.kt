package org.jetbrains.hexana.document.journal

import kotlinx.serialization.SerializationException

/**
 * Pure encode/decode + load-side validation for a [Journal] sidecar file.
 *
 * **Filesystem-free.** [JournalCodec] never opens, reads, or writes a file.
 * Callers (the IDE plugin) handle the actual I/O — they read a file's text
 * and pass it to [loadFromJson] along with the original-binary bytes; they
 * receive a [Journal] back from [encode] as a `String` and write it via the
 * §6 atomic-rename pattern.
 *
 * **Mismatch reporting.** Returns [JournalLoadResult.Loaded] on success and
 * [JournalLoadResult.Mismatch] for every failure case the spec calls out
 * (§7 hash binding, §8 mismatch UX, §9 corrupt-sidecar handling). The pure
 * layer never deletes or modifies the sidecar — it only reports. The IDE
 * plugin's notification handler owns Discard / Backup-then-discard.
 *
 * **No `Empty`.** The sidecar-absent case ([JournalLoadResult.Empty]) is
 * constructed by the caller when the file simply doesn't exist on disk.
 * The codec only sees text that did exist somewhere.
 */
public object JournalCodec {

    /** Renders [journal] to a JSON string suitable for writing to the sidecar file. */
    public fun encode(journal: Journal): String =
        JournalJson.encodeToString(Journal.serializer(), journal)

    /**
     * Parses [jsonText] (the sidecar's contents) and validates it against
     * [originalBytes] (the on-disk binary's current bytes).
     *
     * Returns:
     * - [JournalLoadResult.Loaded] — JSON parsed cleanly, schema is recognized,
     *   and `originalHash` + `originalLength` match [originalBytes].
     * - [JournalLoadResult.Mismatch] with reason set per the spec:
     *   - [MismatchReason.CORRUPT] — JSON couldn't be parsed at all.
     *   - [MismatchReason.SCHEMA_UNKNOWN] — JSON parsed but `schema` is unknown
     *     to this build (future-proofing; the current build accepts only
     *     [Journal.SCHEMA_V1]).
     *   - [MismatchReason.LENGTH] — `originalLength` disagrees with the
     *     binary's actual size.
     *   - [MismatchReason.HASH] — sha256 of `originalBytes` doesn't match
     *     `originalHash`.
     *
     * On a `Mismatch`, [JournalLoadResult.Mismatch.journal] is null for
     * CORRUPT and SCHEMA_UNKNOWN (we don't trust the bytes enough to surface
     * a half-parsed object) and non-null for HASH and LENGTH (parsed cleanly,
     * just bound to the wrong file).
     */
    public fun loadFromJson(jsonText: String, originalBytes: ByteArray): JournalLoadResult {
        val journal: Journal = try {
            JournalJson.decodeFromString(Journal.serializer(), jsonText)
        } catch (_: SerializationException) {
            return JournalLoadResult.Mismatch(journal = null, reason = MismatchReason.CORRUPT)
        } catch (_: IllegalArgumentException) {
            // kotlinx-serialization can also surface IAE for some structural failures.
            return JournalLoadResult.Mismatch(journal = null, reason = MismatchReason.CORRUPT)
        }

        if (journal.schema != Journal.SCHEMA_V1) {
            return JournalLoadResult.Mismatch(journal = null, reason = MismatchReason.SCHEMA_UNKNOWN)
        }
        if (journal.originalLength != originalBytes.size.toLong()) {
            return JournalLoadResult.Mismatch(journal = journal, reason = MismatchReason.LENGTH)
        }
        val expected = journal.originalHash.removePrefix(Journal.ORIGINAL_HASH_PREFIX)
        val actual = Sha256.hex(originalBytes)
        if (!expected.equals(actual, ignoreCase = true)) {
            return JournalLoadResult.Mismatch(journal = journal, reason = MismatchReason.HASH)
        }
        return JournalLoadResult.Loaded(journal)
    }
}

/**
 * Outcome of attempting to load a journal sidecar. Returned by [JournalCodec]
 * for files-with-content; callers construct [Empty] themselves when the
 * sidecar is absent on disk.
 */
public sealed interface JournalLoadResult {

    /** Sidecar parsed cleanly and matches the binary. Safe to replay. */
    public data class Loaded(public val journal: Journal) : JournalLoadResult

    /** No sidecar exists. New session — start with an empty journal. */
    public data class Empty(
        public val originalHash: String,
        public val originalLength: Long,
    ) : JournalLoadResult

    /**
     * Sidecar exists but cannot be applied. [journal] is non-null when the
     * file parsed but failed validation (HASH / LENGTH mismatch); null when
     * we don't trust the parse output (CORRUPT / SCHEMA_UNKNOWN).
     */
    public data class Mismatch(
        public val journal: Journal?,
        public val reason: MismatchReason,
    ) : JournalLoadResult
}

/**
 * Why a sidecar was rejected. Each case maps to the same Discard /
 * Backup-then-discard UX in the IDE plugin (see §8 of the design spec); only
 * the human-readable message differs.
 */
public enum class MismatchReason {
    /** sha256 of the on-disk binary doesn't match the journal's `originalHash`. */
    HASH,

    /** Byte length of the on-disk binary doesn't match the journal's `originalLength`. */
    LENGTH,

    /** Sidecar JSON couldn't be parsed (truncated, malformed, hand-edited badly). */
    CORRUPT,

    /** Sidecar parsed but its `schema` value isn't recognized by this build. */
    SCHEMA_UNKNOWN,
}
