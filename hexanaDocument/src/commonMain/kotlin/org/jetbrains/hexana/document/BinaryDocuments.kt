package org.jetbrains.hexana.document

import org.jetbrains.hexana.document.internal.ImmutableBinaryDocument
import org.jetbrains.hexana.document.internal.PieceTableDocument
import org.jetbrains.hexana.document.journal.JournalPersister

/**
 * Creates a read-only [BinaryDocument] backed by [bytes]. The array is **not
 * copied** — callers must not mutate it after handoff. Calls to [BinaryDocument.edit]
 * throw — use [editableBinaryDocumentOf] for a mutable backing.
 */
public fun binaryDocumentOf(bytes: ByteArray): BinaryDocument = ImmutableBinaryDocument(bytes)

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
    bytes: ByteArray,
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
}
