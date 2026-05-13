package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.RenderToken
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
