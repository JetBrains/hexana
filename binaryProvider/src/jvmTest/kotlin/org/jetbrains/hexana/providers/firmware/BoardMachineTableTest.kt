package org.jetbrains.hexana.providers.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BoardMachineTableTest {

    // ---------------------------------------------------------------------------
    // Every table row — exact values from fact-sheet-confirmed table
    // ---------------------------------------------------------------------------

    @Test
    fun qemuCortexM3() {
        val entry = lookupBoard("qemu_cortex_m3")
        assertNotNull(entry)
        assertEquals(QemuBinary.SystemArm, entry.binary)
        assertEquals("lm3s6965evb", entry.machine)
        assertEquals(listOf("-cpu", "cortex-m3"), entry.extraArgs)
    }

    @Test
    fun mps2An385() {
        val entry = lookupBoard("mps2_an385")
        assertNotNull(entry)
        assertEquals(QemuBinary.SystemArm, entry.binary)
        assertEquals("mps2-an385", entry.machine)
        assertEquals(listOf("-cpu", "cortex-m3"), entry.extraArgs)
    }

    @Test
    fun mps2An521() {
        val entry = lookupBoard("mps2_an521")
        assertNotNull(entry)
        assertEquals(QemuBinary.SystemArm, entry.binary)
        assertEquals("mps2-an521", entry.machine)
        assertEquals(listOf("-cpu", "cortex-m33"), entry.extraArgs)
    }

    @Test
    fun qemuCortexA53() {
        val entry = lookupBoard("qemu_cortex_a53")
        assertNotNull(entry)
        assertEquals(QemuBinary.SystemAarch64, entry.binary)
        assertEquals("virt,secure=on,gic-version=3", entry.machine)
        assertEquals(listOf("-cpu", "cortex-a53"), entry.extraArgs)
    }

    @Test
    fun qemuRiscV32() {
        val entry = lookupBoard("qemu_riscv32")
        assertNotNull(entry)
        assertEquals(QemuBinary.SystemRiscv32, entry.binary)
        assertEquals("virt", entry.machine)
        assertEquals(listOf("-bios", "none", "-m", "256"), entry.extraArgs)
    }

    @Test
    fun qemuRiscV64() {
        val entry = lookupBoard("qemu_riscv64")
        assertNotNull(entry)
        assertEquals(QemuBinary.SystemRiscv64, entry.binary)
        assertEquals("virt", entry.machine)
        assertEquals(listOf("-bios", "none", "-m", "256"), entry.extraArgs)
    }

    // ---------------------------------------------------------------------------
    // Qualifier normalization
    // ---------------------------------------------------------------------------

    @Test
    fun slashQualifierFirstSegment() {
        // "qemu_cortex_m3/ti_lm3s6965" → first segment = "qemu_cortex_m3"
        val entry = lookupBoard("qemu_cortex_m3/ti_lm3s6965")
        assertNotNull(entry, "Expected lookup to normalize slash qualifier to first segment")
        assertEquals("lm3s6965evb", entry.machine)
    }

    @Test
    fun slashQualifierJoined() {
        // "mps2/an521/cpu0" → underscore-joined = "mps2_an521_cpu0" (no hit)
        //                    → first segment "mps2" (no hit)
        //                    → try "mps2_an521" via joined without trailing /cpu0 — check spec:
        // The normalization tries: exact → first segment → all-joined.
        // "mps2/an521" → "mps2_an521" (all-joined).
        val entry = lookupBoard("mps2/an521")
        assertNotNull(entry, "Expected mps2/an521 to normalize to mps2_an521")
        assertEquals("mps2-an521", entry.machine)
    }

    @Test
    fun slashQualifierThreeSegments() {
        // Prefixes are tried longest-first: "mps2_an521_cpu0" (no hit) → "mps2_an521" (hit).
        // This is the Zephyr v4 CONFIG_BOARD_TARGET form for the AN521 CPU0 target.
        val entry = lookupBoard("mps2/an521/cpu0")
        assertNotNull(entry, "Expected mps2/an521/cpu0 to resolve via its mps2_an521 prefix")
        assertEquals("mps2-an521", entry.machine)
    }

    @Test
    fun unknownBoard() {
        assertNull(lookupBoard("unknown_board"))
        assertNull(lookupBoard(""))
    }
}
