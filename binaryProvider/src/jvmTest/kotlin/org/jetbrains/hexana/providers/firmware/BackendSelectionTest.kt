package org.jetbrains.hexana.providers.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private fun cortexMProfile(boardId: String? = null) = TargetProfile(
    archClass = ArchClass.CortexM,
    boardId = boardId,
    socCompatibles = emptyList(),
    elfIs64Bit = false,
    elfMachine = 40, // EM_ARM
    confidence = if (boardId != null) Confidence.Exact else Confidence.ElfOnly,
)

private fun cortexAProfile(is64Bit: Boolean = true, boardId: String? = null) = TargetProfile(
    archClass = ArchClass.CortexA,
    boardId = boardId,
    socCompatibles = emptyList(),
    elfIs64Bit = is64Bit,
    elfMachine = if (is64Bit) 183 else 40,
    confidence = if (boardId != null) Confidence.Exact else Confidence.ElfOnly,
)

private fun riscVProfile(is64Bit: Boolean = false, boardId: String? = null) = TargetProfile(
    archClass = ArchClass.RiscV,
    boardId = boardId,
    socCompatibles = emptyList(),
    elfIs64Bit = is64Bit,
    elfMachine = 243, // EM_RISCV
    confidence = if (boardId != null) Confidence.Exact else Confidence.ElfOnly,
)

private fun otherProfile() = TargetProfile(
    archClass = ArchClass.Other,
    boardId = null,
    socCompatibles = emptyList(),
    elfIs64Bit = false,
    elfMachine = 3, // EM_386
    confidence = Confidence.ElfOnly,
)

class BackendSelectionTest {

    // ---------------------------------------------------------------------------
    // Rule 1: known QEMU board
    // ---------------------------------------------------------------------------

    @Test
    fun rule1KnownQemuBoard() {
        val profile = cortexMProfile(boardId = "qemu_cortex_m3")
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(Backend.Qemu, result.backend)
        assertEquals(Backend.Qemu, result.recommended)
        assertEquals("lm3s6965evb", result.qemuMachine)
        assertEquals(QemuBinary.SystemArm, result.qemuBinary)
        assertEquals(listOf("-cpu", "cortex-m3"), result.qemuExtraArgs)
        assertNull(result.replSource)
    }

    @Test
    fun rule1Mps2An521() {
        val profile = cortexMProfile(boardId = "mps2_an521")
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals("mps2-an521", result.qemuMachine)
        assertEquals(listOf("-cpu", "cortex-m33"), result.qemuExtraArgs)
    }

    @Test
    fun rule1QemuRiscV32() {
        val profile = riscVProfile(is64Bit = false, boardId = "qemu_riscv32")
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(Backend.Qemu, result.backend)
        assertEquals(QemuBinary.SystemRiscv32, result.qemuBinary)
        assertEquals("virt", result.qemuMachine)
        assertEquals(listOf("-bios", "none", "-m", "256"), result.qemuExtraArgs)
    }

    @Test
    fun rule1QemuCortexA53() {
        val profile = cortexAProfile(is64Bit = true, boardId = "qemu_cortex_a53")
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(Backend.Qemu, result.backend)
        assertEquals(QemuBinary.SystemAarch64, result.qemuBinary)
        assertEquals("virt,secure=on,gic-version=3", result.qemuMachine)
        assertEquals(listOf("-cpu", "cortex-a53"), result.qemuExtraArgs)
    }

    // ---------------------------------------------------------------------------
    // Rule 2: CortexA without board table hit
    // ---------------------------------------------------------------------------

    @Test
    fun rule2CortexA64bitVirt() {
        val profile = cortexAProfile(is64Bit = true)
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(Backend.Qemu, result.backend)
        assertEquals(Backend.Qemu, result.recommended)
        assertEquals(QemuBinary.SystemAarch64, result.qemuBinary)
        assertEquals("virt", result.qemuMachine)
    }

    @Test
    fun rule2CortexA32bitVirt() {
        val profile = cortexAProfile(is64Bit = false)
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(QemuBinary.SystemArm, result.qemuBinary)
        assertEquals("virt", result.qemuMachine)
    }

    // ---------------------------------------------------------------------------
    // Rule 3: CortexM/RiscV → Renode
    // ---------------------------------------------------------------------------

    @Test
    fun rule3CortexMNoBoard() {
        val profile = cortexMProfile()
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(Backend.Renode, result.backend)
        assertEquals(Backend.Renode, result.recommended)
        assertNull(result.qemuBinary)
        assertNull(result.qemuMachine)
        assertEquals(ReplSource.Generated, result.replSource)
    }

