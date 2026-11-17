package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [WasmInstrOffsets.deduceOffsets].
 *
 * Ground-truth example from debugInfo/dwarf-fixtures/test-data/wasm32/dwarf-types.wasm,
 * compute function body bytes at module offsets 0x72..0x7d:
 *
 *   0x72  local.get 1     (recorded, 2 bytes: 0x20 0x01)
 *   0x74  local.get 0     (recorded, 2 bytes: 0x20 0x00)
 *   0x76  i32.load        (recorded, 3 bytes: 0x28 0x02 0x00)
 *   0x79  i32.add         (singleton, 1 byte: 0x6a) -- deduced
 *   0x7a  i32.const 10    (recorded, 2 bytes: 0x41 0x0a)
 *   0x7c  i32.add         (singleton, 1 byte: 0x6a) -- deduced
 *   0x7d  end             (END marker, 1 byte: 0x0b) -- deduced
 *
 * entryEnd = 0x7e (exclusive boundary, so closing end byte is at entryEnd-1 = 0x7d).
 */
class WasmInstrOffsetsTest {

    @Test
    fun deducedOffsetsMatchGroundTruth() {
        // Build the flat list mirroring the ground-truth example.
        // The function's closing Terminal end byte (0x7d) is consumed by parseOpCodes
        // as the stop signal and is NOT included in record.opCodes -- hence NOT in flat.
        // entryEnd = 0x7e, so next starts at 0x7d (= entryEnd - 1), the Terminal byte.
        // Recorded offsets (> 0) are carried by value-bearing opcodes.
        // Singletons have recordedOffset = -1 and byteLen = 1.
        val flat = listOf(
            FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = 0x72), // local.get 1
            FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = 0x74), // local.get 0
            FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = 0x76), // i32.load
            FlattenedInstr(isEndMarker = false, byteLen = 1,    recordedOffset = -1),   // i32.add (singleton)
            FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = 0x7a), // i32.const 10
            FlattenedInstr(isEndMarker = false, byteLen = 1,    recordedOffset = -1),   // i32.add (singleton)
        )
        // entryEnd = 0x7e (exclusive); Terminal end byte at 0x7d is the sentinel.
        val entryEnd = 0x7e

        val ok = WasmInstrOffsets.deduceOffsets(flat, entryEnd)
        assertTrue(ok, "deduceOffsets should succeed for well-formed input")

        assertEquals(0x72, flat[0].offset, "local.get 1 offset")
        assertEquals(0x74, flat[1].offset, "local.get 0 offset")
        assertEquals(0x76, flat[2].offset, "i32.load offset")
        assertEquals(0x79, flat[3].offset, "i32.add (first singleton) deduced offset")
        assertEquals(0x7a, flat[4].offset, "i32.const 10 offset")
        assertEquals(0x7c, flat[5].offset, "i32.add (second singleton) deduced offset")
    }

    @Test
    fun endMarkerDoesNotInheritBlockStartOffset() {
        // A block whose BLOCK_START has a recorded offset of 0x10 (the block's opening byte).
        // The END marker must not inherit 0x10 but must be deduced backward from what follows.
        // Sequence: block @0x10, nop (singleton @0x12, byteLen=1), end (END marker)
        // entryEnd = 0x15; closing function end at 0x14.
        // Backward pass:
        //   end (END):    next=0x14, deduced = 0x14 - 1 = 0x13
        //   nop (plain):  recordedOffset=-1, byteLen=1, next=0x13 -> deduced=0x12
        //   block (BLOCK_START): recordedOffset=0x10, recorded wins -> offset=0x10
        val flat = listOf(
            FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = 0x10), // block
            FlattenedInstr(isEndMarker = false, byteLen = 1,    recordedOffset = -1),   // nop (singleton)
            FlattenedInstr(isEndMarker = true,  byteLen = 1,    recordedOffset = -1),   // end of block
        )
        val entryEnd = 0x15

        val ok = WasmInstrOffsets.deduceOffsets(flat, entryEnd)
        assertTrue(ok, "deduceOffsets should succeed")

        assertEquals(0x10, flat[0].offset, "block start offset (recorded)")
        assertEquals(0x12, flat[1].offset, "nop deduced offset (0x13 - 1)")
        assertEquals(0x13, flat[2].offset, "end of block deduced offset (0x14 - 1)")
    }

    @Test
    fun nonIncreasingOffsetsCauseFailure() {
        // Inject a deliberately wrong byteLen (4 instead of 1) so the deduced offset
        // for the singleton lands at or before the preceding recorded offset, triggering
        // the non-increasing-offsets sanity check.
        //   0x10  local.get 0  (recorded)
        //   ?     singleton    (byteLen=4 -- wrong; deduced = 0x10 - 4 = 0x0c < 0x10 -- still passes strictly
        //                       increasing check because 0x0c < 0x10; need a different setup)
        // Better: two recorded offsets reversed: recorded[1] < recorded[0].
        val flat = listOf(
            FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = 0x20), // high
            FlattenedInstr(isEndMarker = false, byteLen = null, recordedOffset = 0x10), // lower -- non-increasing
        )
        val entryEnd = 0x25

        val ok = WasmInstrOffsets.deduceOffsets(flat, entryEnd)
        assertFalse(ok, "deduceOffsets must return false for non-increasing offsets")
    }
}
