package org.jetbrains.hexana.providers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Extents from untrusted Parquet/Arrow metadata must not wrap into "inside the head". */
class ExtentFitsTest {
    @Test
    fun anExtentWithinTheSizeFits() {
        assertTrue(extentFits(0, 10, 10))
        assertTrue(extentFits(4, 6, 10))
        assertTrue(extentFits(10, 0, 10))
    }

    @Test
    fun anExtentPastTheEndDoesNotFit() {
        assertFalse(extentFits(5, 6, 10))
        assertFalse(extentFits(11, 0, 10))
    }

    @Test
    fun anOverflowingExtentDoesNotFit() {
        // start + length wraps negative, which a naive `start + length <= size` would accept.
        assertTrue(Long.MAX_VALUE - 10 + 100 < 0)
        assertFalse(extentFits(Long.MAX_VALUE - 10, 100, 1024))
        assertFalse(extentFits(100, Long.MAX_VALUE, 1024))
    }

    @Test
    fun negativeStartsAndLengthsDoNotFit() {
        assertFalse(extentFits(-1, 4, 1024))
        assertFalse(extentFits(4, -1, 1024))
    }
}
