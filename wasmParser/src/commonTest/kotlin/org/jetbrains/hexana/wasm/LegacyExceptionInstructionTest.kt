package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.legacy.eh.LegacyEH
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes
import org.jetbrains.hexana.wasm.sections.StandardTypesParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the WebAssembly legacy Exception Handling proposal (pre-`try_table`).
 *
 * Spec (local):    `doc/exception-handling/legacy/Exceptions.md`
 * Spec (upstream): https://github.com/WebAssembly/exception-handling/blob/main/proposals/exception-handling/legacy/Exceptions.md
 *
 * ### Legacy opcodes (primary opcode space)
 *
 * | Byte | Mnemonic   | Immediates                    | Notes                                              |
 * |------|------------|-------------------------------|----------------------------------------------------|
 * | 0x06 | `try`      | `bt: blocktype`               | Opens try block (same blocktype encoding as `block`). |
 * | 0x07 | `catch`    | `x: tagidx` (ULEB128)         | Starts catch arm. Pushes tag's param values.        |
 * | 0x08 | `throw`    | `x: tagidx` (ULEB128)         | Shared with finalized EH — same opcode.             |
 * | 0x09 | `rethrow`  | `l: labelidx` (ULEB128)       | Re-raises exception caught by enclosing catch.      |
 * | 0x18 | `delegate` | `l: labelidx` (ULEB128)       | Terminates try body (**replaces** 0x0B end).        |
 * | 0x19 | `catch_all`| —                             | At most one per try; after all `catch` arms.        |
 * | 0x0B | `end`      | —                             | Terminates try/catch/catch_all.                     |
 *
 * ### Block structure
 *
 * ```text
 * try-instr ::= 0x06 bt (instr)* catch-arm* catch-all-arm? 0x0B
 *             | 0x06 bt (instr)* 0x18 l
 *
 * catch-arm     ::= 0x07 x (instr)*
 * catch-all-arm ::= 0x19 (instr)*
 * ```
 *
 * ### Parser shape (see [LegacyEH])
 *
 * `LegacyEH.Try` extends `OpCodes.Flow.Block` and its `value` is a **flat** list
 * produced by `consumeBlock()`/`parseOpCodes()`. `parseOpCodes()` terminates only
 * on `0x0B`, so `catch` / `catch_all` / `rethrow` / `delegate` appear inline in
 * `Try.value` rather than as separate structural fields. A reconstruction of
 * `catch-arm` / `catch-all-arm` bodies therefore requires a secondary pass over
 * the flat list (splitting on `LegacyEH.Catch` / `LegacyEH.CatchAll`).
 *
 * A `try` body terminated by `delegate l` has no trailing `0x0B`, so in that
 * shape the terminating `Delegate` is the last element of `Try.value` and the
 * outer `parseOpCodes()` loop exits via buffer exhaustion (in these isolated
 * tests) or continues reading the next outer instruction (in a real code body).
 *
 * All offsets are start-of-instruction byte indices (`position() - 1`).
 */
class LegacyExceptionInstructionTest {

    // --- 0x06 try ---

    /**
     * `try bt=Empty end` — bytes `06 40 0B`.
     */
    @Test
    fun `legacy try Empty end`() {
        val ops = byteArrayOf(0x06, 0x40, 0x0B).toCommonByteBuffer().parseOpCodes()
        val tryOp = ops.single()
        assertIs<LegacyEH.Try>(tryOp)
        assertSame(StandardTypesParser.WasmBlockType.Empty, tryOp.type)
        assertEquals(emptyList(), tryOp.value)
        assertEquals(0, tryOp.offset)
    }

    /**
     * `try bt=Empty nop end` — bytes `06 40 01 0B`.
     */
    @Test
    fun `legacy try with nop body`() {
        val ops = byteArrayOf(0x06, 0x40, 0x01, 0x0B).toCommonByteBuffer().parseOpCodes()
        val tryOp = ops.single()
        assertIs<LegacyEH.Try>(tryOp)
        assertSame(StandardTypesParser.WasmBlockType.Empty, tryOp.type)
        assertEquals(1, tryOp.value.size)
        assertSame(OpCodes.ParametricInstructions.Nop, tryOp.value.single())
    }

