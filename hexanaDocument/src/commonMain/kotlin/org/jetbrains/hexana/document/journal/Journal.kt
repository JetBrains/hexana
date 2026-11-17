package org.jetbrains.hexana.document.journal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * On-disk JSON shape of Hexana's binary edit journal. See
 * `docs/superpowers/specs/2026-05-08-binary-edit-journal-design.md` for the
 * full design rationale; the highlights:
 *
 * - The original file stays read-only; the journal is the only mutable artifact.
 * - Replay = `for (entry in entries) for (op in entry.ops) apply(op, buf)`.
 * - All offsets are *logical* — measured against the document state right
 *   before that op runs, not against the original file's coordinate space.
 * - One [JournalEntry] per `EditTransaction.commit()`. The whole entry is
 *   applied atomically; listeners only ever see the post-commit state.
 *
 * The schema number is bumped on incompatible changes; an older reader
 * encountering a newer schema treats the journal as a mismatch (see
 * [JournalLoadResult.Mismatch] with reason `SCHEMA_UNKNOWN`).
 */
@Serializable
public data class Journal(
    public val schema: Int = SCHEMA_V1,
    public val originalHash: String,
    public val originalLength: Long,
    public val entries: List<JournalEntry>,
) {
    public companion object {
        public const val SCHEMA_V1: Int = 1
        public const val ORIGINAL_HASH_PREFIX: String = "sha256:"
    }
}

/**
 * One commit's worth of edits — a non-empty, ordered list of [JournalOp]s
 * applied atomically. Within an entry, each op's offsets are logical against
 * the buffer state *after* prior ops in the same entry have been applied.
 */
@Serializable
public data class JournalEntry(
    public val timestamp: String,
    public val ops: List<JournalOp>,
)

/**
 * Per-op vocabulary. The `op` discriminator identifies which subtype is on the
 * wire. `data` (when present) is RFC 4648 standard-alphabet base64 of the
 * literal bytes to insert; offsets are logical (see [Journal]).
 *
 * `replace` is intentionally a primitive instead of being decomposed into
 * `delete + insert`: a typical hex-column keystroke is a 1-byte replace, and
 * decomposing every keystroke would double the journal size.
 */
@Serializable
public sealed interface JournalOp {

    @Serializable
    @SerialName("replace")
    public data class Replace(
        public val start: Long,
        public val end: Long,
        public val data: String,
    ) : JournalOp

    @Serializable
    @SerialName("insert")
    public data class Insert(
        public val offset: Long,
        public val data: String,
    ) : JournalOp

    @Serializable
    @SerialName("delete")
    public data class Delete(
        public val start: Long,
        public val end: Long,
    ) : JournalOp
}
