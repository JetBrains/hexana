package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes
import org.jetbrains.hexana.wasm.sections.consumeCodeEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the byte-range containment contract of the code section.
 *
 * Spec: https://webassembly.github.io/spec/core/binary/modules.html#code-section
 *
 * ```
 * code ::= size:u32  func:func      (where size = ||func||)
 * func ::= (t*)*:vec(locals)  e:expr
 * ```
 *
 * The `size` field exists so decoder errors stay confined to one code entry.
 * Two mechanisms enforce that in our parser:
 *
 *  - `parseOpCodes(limit)` — optional absolute upper bound in buffer coordinates.
 *    Its `limit` propagates through `consumeOpCode` → `consumeBlock` → nested
 *    `parseOpCodes`, so a `block`/`loop`/`if`/`try`/`try_table` body cannot
 *    drift past the boundary even if its structural `0x0B` terminator is
 *    missing. The terminator still wins when it appears before `limit`.
 *
 *  - `consumeCodeEntry` pins `position()` to `codeEntryStart + fullSize` after
 *    a successful parse, where `fullSize = (bodyStart - codeEntryStart) + funSize`.
 *    Regardless of whether `parseOpCodes` under- or over-consumed inside the
 *    body, the *next* entry therefore starts at the spec-correct offset.
 *
 * These tests pin those two invariants with hand-crafted inline byte arrays
 * so any future refactor that weakens them gets flagged immediately.
 */
class CodeSectionBoundariesTest {

    // --- parseOpCodes(limit) ---

    /**
     * Four `nop` bytes, `limit = 2`. Loop must stop after two reads even though
     * there is no `end` byte and the buffer has more to give.
     */
    @Test
    fun `parseOpCodes respects explicit limit`() {
        val buf = byteArrayOf(0x01, 0x01, 0x01, 0x01).toCommonByteBuffer()
        val ops = buf.parseOpCodes(limit = 2)
        assertEquals(2, ops.size)
        assertTrue(ops.all { it === OpCodes.ParametricInstructions.Nop })
        assertEquals(2, buf.position(), "position must stop at the limit, not overshoot")
    }

    /**
     * `nop end nop nop` with `limit` at buffer end — the `0x0B` terminator
     * must still win over `limit`. Exactly one `nop` is returned and the
     * cursor lands after the consumed `end` byte.
     */
    @Test
    fun `parseOpCodes end byte terminates before limit`() {
        val buf = byteArrayOf(0x01, 0x0B, 0x01, 0x01).toCommonByteBuffer()
        val ops = buf.parseOpCodes(limit = 4)
        assertEquals(1, ops.size)
        assertSame(OpCodes.ParametricInstructions.Nop, ops.single())
        assertEquals(2, buf.position(), "end byte is consumed; cursor sits after it")
    }

    /**
     * `block bt=Empty nop nop` (no end for either). With `limit = 3` the nested
     * `consumeBlock → parseOpCodes` must see the propagated limit and include
     * exactly ONE `nop` in the block body, not two. This pin guards the
     * `limit` threading through `consumeOpCode` and `consumeBlock`.
     */
    @Test
    fun `parseOpCodes threads limit into nested block body`() {
        //   0x02 — block
        //   0x40 — blocktype Empty
        //   0x01 — nop (inside block)
        //   0x01 — nop (would also be inside block without the propagated limit)
        val buf = byteArrayOf(0x02, 0x40, 0x01, 0x01).toCommonByteBuffer()
        val ops = buf.parseOpCodes(limit = 3)

        val block = assertIs<OpCodes.Flow.Block>(ops.single())
        assertEquals(1, block.value.size, "nested block body must respect the outer limit")
        assertSame(OpCodes.ParametricInstructions.Nop, block.value.single())
    }

    // --- consumeCodeEntry: cursor pinning + size accounting ---

    /**
     * Two back-to-back well-formed entries. The parser must produce records
     * whose `intRange`s tile the buffer exactly (no gap, no overlap) and
     * whose `size` field matches the spec total (size-field ULEB128 + body).
     */
    @Test
    fun `consumeCodeEntry back-to-back entries tile correctly`() {
        // entry 1: funSize=4, locals=0, i32.const 10, end
        // entry 2: funSize=4, locals=0, i32.const 20, end
        val buf = byteArrayOf(
            0x04, 0x00, 0x41, 0x0A, 0x0B,
            0x04, 0x00, 0x41, 0x14, 0x0B,
        ).toCommonByteBuffer()

        val e1 = buf.consumeCodeEntry()
        val e2 = buf.consumeCodeEntry()

        assertEquals(0, e1.offset)
        assertEquals(5, e1.size, "entry 1: 1-byte size-field + 4-byte body")
        assertEquals(5, e2.offset, "entry 2 starts exactly where entry 1 ends")
        assertEquals(5, e2.size)
        assertEquals(10, buf.position(), "both entries fully consumed")

        assertEquals(10, assertIs<I32.Const>(e1.opCodes.single()).value)
        assertEquals(20, assertIs<I32.Const>(e2.opCodes.single()).value)

        // WasmRange.intRange contract: tiles without gap/overlap
        assertEquals(e1.intRange.last + 1, e2.intRange.first,
            "WasmCodeRecord.intRange must tile the code section")
    }

