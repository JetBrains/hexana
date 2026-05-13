package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.SectionKind
import org.jetbrains.hexana.wasm.opcodes.OpCode

/**
 * One on-screen line in the virtualized WAT panel. The panel groups the
 * renderer's flat token stream into rows so it can give each line its own
 * Compose composable — a static `Text` for read-only rows, a
 * `BasicTextField` for [editable] rows whose immediates the user can
 * change inline.
 *
 * @property tokens the row's tokens (no trailing `LINE_BREAK`).
 * @property editable per-row metadata for inline editing, or `null` if the
 *   row is not in the inline-editable subset (header / locals / close rows
 *   in code chunks; structural / SIMD / GC / atomic / EH / table /
 *   reference instruction rows; non-code chunk rows).
 */
public data class RenderedRow(
    public val tokens: List<RenderToken>,
    public val editable: EditableRowInfo? = null,
    /**
     * Target of the IDE's "Go To Declaration" / click navigation on this row.
     *
     * - For an editable `call N` row in the Code section: points at the
     *   Function-section row that declares `funcN` (one step "toward the
     *   implementation"; pressing Cmd+B again on the Function row continues
     *   to the body).
     * - For a Function-section row: points at the Code-section entry for
     *   the function (the body).
     * - `null` for rows that have no follow target.
     */
    public val followTarget: FollowTarget? = null,
    /**
     * Role discriminator the panel uses to give specific rows special
     * behaviour (the Data section's expander row click, body-row hiding
     * when the entry is collapsed, …). Defaults to [RowKind.Generic] for
     * every chunk that doesn't need it.
     */
    public val kind: RowKind = RowKind.Generic,
)

/**
 * How the renderer should display the bytes of a Data-section entry's body.
 * All multi-byte forms are little-endian (the WASM spec mandates LE for
 * memory access; exposing an endianness toggle would only invite misuse).
 *
 * Phase 2 of the Data-section work. Phase 1 baseline is [U8_HEX].
 */
public enum class DataViewMode {
    /** `xxd`-style: one hex pair per byte, ASCII sidecar on the right. */
    U8_HEX,

    /** Eight 16-bit little-endian values per 16-byte row. */
    U16_HEX,

    /** Four 32-bit little-endian values per 16-byte row. */
    U32_HEX,

    /** Two 64-bit little-endian values per 16-byte row. */
    U64_HEX,

    /** 64 chars per row of printable ASCII (`.` for non-printable). No hex column. */
    ASCII,

    /** UTF-8-decoded, 64 chars per row. Control chars and invalid sequences become `.`. */
    UTF8,
}

/**
 * Per-row role tag. The renderer attaches this to specific rows so the panel
 * can route clicks / hide rows / draw alternate affordances without parsing
 * tokens. Most rows leave the default [Generic].
 */
public sealed interface RowKind {
    /** Default — render as plain text / editable text, no special handling. */
    public data object Generic : RowKind

    /** The single header line a Data-section entry chunk emits. */
    public data object DataHeader : RowKind

    /**
     * Clickable `▶ show N bytes` / `▼ hide` row between the header and the
     * body rows. The panel toggles the chunk's membership in its
     * `expandedDataChunks` set on click.
     */
    public data class DataExpander(public val bodyByteCount: Int) : RowKind

    /**
     * One hex+ascii row of a Data-record's payload. The panel hides these
     * unless the containing chunk is in `expandedDataChunks`.
     */
    public data object DataBody : RowKind

    /**
     * A descriptor row of a Data-section entry — the mode byte, optional
     * `memidx` ULEB, each init-expression opcode (rendered like a code
     * row: offset + hex + mnemonic), and the payload-length ULEB. The
     * panel hides these unless the chunk is expanded; they sit between
     * the expander row and the data body rows so the descriptor and
     * payload are visually separated.
     */
    public data object DataDescriptor : RowKind

    /**
     * Clickable `▶ show details` / `▼ hide details` row sitting below an
     * Import-section entry's header. The panel toggles the chunk's
     * membership in `expandedImportChunks` on click.
     */
    public data object ImportExpander : RowKind

    /**
     * One descriptor row of an Import-section entry — the module name
     * slice, the symbol name slice, or the import descriptor slice. The
     * panel hides these unless the chunk is in `expandedImportChunks`.
     */
    public data object ImportDescriptor : RowKind

    /**
     * Clickable `▶ show details` / `▼ hide details` row sitting below an
     * Export-section entry's header. Mirrors [ImportExpander].
     */
    public data object ExportExpander : RowKind

