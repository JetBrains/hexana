package org.jetbrains.hexana.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableTabBridgeTest {
    @Test
    fun comparesLargeIntegralValuesWithoutDoublePrecisionLoss() {
        assertTrue(compareTableValues(Long.MAX_VALUE, Long.MAX_VALUE - 1) > 0)
        assertTrue(compareTableValues(Long.MIN_VALUE, Long.MIN_VALUE + 1) < 0)
        assertEquals(0, compareTableValues(42L, 42))
    }
}