    // --- 0x07 catch ---

    /**
     * `try bt=Empty catch 0 end` — bytes `06 40 07 00 0B`.
     */
    @Test
    fun `legacy try catch 0 end`() {
        val ops = byteArrayOf(0x06, 0x40, 0x07, 0x00, 0x0B).toCommonByteBuffer().parseOpCodes()
        val tryOp = ops.single()
        assertIs<LegacyEH.Try>(tryOp)
        val catch = tryOp.value.single()
        assertIs<LegacyEH.Catch>(catch)
        assertEquals(0, catch.value, "catch tagidx")
        assertEquals(2, catch.offset, "catch opcode byte is at buffer offset 2")
    }

    /**
     * `try bt=Empty catch 128 end` — bytes `06 40 07 80 01 0B`.
     *
     * `tagidx` is decoded as SLEB128 (matches ULEB128 for non-negative values
     * whose top value-bit is 0). 128 is `0x80 0x01` in LEB128.
     */
    @Test
    fun `legacy catch with multi-byte LEB128 tag index`() {
        val ops = byteArrayOf(0x06, 0x40, 0x07, 0x80.toByte(), 0x01, 0x0B)
            .toCommonByteBuffer().parseOpCodes()
        val tryOp = assertIs<LegacyEH.Try>(ops.single())
        val catch = assertIs<LegacyEH.Catch>(tryOp.value.single())
        assertEquals(128, catch.value)
    }

    // --- 0x19 catch_all ---

    /**
     * `try bt=Empty catch_all end` — bytes `06 40 19 0B`.
     */
    @Test
    fun `legacy try catch_all end`() {
        val ops = byteArrayOf(0x06, 0x40, 0x19, 0x0B).toCommonByteBuffer().parseOpCodes()
        val tryOp = assertIs<LegacyEH.Try>(ops.single())
        val catchAll = assertIs<LegacyEH.CatchAll>(tryOp.value.single())
        assertEquals(2, catchAll.offset)
    }

    /**
     * `try bt=Empty catch 0 catch 1 catch_all end` — bytes `06 40 07 00 07 01 19 0B`.
     *
     * The parser flattens catch arms and catch_all into `Try.value`, in order.
     * Reconstructing arm bodies is the caller's responsibility.
     */
    @Test
    fun `legacy try multi-catch plus catch_all`() {
        val ops = byteArrayOf(0x06, 0x40, 0x07, 0x00, 0x07, 0x01, 0x19, 0x0B)
            .toCommonByteBuffer().parseOpCodes()
        val tryOp = assertIs<LegacyEH.Try>(ops.single())
        assertEquals(3, tryOp.value.size)

        val c0 = assertIs<LegacyEH.Catch>(tryOp.value[0])
        assertEquals(0, c0.value)
        assertEquals(2, c0.offset)

        val c1 = assertIs<LegacyEH.Catch>(tryOp.value[1])
        assertEquals(1, c1.value)
        assertEquals(4, c1.offset)

        val all = assertIs<LegacyEH.CatchAll>(tryOp.value[2])
        assertEquals(6, all.offset)
    }

    // --- 0x08 throw (shared with finalized EH) ---

    /**
     * `throw 0` — bytes `08 00 0B`.
     *
     * `throw` is shared between legacy and finalized EH (same opcode `0x08`).
     * It decodes as `OpCodes.Flow.Trow`, independent of whether it appears
     * inside a legacy `try` or a finalized `try_table`.
     */
    @Test
    fun `throw tag 0 — decoded as Flow Trow`() {
        val ops = byteArrayOf(0x08, 0x00, 0x0B).toCommonByteBuffer().parseOpCodes()
        val throwOp = ops.filterIsInstance<OpCodes.Flow.Trow>().single()
        assertEquals(0u, throwOp.value)
    }

    // --- 0x09 rethrow ---