    /**
     * One descriptor row of an Export-section entry — the exported-name
     * slice or the descriptor slice (kind byte + index ULEB). The panel
     * hides these unless the chunk is in `expandedExportChunks`. The
     * descriptor slice carries a [FollowTarget] for function exports so
     * the user can navigate to the function's declaration row.
     */
    public data object ExportDescriptor : RowKind
}

/**
 * A navigable funcidx + which section to land in. The panel resolves this
 * to a chunk via the chunk tree and asks the navigator to scroll there.
 */
public data class FollowTarget(
    public val funcIdx: Int,
    public val section: SectionKind,
)

/**
 * Per-row metadata needed to commit an inline edit. Together with the
 * user's edited text, [EditableRowEncoder.encode] uses these fields to
 * produce the [JournalOp][org.jetbrains.hexana.document.journal.JournalOp]
 * list the edit transforms into.
 *
 * @property op the original opcode parsed from the binary; used to enforce
 *   the mnemonic-preserving rule (the user can change immediates but not
 *   the op kind).
 * @property opStart logical byte offset of the opcode's first byte in the
 *   document — same coordinate space as
 *   [EditableBinaryDocument][org.jetbrains.hexana.document.EditableBinaryDocument].
 * @property opEncodedLength current encoded byte length of the row
 *   (opcode + immediates). The encoder rejects edits whose new canonical
 *   encoding differs in length, deferring length cascade to a later phase.
 * @property watText the editable portion of the row as currently rendered
 *   (mnemonic + immediates only — no offset gutter, no hex column, no
 *   trailing LEB annotation). This is the text the panel pre-fills the
 *   `BasicTextField` with.
 */
public data class EditableRowInfo(
    /**
     * For [Kind.OPCODE] rows: the original opcode parsed at [opStart]. The
     * encoder doesn't pin its dispatch to this value (D.1 made dispatch
     * mnemonic-keyed) — kept for callers that want context. `null` for
     * non-opcode rows ([Kind.LOCALS]).
     */
    public val op: OpCode? = null,
    public val opStart: Long,
    public val opEncodedLength: Int,
    public val watText: String,
    /** Discriminator for which encoder path applies to this row. */
    public val kind: Kind = Kind.OPCODE,
    /**
     * For `local.{get,set,tee}` rows only: total number of params + locals
     * in the containing function. Lets the encoder reject an edit like
     * `local.get 10` in a function with only 5 locals before producing
     * binary that the parser would later flag as malformed. Null for ops
     * where this constraint doesn't apply (numeric constants, branches,
     * memory ops, etc.).
     */
    public val localCount: Int? = null,
    /**
     * Surrounding-structure context used by the encoder to emit cascade
     * ops when a length-changing edit shifts byte offsets through the
     * function-body size ULEB128 and the section-size ULEB128. Populated
     * for code-chunk rows; null for non-code rows or when the renderer
     * couldn't resolve the surrounding structure.
     */
    public val cascade: CascadeContext? = null,
) {
    /**
     * What kind of editable row this is. Different kinds dispatch to
     * different encoder paths.
     */
    public enum class Kind {
        /** A function-body instruction: opcode + immediates. */
        OPCODE,
        /** The function's locals declaration: `(local <type>...)`. */
        LOCALS,
    }
}

/**
 * Byte-position metadata for the size prefixes that need to grow / shrink
 * alongside a length-changing inline edit. The structure of a code section
 * is `[id 0x0a] [sectionSize ULEB] [count ULEB] [funcEntry...]` where each
 * funcEntry is `[funcSize ULEB] [body bytes]`. When a row inside a body
 * grows by `delta`, the body's funcSize ULEB encodes the new length (and
 * may itself grow / shrink in width by `funcSizeDelta`), and the outer
 * sectionSize ULEB encodes the new section length (similarly).
 *
 * @property funcSizePrefixStart logical byte offset of the funcSize ULEB.
 * @property funcSizePrefixLength current encoded length of that ULEB in bytes.
 * @property currentBodyLength the value the funcSize ULEB currently encodes
 *   (i.e. body bytes only — locals declarations + instructions + closing
 *   `0x0B`, **not** including the funcSize prefix itself).
 * @property sectionSizePrefixStart logical byte offset of the sectionSize
 *   ULEB. Lives just past the 1-byte section-ID byte at the section start.
 * @property sectionSizePrefixLength current encoded length of that ULEB.
 * @property currentSectionLength the value the sectionSize ULEB currently
 *   encodes (section content only — the count ULEB + every funcEntry —
 *   not including the sectionSize prefix itself or the section-ID byte).
 */
public data class CascadeContext(
    public val funcSizePrefixStart: Long,
    public val funcSizePrefixLength: Int,
    public val currentBodyLength: Long,
    public val sectionSizePrefixStart: Long,
    public val sectionSizePrefixLength: Int,
    public val currentSectionLength: Long,
)
