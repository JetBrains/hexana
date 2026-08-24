package org.jetbrains.hexana.providers.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val EM_ARM = 40
private const val EM_AARCH64 = 183
private const val EM_RISCV = 243
private const val EM_X86 = 3

class TargetProfileDetectionTest {

    private val armElf32 = ElfHeaderFacts(machine = EM_ARM, is64Bit = false, flags = 0)
    private val aarch64Elf64 = ElfHeaderFacts(machine = EM_AARCH64, is64Bit = true, flags = 0)
    private val riscv32Elf = ElfHeaderFacts(machine = EM_RISCV, is64Bit = false, flags = 0)
    private val riscv64Elf = ElfHeaderFacts(machine = EM_RISCV, is64Bit = true, flags = 0)
    private val x86Elf = ElfHeaderFacts(machine = EM_X86, is64Bit = false, flags = 0)

    // ---------------------------------------------------------------------------
    // CONFIG_BOARD wins (Confidence.Exact)
    // ---------------------------------------------------------------------------

    @Test
    fun configBoardWinsOverElfMachine() {
        // Disagreement fixture: CONFIG_BOARD says qemu_cortex_m3 but ELF is also EM_ARM.
        // Board wins; confidence must be Exact.
        val config = "CONFIG_BOARD=\"qemu_cortex_m3\"\n"
        val profile = detectTargetProfile(armElf32, dtsText = null, configText = config)
        assertEquals("qemu_cortex_m3", profile.boardId)
        assertEquals(Confidence.Exact, profile.confidence)
        assertEquals(ArchClass.CortexM, profile.archClass)
    }

    @Test
    fun configBoardWithDtsCpuCompatible() {
        // CONFIG_BOARD present + DTS with cpu compatible — board wins, archClass refined by cpu.
        val config = "CONFIG_BOARD=\"qemu_cortex_a53\"\n"
        val dts = minimalDtsWithCpuCompat("arm,cortex-a53")
        val profile = detectTargetProfile(aarch64Elf64, dtsText = dts, configText = config)
        assertEquals("qemu_cortex_a53", profile.boardId)
        assertEquals(Confidence.Exact, profile.confidence)
        assertEquals(ArchClass.CortexA, profile.archClass)
    }

    // ---------------------------------------------------------------------------
    // DTS root compatible (Confidence.Exact, no CONFIG_BOARD)
    // ---------------------------------------------------------------------------

    @Test
    fun dtsRootCompatibleDerivesBoard() {
        val dts = minimalDtsWithRoot("nordic,nrf52840-dk-nrf52840")
        val profile = detectTargetProfile(armElf32, dtsText = dts, configText = null)
        assertEquals("nrf52840-dk-nrf52840", profile.boardId,
            "Board should be derived from tail of first DTS root compatible token")
        assertEquals(Confidence.Exact, profile.confidence)
    }

    @Test
    fun dtsRootCompatibleWithCpuArchClass() {
        val dts = minimalDtsWithRootAndCpu("nordic,nrf52840-dk-nrf52840", "arm,cortex-m4f")
        val profile = detectTargetProfile(armElf32, dtsText = dts, configText = null)
        assertEquals("nrf52840-dk-nrf52840", profile.boardId)
        assertEquals(ArchClass.CortexM, profile.archClass)
        assertEquals(Confidence.Exact, profile.confidence)
    }

    // ---------------------------------------------------------------------------
    // CPU compatible (Confidence.Heuristic, no board)
    // ---------------------------------------------------------------------------

    @Test
    fun cpuCompatibleCortexM() {
        val dts = minimalDtsWithCpuCompat("arm,cortex-m3")
        val profile = detectTargetProfile(armElf32, dtsText = dts, configText = null)
        assertNull(profile.boardId)
        assertEquals(ArchClass.CortexM, profile.archClass)
        assertEquals(Confidence.Heuristic, profile.confidence)
    }

    @Test
    fun cpuCompatibleCortexA() {
        val dts = minimalDtsWithCpuCompat("arm,cortex-a53")
        val profile = detectTargetProfile(aarch64Elf64, dtsText = dts, configText = null)
        assertNull(profile.boardId)
        assertEquals(ArchClass.CortexA, profile.archClass)
        assertEquals(Confidence.Heuristic, profile.confidence)
    }

    @Test
    fun cpuCompatibleRiscV() {
        val dts = minimalDtsWithCpuCompat("riscv")
        val profile = detectTargetProfile(riscv32Elf, dtsText = dts, configText = null)
        assertNull(profile.boardId)
        assertEquals(ArchClass.RiscV, profile.archClass)
        assertEquals(Confidence.Heuristic, profile.confidence)
    }

