package org.jetbrains.hexana.document

import org.jetbrains.hexana.document.internal.ImmutableBinaryDocument

/**
 * Creates a read-only [BinaryDocument] backed by [bytes]. The array is **not
 * copied** — callers must not mutate it after handoff.
 *
 * Phase 1: this is the only factory. Phase 2 adds piece-table-backed factories
 * (e.g. `binaryDocumentFromMmap(path)`).
 */
public fun binaryDocumentOf(bytes: ByteArray): BinaryDocument = ImmutableBinaryDocument(bytes)