    @Test
    fun rule3Nrf52840dkLibrary() {
        val profile = cortexMProfile(boardId = "nrf52840dk_nrf52840")
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(Backend.Renode, result.backend)
        assertEquals(ReplSource.Library, result.replSource)
    }

    @Test
    fun rule3Stm32f4Library() {
        val profile = cortexMProfile(boardId = "stm32f4_discovery")
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(ReplSource.Library, result.replSource)
    }

    @Test
    fun rule3NucleoF429ziIsGenerated() {
        // nucleo_f429zi was removed from RENODE_LIBRARY_BOARDS (no confirmed .repl).
        val profile = cortexMProfile(boardId = "nucleo_f429zi")
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(Backend.Renode, result.backend)
        assertEquals(ReplSource.Generated, result.replSource)
    }

    @Test
    fun rule3RiscVNoBoard() {
        val profile = riscVProfile()
        val result = selectBackend(profile)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(Backend.Renode, result.backend)
        assertEquals(ReplSource.Generated, result.replSource)
    }

    // ---------------------------------------------------------------------------
    // Rule 4: Other arch → Fail
    // ---------------------------------------------------------------------------

    @Test
    fun rule4OtherArchFails() {
        val profile = otherProfile()
        val result = selectBackend(profile)
        assertIs<BackendSelection.Fail>(result)
        assertEquals(
            "Cannot run this firmware: ELF machine 3 maps to no supported emulator (arch class Other).",
            result.reason,
        )
        assertEquals(
            "No QEMU machine and no Renode platform are available for this target.",
            result.alternative,
        )
    }

    // ---------------------------------------------------------------------------
    // Forced backend — failure copy (exact strings per design)
    // ---------------------------------------------------------------------------

    @Test
    fun forcedRenodeOnCortexAFails() {
        val profile = cortexAProfile(is64Bit = true)
        val result = selectBackend(profile, forced = Backend.Renode)
        assertIs<BackendSelection.Fail>(result)
        assertEquals(
            "Cannot run CortexA with Renode: this Cortex-A / application-class target is QEMU-only.",
            result.reason,
        )
        assertEquals(
            "Switch the Emulator selector to QEMU (recommended) or Recommended.",
            result.alternative,
        )
    }

    @Test
    fun forcedRenodeOnNamedCortexABoardFails() {
        val profile = cortexAProfile(is64Bit = true, boardId = "qemu_cortex_a53")
        val result = selectBackend(profile, forced = Backend.Renode)
        assertIs<BackendSelection.Fail>(result)
        assertEquals(
            "Cannot run qemu_cortex_a53 with Renode: this Cortex-A / application-class target is QEMU-only.",
            result.reason,
        )
    }

    @Test
    fun forcedQemuOnMcuWithoutTableEntryFails() {
        // CortexM without a board table entry → natural is Renode; forcing QEMU → Fail.
        val profile = cortexMProfile(boardId = "nrf52840dk_nrf52840")
        val result = selectBackend(profile, forced = Backend.Qemu)
        assertIs<BackendSelection.Fail>(result)
        assertEquals(
            "Cannot run nrf52840dk_nrf52840 with QEMU: no QEMU machine models this SoC.",
            result.reason,
        )
        assertEquals(
            "Switch to Renode (recommended), which will generate a platform from zephyr.dts.",
            result.alternative,
        )
    }

    @Test
    fun forcedQemuOnKnownQemuBoardSucceeds() {
        // Forcing QEMU on a board already in the QEMU table is a no-op (same result).
        val profile = cortexMProfile(boardId = "qemu_cortex_m3")
        val result = selectBackend(profile, forced = Backend.Qemu)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(Backend.Qemu, result.backend)
        assertEquals("lm3s6965evb", result.qemuMachine)
    }

    @Test
    fun forcedRenodeOnKnownQemuBoardSucceeds() {
        // User may force Renode even on a QEMU-mapped board (e.g. for peripheral fidelity).
        val profile = cortexMProfile(boardId = "qemu_cortex_m3")
        val result = selectBackend(profile, forced = Backend.Renode)
        assertIs<BackendSelection.Ok>(result)
        assertEquals(Backend.Renode, result.backend)
        assertEquals(Backend.Qemu, result.recommended, "Recommended should still be QEMU")
        assertNull(result.qemuMachine)
        // qemu_cortex_m3 is not in RENODE_LIBRARY_BOARDS → Generated.
        assertEquals(ReplSource.Generated, result.replSource)
    }
}