    // ---------------------------------------------------------------------------
    // ELF header only (Confidence.ElfOnly)
    // ---------------------------------------------------------------------------

    @Test
    fun elfOnlyArm() {
        val profile = detectTargetProfile(armElf32, dtsText = null, configText = null)
        assertNull(profile.boardId)
        assertEquals(ArchClass.CortexM, profile.archClass)
        assertEquals(Confidence.ElfOnly, profile.confidence)
        assertEquals(EM_ARM, profile.elfMachine)
        assertEquals(false, profile.elfIs64Bit)
    }

    @Test
    fun elfOnlyAarch64() {
        val profile = detectTargetProfile(aarch64Elf64, dtsText = null, configText = null)
        assertNull(profile.boardId)
        assertEquals(ArchClass.CortexA, profile.archClass)
        assertEquals(Confidence.ElfOnly, profile.confidence)
        assertEquals(EM_AARCH64, profile.elfMachine)
        assertEquals(true, profile.elfIs64Bit)
    }

    @Test
    fun elfOnlyRiscV32() {
        val profile = detectTargetProfile(riscv32Elf, dtsText = null, configText = null)
        assertNull(profile.boardId)
        assertEquals(ArchClass.RiscV, profile.archClass)
        assertEquals(Confidence.ElfOnly, profile.confidence)
        assertEquals(false, profile.elfIs64Bit)
    }

    @Test
    fun elfOnlyRiscV64() {
        val profile = detectTargetProfile(riscv64Elf, dtsText = null, configText = null)
        assertEquals(ArchClass.RiscV, profile.archClass)
        assertEquals(Confidence.ElfOnly, profile.confidence)
        assertEquals(true, profile.elfIs64Bit)
    }

    @Test
    fun elfOnlyOther() {
        val profile = detectTargetProfile(x86Elf, dtsText = null, configText = null)
        assertNull(profile.boardId)
        assertEquals(ArchClass.Other, profile.archClass)
        assertEquals(Confidence.ElfOnly, profile.confidence)
    }

    // ---------------------------------------------------------------------------
    // Disagreement fixture: CONFIG_BOARD overrides ELF machine class mismatch
    // ---------------------------------------------------------------------------

    @Test
    fun configBoardOverridesWhenElfMachineDisagrees() {
        // CONFIG_BOARD says qemu_cortex_m3 (CortexM), but ELF is EM_AARCH64.
        // CONFIG_BOARD has highest authority → boardId set, confidence Exact.
        val config = "CONFIG_BOARD=\"qemu_cortex_m3\"\n"
        val profile = detectTargetProfile(aarch64Elf64, dtsText = null, configText = config)
        assertEquals("qemu_cortex_m3", profile.boardId)
        assertEquals(Confidence.Exact, profile.confidence)
        // archClass: no cpu compatible, ELF says AARCH64 → CortexA from ELF (config sets board
        // but archClass still comes from the best available source, here ELF).
        // This is acceptable — LANE-RUN uses boardId for table lookup, not archClass alone.
    }

    // ---------------------------------------------------------------------------
    // socCompatibles accumulation
    // ---------------------------------------------------------------------------

    @Test
    fun socCompatiblesIncludesRootAndCpu() {
        val dts = minimalDtsWithRootAndCpu("nordic,nrf52840-dk-nrf52840", "arm,cortex-m4f")
        val profile = detectTargetProfile(armElf32, dtsText = dts, configText = null)
        assertTrue("nordic,nrf52840-dk-nrf52840" in profile.socCompatibles,
            "Root compatible must appear in socCompatibles")
        assertTrue("arm,cortex-m4f" in profile.socCompatibles,
            "CPU compatible must appear in socCompatibles")
    }

    // ---------------------------------------------------------------------------
    // Minimal DTS builders
    // ---------------------------------------------------------------------------

    private fun minimalDtsWithRoot(rootCompat: String) = """
        /dts-v1/;
        / {
            compatible = "$rootCompat";
        };
    """.trimIndent()

    private fun minimalDtsWithCpuCompat(cpuCompat: String) = """
        /dts-v1/;
        / {
            cpus {
                cpu@0 {
                    compatible = "$cpuCompat";
                    reg = < 0x0 >;
                };
            };
        };
    """.trimIndent()

    private fun minimalDtsWithRootAndCpu(rootCompat: String, cpuCompat: String) = """
        /dts-v1/;
        / {
            compatible = "$rootCompat";
            cpus {
                cpu@0 {
                    compatible = "$cpuCompat";
                    reg = < 0x0 >;
                };
            };
        };
    """.trimIndent()
}

private fun assertTrue(value: Boolean, message: String) {
    kotlin.test.assertTrue(value, message)
}
