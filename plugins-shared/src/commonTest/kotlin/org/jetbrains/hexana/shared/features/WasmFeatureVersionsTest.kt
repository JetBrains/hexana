package org.jetbrains.hexana.shared.features

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Version strings are compared against the shapes that actually occur in `features.json`:
 * bare browser numbers, two-segment and three-segment runtime versions.
 */
class WasmFeatureVersionsTest {

    @Test
    fun `single-segment browser versions compare numerically, not lexically`() {
        // The lexical trap: "9" > "133" as strings.
        assertTrue(compareRuntimeVersions("133", "9") > 0)
        assertTrue(compareRuntimeVersions("85", "119") < 0)
        assertEquals(0, compareRuntimeVersions("137", "137"))
    }

    @Test
    fun `multi-segment versions compare segment by segment`() {
        assertTrue(compareRuntimeVersions("1.0.24", "1.0.30") < 0)
        assertTrue(compareRuntimeVersions("26.0", "9.1") > 0)
        assertTrue(compareRuntimeVersions("0.10.1", "0.9.1") > 0)
        assertTrue(compareRuntimeVersions("16.4", "16.2") > 0)
    }

    @Test
    fun `missing trailing segments read as zero`() {
        assertEquals(0, compareRuntimeVersions("1.0", "1.0.0"))
        assertEquals(0, compareRuntimeVersions("2", "2.0.0"))
        assertTrue(compareRuntimeVersions("1.0.1", "1.0") > 0)
    }

    @Test
    fun `blank sorts below everything`() {
        assertTrue(compareRuntimeVersions("", "0.1") < 0)
        assertTrue(compareRuntimeVersions("0.1", "") > 0)
        assertEquals(0, compareRuntimeVersions("", ""))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(0, compareRuntimeVersions(" 120 ", "120"))
    }

    @Test
    fun `non-numeric segments fall back to string comparison`() {
        // Not a shape upstream uses today; pinned so a hand-edited runtimes.json cannot crash.
        assertTrue(compareRuntimeVersions("1.0-rc1", "1.0-rc2") < 0)
        assertEquals(0, compareRuntimeVersions("nightly", "nightly"))
    }

    @Test
    fun `atLeast is inclusive of the landing version`() {
        assertTrue(runtimeVersionAtLeast("119", "119"))
        assertTrue(runtimeVersionAtLeast("137", "119"))
        assertFalse(runtimeVersionAtLeast("100", "119"))
    }
}
