package org.jetbrains.hexana.shared

/**
 * Identifies the instruction a binary breakpoint is anchored to.
 *
 * Two sub-types: [Wasm] for WebAssembly modules and [Native] for ELF/Mach-O/PE binaries.
 * Secondary keys ([Wasm.funcName], [Native.symbolName]) are rehoming hints only; they do
 * not participate in equality and must never be used as unique keys.
 */
sealed interface BinaryAnchor {
    /**
     * Breakpoint anchor in a WebAssembly module.
     *
     * @param funcIdx global function index (imports first)
     * @param byteOffset absolute module byte offset of the instruction
     * @param funcName secondary rehome key (name section); null if the name section is absent
     * @param instrOrdinal 0-based instruction ordinal within the function body
     */
    data class Wasm(
        val funcIdx: Int,
        val byteOffset: Long,
        val funcName: String?,
        val instrOrdinal: Int,
    ) : BinaryAnchor

    /**
     * Breakpoint anchor in a native binary (ELF, Mach-O, PE).
     *
     * @param virtualAddress load-time virtual address of the instruction
     * @param symbolName secondary rehome key; null if stripped
     * @param offsetInSymbol byte offset from the symbol's start address
     */
    data class Native(
        val virtualAddress: ULong,
        val symbolName: String?,
        val offsetInSymbol: ULong,
    ) : BinaryAnchor
}

/**
 * Content identity of a binary artifact.
 *
 * [contentSha256] is always present (lowercase hex). [elfBuildId] is optional and
 * present only for ELF files that carry an NT_GNU_BUILD_ID note.
 *
 * Two identities [matches] when they share either a SHA-256 or a build-id.
 */
data class BinaryIdentity(
    val contentSha256: String,
    val elfBuildId: String? = null,
) {
    fun matches(other: BinaryIdentity): Boolean =
        contentSha256 == other.contentSha256 || (elfBuildId != null && elfBuildId == other.elfBuildId)
}

/**
 * A persistent binary breakpoint.
 *
 * [id] is stable across rehoming. [filePath] is a display/rehome hint only and
 * is not part of the breakpoint's identity.
 */
data class BinaryBreakpoint(
    val id: String,
    val identity: BinaryIdentity,
    val anchor: BinaryAnchor,
    val enabled: Boolean,
    val rehomed: Boolean,
    /** Rehome failed: breakpoint is visible but disabled and was never materialized. */
    val stale: Boolean,
    /** Display and rehome hint only. NOT part of identity. */
    val filePath: String?,
)
