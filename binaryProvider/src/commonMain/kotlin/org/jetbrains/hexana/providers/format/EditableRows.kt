package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64

/**
 * Classifies which `BinaryChunk.Element` rows in the virtualized WAT panel
 * are inline-editable (Phase C of the editing roadmap). Phase C ships
 * per-row WAT text editing; only the subset listed in [isEditable] gets a
 * Compose `BasicTextField` overlay — every other row stays read-only.
 *
 * The accepted set is deliberately narrow: ops whose only "editable
 * surface" is one or two ULEB128/SLEB128 immediates (or a MemArg, which is
 * just two ULEB128s). Everything structural — `block` / `loop` / `if` /
 * `else` / `end`, `br_table`, `call_indirect`, SIMD / GC / atomic / bulk
 * memory / EH / reference ops — is excluded. Editing a structural opcode
 * would require either a parser that understands scope (`block` open and
 * `end` close must balance) or richer surface-level UI; both are deferred
 * to a follow-up phase.
 *
 * Length-changing edits (e.g. `local.get 5` → `local.get 200` widening the
 * ULEB128 from 1 byte to 2) are explicitly in scope. The cascade through
 * the function body's size prefix and the code section's outer size prefix
 * is handled by the encoder + the document layer at edit time.
 */
public object EditableRows {

