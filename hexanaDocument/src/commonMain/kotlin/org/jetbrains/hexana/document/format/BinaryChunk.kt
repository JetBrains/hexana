package org.jetbrains.hexana.document.format

import kotlin.jvm.JvmInline

/**
 * A logical section/element kind, scoped per format. Phase 1 only WASM kinds
 * exist; Phase 3+ adapters (ELF, PE/COFF, JVM, LLVM bitcode) define their own
 * constants. The wrapper type lets the generic UI (sticky headers, status bar)
 * treat kinds opaquely while format-specific code matches on concrete values.
 *
 * Convention: prefix with the format identifier, e.g. `"wasm.code"`,
 * `"elf.symtab"`, `"jvm.constant_pool"`. Used as the key for sticky-header
 * grouping and for symbol-resolver lookups.
 */
@JvmInline
public value class SectionKind(public val id: String)

/**
 * Stable identity for a [BinaryChunk] across re-parses. Used by UI state
 * (selection, scroll restoration, search results) so structural edits don't
 * silently drop the user's place. See `editor-engine-expert` agent rules for
 * the three-tier identity strategy.
 */
public sealed interface ChunkId {
    /** Identity by section + symbolic name (function name, export name, …). Survives reflow. */
    public data class Symbolic(public val sectionKind: SectionKind, public val name: String) : ChunkId

    /** Identity by section + element index. Stable only when the section's element count is unchanged. */
    public data class Indexed(public val sectionKind: SectionKind, public val index: Int) : ChunkId

    /** Identity for a section header chunk. */
    public data class Header(public val sectionKind: SectionKind) : ChunkId
}

/**
 * One renderable unit in the virtualized viewer. Each chunk maps to a single
 * `LazyColumn` item.
 *
 * @property id stable across re-parses; never use list index for UI state.
 * @property byteRange logical offsets in the [org.jetbrains.hexana.document.BinaryDocument].
 * @property estimatedLines hint for [LazyColumn] heights before render. Refined on first render.
 */
public sealed interface BinaryChunk {
    public val id: ChunkId
    public val byteRange: LongRange
    public val estimatedLines: Int
    public val kind: SectionKind

    /**
     * Header row for a section (e.g., `(; type section: 87 entries, 2.4KB ;)`). Rendered
     * by the panel as a sticky header.
     */
    public data class SectionHeader(
        override val id: ChunkId,
        override val byteRange: LongRange,
        override val estimatedLines: Int,
        override val kind: SectionKind,
        public val elementCount: Int,
        public val totalSize: Long,
    ) : BinaryChunk

    /**
     * One element in a section (one function body, one import, one export, …).
     * For WASM Phase 1, the Function section is a single Element chunk
     * representing all entries.
     */
    public data class Element(
        override val id: ChunkId,
        override val byteRange: LongRange,
        override val estimatedLines: Int,
        override val kind: SectionKind,
        public val indexInSection: Int,
        public val displayName: String?,
        /**
         * For chunks nested under a collapsible sub-group (e.g. the member
         * types of a WasmGC `(rec …)` group), the [id] of that group's header
         * chunk. The virtualized panel hides such chunks unless the group is
         * expanded — letting one rec group of tens of thousands of types fold
         * to a single line while each member stays an independently
         * virtualized item when expanded. `null` for chunks with no sub-group.
         */
        public val groupId: ChunkId? = null,
    ) : BinaryChunk
}

/**
 * Lazy, indexed sequence of chunks. Implementations may compute chunks on
 * demand — important for ELF binaries with millions of relocations or large
 * WASM data segments.
 *
 * The contract guarantees:
 * - [totalChunks] is finite and known up-front.
 * - [chunkAt] returns the same identity twice for the same index (within one parse).
 * - Chunks are ordered by [BinaryChunk.byteRange] start.
 */
public interface ChunkTree {
    public val totalChunks: Int

    public fun chunkAt(index: Int): BinaryChunk

    /** Returns the chunk index whose [BinaryChunk.byteRange] contains [offset], or `null`. */
    public fun chunkContainingByte(offset: Long): Int?

    /** Looks up a chunk's current index by stable [ChunkId]. */
    public fun chunkForId(id: ChunkId): Int?
}
