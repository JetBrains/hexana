package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WasmDispatchSurfaceDiffTest {

    /**
     * Baseline: functions A/B/C (all `(i32) -> i32`) with table [A, B]; X
     * (exported "x") dispatches via `call_indirect (type 0)`.
     *
     * ```
     *   00 61 73 6D 01 00 00 00
     *   01 06 01 60 01 7F 01 7F          ; type 0 = (i32) -> i32
     *   03 05 04 00 00 00 00             ; A=0 B=1 C=2 X=3, all type 0
     *   04 04 01 70 00 04                ; table funcref min=4
     *   07 05 01 01 78 00 03             ; export "x" -> X
     *   09 08 01 00 41 00 0B 02 00 01    ; elem active [A, B]
     *   0A 1D 04                         ; code
     *     04 00 20 00 0B                 ; A: local.get 0
     *     04 00 41 07 0B                 ; B: i32.const 7
     *     07 00 20 00 41 02 6C 0B        ; C: local.get 0; i32.const 2; i32.mul
     *     09 00 20 00 41 00 11 00 00 0B  ; X: call_indirect (type 0)
     * ```
     */
    private val baseline: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x06, 0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
        0x03, 0x05, 0x04, 0x00, 0x00, 0x00, 0x00,
        0x04, 0x04, 0x01, 0x70, 0x00, 0x04,
        0x07, 0x05, 0x01, 0x01, 0x78, 0x00, 0x03,
        0x09, 0x08, 0x01, 0x00, 0x41, 0x00, 0x0B, 0x02, 0x00, 0x01,
        0x0A, 0x1D, 0x04,
        0x04, 0x00, 0x20, 0x00, 0x0B,
        0x04, 0x00, 0x41, 0x07, 0x0B,
        0x07, 0x00, 0x20, 0x00, 0x41, 0x02, 0x6C, 0x0B,
        0x09, 0x00, 0x20, 0x00, 0x41, 0x00, 0x11, 0x00, 0x00, 0x0B,
    )

    /**
     * Current: B is gone entirely; C (same body as baseline C, so the matcher
     * pairs it) is NOW in the table; D is a brand-new function in the table.
     * D's body (`local.get 0; local.get 0; i32.mul; local.get 0; i32.add`) is
     * deliberately structurally dissimilar to B's const-return, otherwise the
     * fuzzy matcher pairs B with D as a MODIFIED function that stayed in the
     * table — a legitimate reading, but not the scenario under test.
     * Table [A, C, D]. A and X keep their baseline bodies.
     * Function indices: A=0 C=1 D=2 X=3.
     */
    private val current: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x06, 0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
        0x03, 0x05, 0x04, 0x00, 0x00, 0x00, 0x00,
        0x04, 0x04, 0x01, 0x70, 0x00, 0x04,
        0x07, 0x05, 0x01, 0x01, 0x78, 0x00, 0x03,
        0x09, 0x09, 0x01, 0x00, 0x41, 0x00, 0x0B, 0x03, 0x00, 0x01, 0x02,
        0x0A, 0x23, 0x04,
        0x04, 0x00, 0x20, 0x00, 0x0B,
        0x07, 0x00, 0x20, 0x00, 0x41, 0x02, 0x6C, 0x0B,
        0x0A, 0x00, 0x20, 0x00, 0x20, 0x00, 0x6C, 0x20, 0x00, 0x6A, 0x0B,
        0x09, 0x00, 0x20, 0x00, 0x41, 0x00, 0x11, 0x00, 0x00, 0x0B,
    )

    private fun diff(): WasmDispatchSurfaceDiffResult {
        val result = WasmDispatchSurfaceDiffOrchestrator.diff(
            WasmBinaryProvider(baseline.toCommonByteBuffer()),
            WasmBinaryProvider(current.toCommonByteBuffer()),
        )
        assertNotNull(result)
        return result
    }

    @Test
    fun `entered and left candidates split by function identity`() {
        val delta = diff().deltas.single()
        assertEquals("(i32) -> i32", delta.signatureText)
        assertEquals(2, delta.baselineCount)
        assertEquals(3, delta.currentCount)

        // Entered: C (existing function newly added to the table — matched to
        // baseline code idx 2) and D (a brand-new function).
        assertEquals(2, delta.entered.size)
        val enteredExisting = delta.entered.single { !it.functionAddedOrRemoved }
        assertEquals(2, enteredExisting.leftCodeIdx, "C matched to baseline C")
        assertEquals(1, enteredExisting.rightCodeIdx)
        val enteredNew = delta.entered.single { it.functionAddedOrRemoved }
        assertEquals(2, enteredNew.rightCodeIdx, "D is current code idx 2")
        assertEquals(null, enteredNew.leftCodeIdx)

        // Left: B, whose function was removed outright.
        val left = delta.left.single()
        assertTrue(left.functionAddedOrRemoved)
        assertEquals(1, left.leftCodeIdx)
        assertEquals(null, left.rightCodeIdx)
    }

    @Test
    fun `stable candidates and callers do not report churn`() {
        val delta = diff().deltas.single()
        // A stayed in the table on both sides — it must appear in neither list.
        assertFalse(delta.entered.any { it.rightCodeIdx == 0 }, "A did not enter")
        assertFalse(delta.left.any { it.leftCodeIdx == 0 }, "A did not leave")
        // X dispatches through the signature on both sides.
        assertEquals(1, delta.baselineCallers)
        assertEquals(1, delta.currentCallers)
    }

    @Test
    fun `identical modules report no deltas`() {
        val result = WasmDispatchSurfaceDiffOrchestrator.diff(
            WasmBinaryProvider(baseline.toCommonByteBuffer()),
            WasmBinaryProvider(baseline.toCommonByteBuffer()),
        )
        assertNotNull(result)
        assertTrue(result.deltas.isEmpty(), "no churn between identical builds")
        assertEquals(1, result.unchangedSignatures)
    }
}
