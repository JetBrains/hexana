package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Flow
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes
import org.jetbrains.hexana.wasm.sections.WasmParseException
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.consumeCodeEntry
import org.jetbrains.hexana.wasm.sections.consumeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression tests for parser robustness against malformed/adversarial input.
 *
 * The parser runs on untrusted, attacker-controlled `.wasm` bytes in the browser, so
 * every read/loop must make forward progress or fail cleanly instead of hanging or
 * exhausting memory. Each test below pins one such fix.
 */
class WasmParserRobustnessTest {

    /**
     * Wasm magic + version 1 (core) + one section with id 0x40 (unresolved) whose
     * length is a 5-byte overlong ULEB128 that decodes to a negative Int
     * (4294967290 truncated to Int == -6).
     *
     * Before the fix: sectionSize computes to 0, so `nextIndex` in
     * `WasmParser.parse`'s section-scan loop never advances past this section's own
     * start; the loop re-reads the same bytes forever, growing `sections` without
     * bound (H1). After the fix, a non-advancing section stops the scan promptly.
     */
    @Test
    fun `parse terminates on section with overlong negative-size LEB128`() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, // magic
            0x01, 0x00, 0x00, 0x00, // version 1, layer 0 (core)
            0x40, // section id (unresolved)
            0xfa.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f, // overlong ULEB128 -> negative Int size
        )
        val buffer = bytes.toCommonByteBuffer()

        // Must return promptly instead of looping forever re-scanning the same section.
        val result = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(result)
    }

    /**
     * A code entry whose body is just `[locals=0, else]` with no matching terminator
     * and nothing left in the buffer afterward. `else` (0x05) recurses to parse its
     * nested body, which normally ends by consuming the shared `end` (0x0B) that also
     * terminates the enclosing `if`; the `else` handler then rewinds one byte to give
     * that shared terminator back to the caller.
     *
     * Before the fix: when input is truncated right after `else` (no terminator to
     * find), the nested body parse returns immediately having consumed nothing, and
     * the unconditional rewind lands back on the very same `else` byte -- the loop
     * re-reads it and appends a new [Flow.Else] forever (H2). After the fix, the
     * rewind only happens when the nested body actually advanced, so parsing
     * terminates with a single, bounded opcode list.
     */
    @Test
    fun `consumeCodeEntry terminates on body ending in bare else with no terminator`() {
        val bytes = byteArrayOf(
            0x02, // funSize = 2
            0x00, // locals count = 0
            0x05, // else, with nothing left in the buffer
        )
        val buffer = bytes.toCommonByteBuffer()

        val record = buffer.consumeCodeEntry()

        assertEquals(1, record.opCodes.size, "opcode list must stay bounded, not grow forever")
        val elseOp = assertIs<Flow.Else>(record.opCodes.single())
        assertTrue(elseOp.value.isEmpty(), "truncated else has no nested body")
        assertEquals(3, buffer.position(), "cursor must land at end of buffer, not loop")
    }

    /** Same trigger as above, exercised directly at the [parseOpCodes] level. */
    @Test
    fun `parseOpCodes terminates on bare else with no terminator`() {
        val buffer = byteArrayOf(0x05).toCommonByteBuffer()

        val ops = buffer.parseOpCodes()

        assertEquals(1, ops.size, "opcode list must stay bounded, not grow forever")
        assertIs<Flow.Else>(ops.single())
    }

    /**
     * `consumeString`'s declared name length is attacker-controlled. A length of
     * `0x7FFFFFFF` (encoded as the 5-byte ULEB128 `FF FF FF FF 07`) with nothing left
     * in the buffer must fail fast instead of attempting a ~2 GiB `ByteArray`
     * allocation (H4).
     */
    @Test
    fun `consumeString rejects a length exceeding the remaining buffer`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07)
        val buffer = bytes.toCommonByteBuffer()

        assertFailsWith<WasmParseException> { buffer.consumeString() }
    }

    /** A string whose declared length exactly matches the remaining bytes must still parse. */
    @Test
    fun `consumeString accepts a length exactly filling the remaining buffer`() {
        val bytes = byteArrayOf(0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())
        val buffer = bytes.toCommonByteBuffer()

        assertEquals("abc", buffer.consumeString())
    }

    /**
     * `consumeArray`'s declared vector count is attacker-controlled. A count of
     * `0x7FFFFFFF` with nothing left in the buffer must fail fast instead of driving
     * billions of `repeat` iterations (H4).
     */
    @Test
    fun `consumeArray rejects a count exceeding the remaining buffer`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07)
        val buffer = bytes.toCommonByteBuffer()

        assertFailsWith<WasmParseException> { buffer.consumeArray { } }
    }

    /** A vector of N one-byte elements where N equals the remaining bytes must still parse. */
    @Test
    fun `consumeArray accepts a count exactly filling the remaining buffer`() {
        val bytes = byteArrayOf(0x03, 0x01, 0x01, 0x01)
        val buffer = bytes.toCommonByteBuffer()

        var visited = 0
        buffer.consumeArray {
            buffer.get()
            visited++
        }

        assertEquals(3, visited)
    }
}