    /**
     * Entry body contains an early `0x0B` — `parseOpCodes` breaks before the
     * full `funSize` bytes are consumed. The cursor must still be pinned at
     * `codeEntryStart + fullSize` so the next entry reads from the spec-correct
     * offset regardless of the truncated body.
     */
    @Test
    fun `consumeCodeEntry pins cursor after early end in body`() {
        // entry 1: funSize=4, body = [locals=0, end, garbage, garbage]
        //          parseOpCodes terminates at byte 2 (the early end),
        //          but bodyEnd = 5 — cursor must jump to 5.
        // entry 2: funSize=2, body = [locals=0, end]
        val buf = byteArrayOf(
            0x04, 0x00, 0x0B, 0xAA.toByte(), 0xBB.toByte(),
            0x02, 0x00, 0x0B,
        ).toCommonByteBuffer()

        val e1 = buf.consumeCodeEntry()
        assertEquals(0, e1.offset)
        assertEquals(5, e1.size)
        assertEquals(5, buf.position(), "cursor pinned to bodyEnd despite early 0x0B")
        assertTrue(e1.opCodes.isEmpty())

        val e2 = buf.consumeCodeEntry()
        assertEquals(5, e2.offset, "entry 2 reads from the correct offset")
        assertEquals(3, e2.size)
    }

    /**
     * Entry body has no explicit `end` byte — only `funSize` delimits it.
     * `parseOpCodes` must stop at `bodyEnd` via the propagated limit and the
     * record's `size` must still cover the full spec-declared entry.
     */
    @Test
    fun `consumeCodeEntry body without explicit end stops at boundary`() {
        // funSize=3, body = [locals=0, i32.const 5]  — no 0x0B
        val buf = byteArrayOf(0x03, 0x00, 0x41, 0x05).toCommonByteBuffer()
        val rec = buf.consumeCodeEntry()

        assertEquals(0, rec.offset)
        assertEquals(4, rec.size, "size = 1-byte size-field + 3-byte body")
        assertEquals(4, buf.position(), "cursor at codeEntryStart + fullSize")
        assertEquals(5, assertIs<I32.Const>(rec.opCodes.single()).value)
    }

    /**
     * Entry 1's body under-declares its opcode immediate — the body has funSize=2
     * with `[locals=0, i32.const]` but the SLEB128 immediate is missing. When
     * `parseOpCodes` invokes `consumeSignedInt`, it reads the next byte (entry 2's
     * size-field) as part of the immediate. The resulting `I32.Const` value is
     * garbage (that's the parser's own fault, not the containment's), but the
     * crucial invariant holds: the cursor gets pinned back to bodyEnd, so entry 2
     * is still parsed from the spec-correct offset.
     */
    @Test
    fun `consumeCodeEntry contains damage from incomplete immediate`() {
        // entry 1: funSize=2, body = [locals=0, 0x41 (i32.const, no immediate in body)]
        // entry 2: funSize=2, body = [locals=0, end]
        val buf = byteArrayOf(
            0x02, 0x00, 0x41,
            0x02, 0x00, 0x0B,
        ).toCommonByteBuffer()

        val e1 = buf.consumeCodeEntry()
        val e2 = buf.consumeCodeEntry()

        assertEquals(0, e1.offset)
        assertEquals(3, e1.size, "entry 1 full size = size-field + declared body")

        // Entry 2 must still be well-formed despite entry 1's SLEB128 read
        // having transiently advanced past bodyEnd — the cursor pin rewinds.
        assertEquals(3, e2.offset, "entry 2 starts where entry 1's spec size ends")
        assertEquals(3, e2.size)
        assertEquals(6, buf.position())
        assertTrue(e2.opCodes.isEmpty(), "entry 2 body is just (locals=0, end)")
    }

    /**
     * Three entries in a row — the strongest structural invariant: the parser
     * can produce records whose start offsets form a running sum equal to the
     * cumulative size-field + body bytes, for any mix of valid entries.
     */
    @Test
    fun `consumeCodeEntry three entries maintain running tile invariant`() {
        // e1: size-field=0x02, body=[locals=0, end]                       → 3 bytes
        // e2: size-field=0x04, body=[locals=0, i32.const 7, end]          → 5 bytes
        // e3: size-field=0x02, body=[locals=0, end]                       → 3 bytes
        val buf = byteArrayOf(
            0x02, 0x00, 0x0B,
            0x04, 0x00, 0x41, 0x07, 0x0B,
            0x02, 0x00, 0x0B,
        ).toCommonByteBuffer()

        val e1 = buf.consumeCodeEntry()
        val e2 = buf.consumeCodeEntry()
        val e3 = buf.consumeCodeEntry()

        assertEquals(0, e1.offset); assertEquals(3, e1.size)
        assertEquals(3, e2.offset); assertEquals(5, e2.size)
        assertEquals(8, e3.offset); assertEquals(3, e3.size)
        assertEquals(11, buf.position())

        // Tiles form a partition of [0, 11)
        assertEquals(e1.intRange.last + 1, e2.intRange.first)
        assertEquals(e2.intRange.last + 1, e3.intRange.first)
        assertEquals(11, e3.intRange.last + 1)

        // Body content survives the containment
        assertEquals(7, assertIs<I32.Const>(e2.opCodes.single()).value)
        assertTrue(e1.opCodes.isEmpty())
        assertTrue(e3.opCodes.isEmpty())
    }
}
