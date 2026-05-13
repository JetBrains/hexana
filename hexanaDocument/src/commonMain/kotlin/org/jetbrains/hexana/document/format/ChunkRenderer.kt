package org.jetbrains.hexana.document.format

import org.jetbrains.hexana.document.BinaryDocumentSnapshot

/**
 * Format-specific chunk-to-text renderer. Each call produces a [TokenStream]
 * for one chunk; renderers are stateless and safe to call from any thread.
 *
 * Phase 1: WASM renderer only, emits Hexana-flavored WAT. Phase 3+: ELF/PE
 * disassemblers, JVM bytecode formatters, etc.
 */
public fun interface ChunkRenderer {
    public fun render(snapshot: BinaryDocumentSnapshot, chunk: BinaryChunk): TokenStream
}

/**
 * Output of a [ChunkRenderer]. A flat list of tokens; line breaks are encoded
 * as [RenderTokenKind.LINE_BREAK] tokens so callers can split into lines or
 * render as a continuous stream.
 */
public data class TokenStream(public val tokens: List<RenderToken>) {
    public val lineCount: Int by lazy {
        // 1 + number of LINE_BREAK tokens; an empty stream is 0 lines.
        if (tokens.isEmpty()) 0 else 1 + tokens.count { it.kind == RenderTokenKind.LINE_BREAK }
    }

    public companion object {
        public val EMPTY: TokenStream = TokenStream(emptyList())
    }
}

/**
 * One token in a rendered chunk. [text] is what gets drawn; [kind] drives
 * syntax highlighting; [sourceOffset] enables jump-to-offset and hex-view
 * cross-link by mapping the rendered character back to its source byte.
 *
 * @property navTarget if non-null, this token is clickable and navigates
 * elsewhere (another chunk, a byte offset, …).
 */
public data class RenderToken(
    public val text: String,
    public val kind: RenderTokenKind,
    public val sourceOffset: Long? = null,
    public val navTarget: NavTarget? = null,
)

/**
 * Token category for highlighting. Broad and format-agnostic; format-specific
 * nuances (e.g., WASM control opcode vs numeric opcode) are encoded by
 * grouping into the same category and letting downstream highlighters key off
 * [RenderToken.text] when finer-grained rules are needed.
 */
public enum class RenderTokenKind {
    KEYWORD,
    IDENTIFIER,
    NUMBER,
    STRING,
    COMMENT,
    PUNCTUATION,
    OPERATOR,
    TYPE_REF,
    FUNC_REF,
    LABEL_REF,
    LOCAL_REF,
    GLOBAL_REF,
    OFFSET_ANNOTATION,
    RAW_BYTES,
    WHITESPACE,
    LINE_BREAK,
    ERROR,
}

/** Navigation target for a clickable [RenderToken]. */
public sealed interface NavTarget {
    /** Jump to another chunk (e.g., function call → callee's chunk). */
    public data class Chunk(public val id: ChunkId) : NavTarget

    /** Jump to a specific byte offset in the document (e.g., into the hex view). */
    public data class Offset(public val offset: Long) : NavTarget

    /** Open an external URL (e.g., spec link for an opcode). */
    public data class External(public val url: String) : NavTarget
}
