package org.jetbrains.hexana.document.journal

/**
 * Pluggable persistence hook for an editable [BinaryDocument][org.jetbrains.hexana.document.BinaryDocument].
 *
 * Called by the document on every state change that mutates `entries[0..cursor]`
 * — `commit()`, `undo()`, and `redo()`. The journal layer never opens a file
 * itself; the IDE plugin (or a test) implements this interface with the actual
 * I/O — typically the §6 atomic-rename pattern from the design spec.
 *
 * @see save
 */
public fun interface JournalPersister {

    /**
     * Persists the journal state. Called synchronously from the thread that
     * drove the state change.
     *
     * @param journal the active journal (`entries[0..cursor]`) when there is
     *   at least one applied entry, or `null` to indicate the sidecar should
     *   be removed (the user has undone all the way back to the original).
     *   `null` is the explicit "no edits applied" signal — symmetric with §6's
     *   "the sidecar is created on the first commit" rule.
     */
    public fun save(journal: Journal?)

    public companion object {
        /** No-op persister, useful for tests and read-only callers. */
        public val NoOp: JournalPersister = JournalPersister { /* drop */ }
    }
}
