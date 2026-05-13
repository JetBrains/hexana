package org.jetbrains.hexana.document

/**
 * Notified after each [EditTransaction.commit]. Listeners run off the UI
 * thread; if a listener needs to touch UI state it must marshal back itself.
 *
 * **Phase 1**: registered listeners are stored but never fired (the document
 * is read-only). The wiring is exercised end-to-end so Phase 2 needs no
 * call-site changes.
 */
public fun interface DocumentListener {
    public fun documentChanged(event: DocumentChangeEvent)
}

/**
 * Payload for [DocumentListener.documentChanged]. Carries pre- and post-commit
 * snapshots plus the list of byte-level edits applied — sufficient to remap
 * chunk byte ranges, invalidate caches, or trigger a targeted re-parse.
 */
public data class DocumentChangeEvent(
    public val before: BinaryDocumentSnapshot,
    public val after: BinaryDocumentSnapshot,
    public val edits: List<EditOp>,
)
