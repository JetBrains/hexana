package org.jetbrains.hexana.document

import org.jetbrains.hexana.document.journal.Sha256
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Content hashes for callers outside this module, on every platform (the implementation is pure
 * Kotlin). Used for journal binding here, and for checking Subresource Integrity values such as the
 * `sha256-…` hashes in a Blazor WebAssembly boot manifest.
 */
object ContentDigest {
    /** The raw 32-byte SHA-256 of [input]; reads by absolute index, so [input]'s cursor is untouched. */
    fun sha256(input: CommonByteBuffer): ByteArray = Sha256.digest(input)
}