    /**
     * Returns true when [op] is in the inline-editable subset for Phase C.
     * Caller still needs to confirm the row corresponds to a function-body
     * instruction (header / locals / fallback rows aren't OpCode-based).
     */
    public fun isEditable(op: OpCode): Boolean = when (op) {
        // -- Numeric constants (SLEB128 / IEEE-754 immediates) -----------
        is I32.Const, is I64.Const, is F32.Const, is F64.Const -> true

        // -- Variable access (single ULEB128 index) ----------------------
        is OpCodes.Local.Get, is OpCodes.Local.Set, is OpCodes.Local.Tee,
        is OpCodes.Global.Get, is OpCodes.Global.Set -> true

        // -- Direct call (single ULEB128 funcidx) ------------------------
        is OpCodes.Flow.Call -> true

        // -- Conditional / unconditional branches (single ULEB128 labelidx) ---
        is OpCodes.Flow.Br, is OpCodes.Flow.BrIf -> true

        // -- Structural block-like openers (opcode + 1-byte blocktype) ---
        // `OpCodes.Flow.Block` covers `Block`, `Loop`, `If`, `Else` via the
        // class hierarchy. The encoder accepts the matching mnemonics with
        // an empty or `(result <vtype>)` blocktype; multi-value typeidx
        // forms are still rejected by the encoder (see `encodeBlockOp`).
        //
        // Scope balance — every `block`/`loop`/`if` needs a matching
        // `end` — is the user's responsibility. A break in the scope
        // structure produces an invalid binary; the live-provider rebuild
        // catches the parse failure and keeps the previous good state on
        // screen, so the user can undo.
        is OpCodes.Flow.Block -> true

        // -- Memory load/store (MemArg = align ULEB128 + offset ULEB128) -
        is OpCodes.Memory.i32.Load,
        is OpCodes.Memory.i32.Load8_S,
        is OpCodes.Memory.i32.Load8_U,
        is OpCodes.Memory.i32.Load16_S,
        is OpCodes.Memory.i32.Load16_U,
        is OpCodes.Memory.i32.Store,
        is OpCodes.Memory.i32.Store8,
        is OpCodes.Memory.i32.Store16,
        is OpCodes.Memory.i64.Load,
        is OpCodes.Memory.i64.Load8_S,
        is OpCodes.Memory.i64.Load8_U,
        is OpCodes.Memory.i64.Load16_S,
        is OpCodes.Memory.i64.Load16_U,
        is OpCodes.Memory.i64.Load32_S,
        is OpCodes.Memory.i64.Load32_U,
        is OpCodes.Memory.i64.Store,
        is OpCodes.Memory.i64.Store8,
        is OpCodes.Memory.i64.Store16,
        is OpCodes.Memory.i64.Store32,
        is OpCodes.Memory.f32.Load,
        is OpCodes.Memory.f32.Store,
        is OpCodes.Memory.f64.Load,
        is OpCodes.Memory.f64.Store -> true

        // -- Parametric / no-immediate control ops -----------------------
        is OpCodes.ParametricInstructions.Unreachable,
        is OpCodes.ParametricInstructions.Nop,
        is OpCodes.ParametricInstructions.Drop,
        is OpCodes.ParametricInstructions.Select -> true
        is OpCodes.Flow.Return -> true

        // -- i32 arithmetic / logic / shift / rotate / compare -----------
        is I32.Add, is I32.Sub, is I32.Mul,
        is I32.DivS, is I32.DivU,
        is I32.RemS, is I32.RemU,
        is I32.And, is I32.Or, is I32.Xor,
        is I32.Shl, is I32.ShrS, is I32.ShrU,
        is I32.Rotl, is I32.Rotr,
        is I32.Clz, is I32.Ctz, is I32.PopCnt,
        is I32.Eqz, is I32.Eq, is I32.Ne,
        is I32.LTS, is I32.LTU,
        is I32.GTS, is I32.GTU,
        is I32.LES, is I32.LEU,
        is I32.GES, is I32.GEU -> true

        // -- i32 convert / extend / reinterpret --------------------------
        is I32.WrapI64,
        is I32.TruncF32S, is I32.TruncF32U,
        is I32.TruncF64S, is I32.TruncF64U,
        is I32.TruncSatF32S, is I32.TruncSatF32U,
        is I32.TruncSatF64S, is I32.TruncSatF64U,
        is I32.ReinterpretF32,
        is I32.Extend8S, is I32.Extend16S -> true

        // -- i64 arithmetic / logic / shift / rotate / compare -----------
        is I64.Add, is I64.Sub, is I64.Mul,
        is I64.DivS, is I64.DivU,
        is I64.RemS, is I64.RemU,
        is I64.And, is I64.Or, is I64.Xor,
        is I64.Shl, is I64.ShrS, is I64.ShrU,
        is I64.RotL, is I64.RotR,
        is I64.Clz, is I64.Ctz, is I64.PopCnt,
        is I64.Eqz, is I64.EQ, is I64.NE,
        is I64.LTS, is I64.LTU,
        is I64.GTS, is I64.GTU,
        is I64.LES, is I64.LEU,
        is I64.GES, is I64.GEU -> true

        // -- i64 convert / extend / reinterpret --------------------------
        is I64.Extend_I32_S, is I64.Extend_I32_U,
        is I64.TruncF32S, is I64.TruncF32U,
        is I64.TruncF64S, is I64.TruncF64U,
        is I64.TruncSatF32S, is I64.TruncSatF32U,
        is I64.TruncSatF64S, is I64.TruncSatF64U,
        is I64.ReinterpretF64,
        is I64.Extend8S, is I64.Extend16S, is I64.Extend32S -> true

        // -- f32 arithmetic / compare / convert / reinterpret ------------
        is F32.Add, is F32.Sub, is F32.Mul, is F32.Div,
        is F32.Abs, is F32.Neg, is F32.Sqrt,
        is F32.Ceil, is F32.Floor, is F32.Trunc, is F32.Nearest,
        is F32.Min, is F32.Max, is F32.CopySign,
        is F32.EQ, is F32.NE,
        is F32.LT, is F32.GT, is F32.LE, is F32.GE,
        is F32.ConvertI32S, is F32.ConvertI32U,
        is F32.ConvertI64S, is F32.ConvertI64U,
        is F32.DemoteF64, is F32.ReinterpretI32 -> true

        // -- f64 arithmetic / compare / convert / reinterpret ------------
        is F64.Add, is F64.Sub, is F64.Mul, is F64.Div,
        is F64.Abs, is F64.Neg, is F64.Sqrt,
        is F64.Ceil, is F64.Floor, is F64.Trunc, is F64.Nearest,
        is F64.Min, is F64.Max, is F64.Copysign,
        is F64.EQ, is F64.NE,
        is F64.LT, is F64.GT, is F64.LE, is F64.GE,
        is F64.ConvertI32S, is F64.ConvertI32U,
        is F64.ConvertI64S, is F64.ConvertI64U,
        is F64.PromoteF32, is F64.ReinterpretI64 -> true

        // Everything else (structural control flow, br_table,
        // call_indirect, SIMD, GC, atomic, bulk memory, EH, reference,
        // table) is read-only for now.
        else -> false
    }
}