    /**
     * `try bt=Empty catch 0 rethrow 0 end` — bytes `06 40 07 00 09 00 0B`.
     *
     * `rethrow l` counts labels outward to the enclosing `catch`/`catch_all`
     * arm, NOT the `try`. `l = 0` re-raises the exception just caught.
     */
    @Test
    fun `legacy try catch rethrow`() {
        val ops = byteArrayOf(0x06, 0x40, 0x07, 0x00, 0x09, 0x00, 0x0B)
            .toCommonByteBuffer().parseOpCodes()
        val tryOp = assertIs<LegacyEH.Try>(ops.single())
        assertEquals(2, tryOp.value.size)

        val catch = assertIs<LegacyEH.Catch>(tryOp.value[0])
        assertEquals(0, catch.value)
        assertEquals(2, catch.offset)

        val rethrow = assertIs<LegacyEH.Rethrow>(tryOp.value[1])
        assertEquals(0u, rethrow.value)
        assertEquals(4, rethrow.offset)
    }

    // --- 0x18 delegate (terminator form) ---

    /**
     * `try bt=Empty delegate 0` — bytes `06 40 18 00`.
     *
     * Note: `delegate` terminates the `try` body without a trailing `0x0B`.
     * `parseOpCodes()` does not special-case `delegate` as a terminator — the
     * `Delegate` opcode is appended to `Try.value` and the inner loop exits
     * via buffer exhaustion (here) or continues reading the next outer
     * instruction (in a real code body). A `try` terminated by `delegate`
     * may not have `catch`/`catch_all` arms.
     */
    @Test
    fun `legacy try delegate as terminator`() {
        val ops = byteArrayOf(0x06, 0x40, 0x18, 0x00).toCommonByteBuffer().parseOpCodes()
        val tryOp = assertIs<LegacyEH.Try>(ops.single())
        val delegate = assertIs<LegacyEH.Delegate>(tryOp.value.single())
        assertEquals(0u, delegate.value, "delegate label depth")
        assertEquals(2, delegate.offset)
        assertTrue(
            tryOp.value.none { it is LegacyEH.Catch || it is LegacyEH.CatchAll },
            "a try terminated by delegate must have no catch/catch_all arms",
        )
    }

    // --- nested try with outer catch ---

    /**
     * Outer `try bt=Empty (try bt=Empty end) catch 0 end`.
     *
     * Bytes: `06 40 06 40 0B 07 00 0B`
     * - outer try at offset 0
     * - inner try at offset 2 (empty body)
     * - outer catch at offset 5
     */
    @Test
    fun `legacy nested try with outer catch`() {
        val ops = byteArrayOf(0x06, 0x40, 0x06, 0x40, 0x0B, 0x07, 0x00, 0x0B)
            .toCommonByteBuffer().parseOpCodes()
        val outer = assertIs<LegacyEH.Try>(ops.single())
        assertEquals(0, outer.offset)
        assertEquals(2, outer.value.size)

        val inner = assertIs<LegacyEH.Try>(outer.value[0])
        assertEquals(2, inner.offset)
        assertSame(StandardTypesParser.WasmBlockType.Empty, inner.type)
        assertTrue(inner.value.isEmpty(), "inner try body is empty")

        val outerCatch = assertIs<LegacyEH.Catch>(outer.value[1])
        assertEquals(0, outerCatch.value)
        assertEquals(5, outerCatch.offset)
    }

    // --- offset preservation ---

    /**
     * Offsets are captured at the start-of-instruction byte (`position() - 1`).
     *
     * Bytes: `06 40 07 00 19 0B`
     * - offset 0 — try
     * - offset 2 — catch 0
     * - offset 4 — catch_all
     */
    @Test
    fun `legacy try byte offsets preserved — start-of-instruction`() {
        val ops = byteArrayOf(0x06, 0x40, 0x07, 0x00, 0x19, 0x0B)
            .toCommonByteBuffer().parseOpCodes()
        val tryOp = assertIs<LegacyEH.Try>(ops.single())
        assertEquals(0, tryOp.offset)
        assertEquals(2, assertIs<LegacyEH.Catch>(tryOp.value[0]).offset)
        assertEquals(4, assertIs<LegacyEH.CatchAll>(tryOp.value[1]).offset)
    }
}
