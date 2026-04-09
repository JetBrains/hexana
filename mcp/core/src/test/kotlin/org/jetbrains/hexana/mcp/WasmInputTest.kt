package org.jetbrains.hexana.mcp

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WasmInputTest {
    @Test
    fun rawBytesUsesContentBasedEqualityAndHashCode() {
        val left = WasmInput.RawBytes(byteArrayOf(1, 2, 3))
        val right = WasmInput.RawBytes(byteArrayOf(1, 2, 3))
        val different = WasmInput.RawBytes(byteArrayOf(1, 2, 4))

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
        assertNotEquals(left, different)
    }

    @Test
    fun rawBytesDefensivelyCopiesInputAndOutputArrays() {
        val source = byteArrayOf(1, 2, 3)
        val rawBytes = WasmInput.RawBytes(source)

        source[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), rawBytes.bytes)

        val exposedBytes = rawBytes.bytes
        exposedBytes[1] = 8
        assertContentEquals(byteArrayOf(1, 2, 3), rawBytes.bytes)
    }
}
