package org.jetbrains.hexana.document

import org.jetbrains.hexana.document.internal.ImmutableBinaryDocument
import org.jetbrains.hexana.document.internal.PieceTableDocument
import org.jetbrains.hexana.document.journal.JournalPersister
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Creates a read-only [BinaryDocument] backed by [bytes]. The buffer is **not
 * copied** — callers must not mutate it after handoff. Calls to [BinaryDocument.edit]
 * throw — use [editableBinaryDocumentOf] for a mutable backing.
 */
public fun binaryDocumentOf(bytes: CommonByteBuffer): BinaryDocument = ImmutableBinaryDocument(bytes)

/**
 * Creates an editable [BinaryDocument] backed by a piece table. The original
 * [bytes] are referenced directly (not copied) and never mutated; every edit
 * is recorded in an in-memory journal. The [persister] hook is invoked on
 * each successful `commit()`, `undo()`, or `redo()` so the IDE plugin can
 * write the sidecar via the §6 atomic-rename pattern. [JournalPersister.NoOp]
 * is the default — useful for tests and read-only callers.
 *
 * [timestampProvider] is injected to keep the document deterministic in
 * tests; production callers wire a real UTC clock.
 */
public fun editableBinaryDocumentOf(
    bytes: CommonByteBuffer,
    persister: JournalPersister = JournalPersister.NoOp,
    timestampProvider: () -> String = { "" },
): EditableBinaryDocument = PieceTableDocument(bytes, persister, timestampProvider)

/**
 * Editable extension of [BinaryDocument] exposing the undo/redo API. Returned
 * by [editableBinaryDocumentOf]. The base [BinaryDocument] interface stays
 * backwards-compatible; only callers that need undo/redo see this surface.
 */
public interface EditableBinaryDocument : BinaryDocument {
    public fun canUndo(): Boolean
    public fun canRedo(): Boolean
    public fun undo()
    public fun redo()

    /**
     * Asks the configured [JournalPersister] to drop the on-disk sidecar
     * (calls `save(null)`). In-memory state — entries, snapshots, cursor,
     * undo/redo — is unchanged; the next [edit] commit will recreate the
     * sidecar from the current journal contents.
     *
     * Used by the IDE-side Finalize action: the user has written the
     * post-edit bytes to a separate output file, so the sidecar bound to
     * the original is no longer the source of truth and should disappear
     * from the directory until the user makes a new edit.
     */
    public fun clearPersistedJournal()

    /**
     * Replays [journal] into the live state as if each entry had been
     * committed in order. Used at file-open time to restore in-progress
     * edits from a previously-persisted sidecar.
     *
     * Persistence is **suppressed** during replay — the journal we're
     * loading came from disk, so re-saving each entry would loop. After
     * replay finishes, normal commits/undos/redos resume normal
     * persistence.
     *
     * Must be called on a fresh document (no commits applied yet).
     * [journal]'s `originalLength` and `originalHash` are NOT validated
     * here — the caller is expected to validate via
     * [org.jetbrains.hexana.document.journal.JournalCodec.loadFromJson]
     * before invoking this.
     */
    public fun loadJournal(journal: org.jetbrains.hexana.document.journal.Journal)
}
