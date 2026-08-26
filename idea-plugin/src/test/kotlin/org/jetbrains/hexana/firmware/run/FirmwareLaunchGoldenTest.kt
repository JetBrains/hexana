package org.jetbrains.hexana.firmware.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.providers.firmware.ElfHeaderFacts
import org.jetbrains.hexana.providers.firmware.ReplSource
import org.jetbrains.hexana.providers.firmware.buildQemuCommandLine
import org.jetbrains.hexana.providers.firmware.buildRescScript
import org.jetbrains.hexana.providers.firmware.detectTargetProfile
import org.jetbrains.hexana.providers.firmware.selectBackend
import java.io.File

/**
 * Golden-string tests for QEMU command lines and Renode `.resc` scripts.
 *
 * These tests use REAL corpus fixture data (dts + config stubs under
 * `idea-plugin/src/test/data/firmware/`) and call the pure commonMain generators
 * ([buildQemuCommandLine], [buildRescScript]) directly — no process I/O, no IDE services.
 *
 * DTS files are copied from
 * `ui-driver-tests/testData/fixtures/zephyr-corpus/apps/hello_world/<board>-Os/zephyr.dts`.
 * Config stubs are synthetic `.config` files containing only `CONFIG_BOARD`.
 *
 * Tests must be able to fail: each assertion checks the EXACT expected list / string;
 * a wrong machine name or missing arg will cause a hard failure.
 */
class FirmwareLaunchGoldenTest : UsefulTestCase() {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val dataDir: File by lazy {
        // Walk up from the project root. Works in both Gradle and IDE test runners.
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { it.resolve("idea-plugin").isDirectory }
            ?: error("Cannot locate project root from ${File(".").canonicalPath}")
        root.resolve("idea-plugin/src/test/data/firmware")
    }

    private fun readText(name: String): String =
        dataDir.resolve(name).readText()

    private fun buildSelectionFor(configFile: String, dtsFile: String): BackendSelection.Ok {
        val configText = readText(configFile)
        val dtsText = readText(dtsFile)
        // Use a minimal ELF32 ARM header (EM_ARM = 40, EI_CLASS = 1 = 32-bit).
        val header = ElfHeaderFacts(machine = 40, is64Bit = false, flags = 0)
        val profile = detectTargetProfile(header, dtsText, configText)
        val sel = selectBackend(profile)
        assertTrue(
            "Expected BackendSelection.Ok for $configFile but got $sel",
            sel is BackendSelection.Ok,
        )
        return sel as BackendSelection.Ok
    }

    private fun buildSelectionForAarch64(configFile: String, dtsFile: String): BackendSelection.Ok {
        val configText = readText(configFile)
        val dtsText = readText(dtsFile)
        val header = ElfHeaderFacts(machine = 183 /* EM_AARCH64 */, is64Bit = true, flags = 0)
        val profile = detectTargetProfile(header, dtsText, configText)
        val sel = selectBackend(profile)
        assertTrue(
            "Expected BackendSelection.Ok for $configFile but got $sel",
            sel is BackendSelection.Ok,
        )
        return sel as BackendSelection.Ok
    }

    private fun buildSelectionForRiscV(configFile: String, dtsFile: String): BackendSelection.Ok {
        val configText = readText(configFile)
        val dtsText = readText(dtsFile)
        val header = ElfHeaderFacts(machine = 243 /* EM_RISCV */, is64Bit = false, flags = 0)
        val profile = detectTargetProfile(header, dtsText, configText)
        val sel = selectBackend(profile)
        assertTrue(
            "Expected BackendSelection.Ok for $configFile but got $sel",
            sel is BackendSelection.Ok,
        )
        return sel as BackendSelection.Ok
    }

    // -------------------------------------------------------------------------
    // qemu_cortex_m3 — run variant
    // -------------------------------------------------------------------------

    fun testQemuCortexM3CommandLineRun() {
        val selection = buildSelectionFor("qemu_cortex_m3.config", "qemu_cortex_m3.dts")
        val elfPath = "/build/qemu_cortex_m3/zephyr/zephyr.elf"
        val cmd = buildQemuCommandLine(selection, elfPath, emptyList())

        assertEquals(
            listOf(
                "qemu-system-arm",
                "-machine", "lm3s6965evb",
                "-cpu", "cortex-m3",
                "-kernel", elfPath,
                "-nographic",
                "-serial", "mon:stdio",
            ),
            cmd,
        )
    }

    // -------------------------------------------------------------------------
    // qemu_cortex_m3 — debug variant (gdbStubPort)
    // -------------------------------------------------------------------------

    fun testQemuCortexM3CommandLineDebug() {
        val selection = buildSelectionFor("qemu_cortex_m3.config", "qemu_cortex_m3.dts")
        val elfPath = "/build/qemu_cortex_m3/zephyr/zephyr.elf"
        val cmd = buildQemuCommandLine(selection, elfPath, emptyList(), gdbStubPort = 3333)

        assertEquals(
            listOf(
                "qemu-system-arm",
                "-machine", "lm3s6965evb",
                "-cpu", "cortex-m3",
                "-kernel", elfPath,
                "-nographic",
                "-serial", "mon:stdio",
                "-gdb", "tcp::3333",
                "-S",
            ),
            cmd,
        )
    }

    // -------------------------------------------------------------------------
    // mps2_an521
    // -------------------------------------------------------------------------

    fun testMps2An521CommandLineRun() {
        val selection = buildSelectionFor("mps2_an521.config", "mps2_an521.dts")
        val elfPath = "/build/mps2_an521/zephyr/zephyr.elf"
        val cmd = buildQemuCommandLine(selection, elfPath, emptyList())

        assertEquals(
            listOf(
                "qemu-system-arm",
                "-machine", "mps2-an521",
                "-cpu", "cortex-m33",
                "-kernel", elfPath,
                "-nographic",
                "-serial", "mon:stdio",
            ),
            cmd,
        )
    }

    // -------------------------------------------------------------------------
    // qemu_riscv32
    // -------------------------------------------------------------------------

    fun testQemuRiscV32CommandLineRun() {
        val selection = buildSelectionForRiscV("qemu_riscv32.config", "qemu_riscv32.dts")
        val elfPath = "/build/qemu_riscv32/zephyr/zephyr.elf"
        val cmd = buildQemuCommandLine(selection, elfPath, emptyList())

        assertEquals(
            listOf(
                "qemu-system-riscv32",
                "-machine", "virt",
                "-bios", "none",
                "-m", "256",
                "-kernel", elfPath,
                "-nographic",
                "-serial", "mon:stdio",
            ),
            cmd,
        )
    }

    fun testQemuRiscV32CommandLineDebug() {
        val selection = buildSelectionForRiscV("qemu_riscv32.config", "qemu_riscv32.dts")
        val elfPath = "/build/qemu_riscv32/zephyr/zephyr.elf"
        val cmd = buildQemuCommandLine(selection, elfPath, emptyList(), gdbStubPort = 3333)

        assertEquals(
            listOf(
                "qemu-system-riscv32",
                "-machine", "virt",
                "-bios", "none",
                "-m", "256",
                "-kernel", elfPath,
                "-nographic",
                "-serial", "mon:stdio",
                "-gdb", "tcp::3333",
                "-S",
            ),
            cmd,
        )
    }

    // -------------------------------------------------------------------------
    // qemu_cortex_a53
    // -------------------------------------------------------------------------

    fun testQemuCortexA53CommandLineRun() {
        val selection = buildSelectionForAarch64("qemu_cortex_a53.config", "qemu_cortex_a53.dts")
        val elfPath = "/build/qemu_cortex_a53/zephyr/zephyr.elf"
        val cmd = buildQemuCommandLine(selection, elfPath, emptyList())

        assertEquals(
            listOf(
                "qemu-system-aarch64",
                "-machine", "virt,secure=on,gic-version=3",
                "-cpu", "cortex-a53",
                "-kernel", elfPath,
                "-nographic",
                "-serial", "mon:stdio",
            ),
            cmd,
        )
    }

    fun testQemuCortexA53CommandLineDebug() {
        val selection = buildSelectionForAarch64("qemu_cortex_a53.config", "qemu_cortex_a53.dts")
        val elfPath = "/build/qemu_cortex_a53/zephyr/zephyr.elf"
        val cmd = buildQemuCommandLine(selection, elfPath, emptyList(), gdbStubPort = 4444)

        assertEquals(
            listOf(
                "qemu-system-aarch64",
                "-machine", "virt,secure=on,gic-version=3",
                "-cpu", "cortex-a53",
                "-kernel", elfPath,
                "-nographic",
                "-serial", "mon:stdio",
                "-gdb", "tcp::4444",
                "-S",
            ),
            cmd,
        )
    }

    // -------------------------------------------------------------------------
    // Renode golden: nrf52840dk library platform (run variant)
    // -------------------------------------------------------------------------

    fun testRenodeNrf52840LibraryRescRun() {
        val resc = buildRescScript(
            replPath = null,
            libraryPlatform = "platforms/cpus/nrf52840.repl",
            elfPath = "/build/nrf52840dk/zephyr/zephyr.elf",
            uartPort = 12345,
            uartPeripheral = "uart0",
            extraLines = emptyList(),
            gdbPort = null,
            machineName = "machine0",
        )

        val expected = """
            mach create "machine0"
            machine LoadPlatformDescription @platforms/cpus/nrf52840.repl
            sysbus LoadELF @/build/nrf52840dk/zephyr/zephyr.elf
            emulation CreateServerSocketTerminal 12345 "term" false
            connector Connect sysbus.uart0 term
            start
        """.trimIndent() + "\n"

        assertEquals(expected, resc)
    }

    // -------------------------------------------------------------------------
    // Renode golden: nrf52840dk library platform (debug/gdb variant)
    // -------------------------------------------------------------------------

    fun testRenodeNrf52840LibraryRescDebug() {
        val resc = buildRescScript(
            replPath = null,
            libraryPlatform = "platforms/cpus/nrf52840.repl",
            elfPath = "/build/nrf52840dk/zephyr/zephyr.elf",
            uartPort = 12345,
            uartPeripheral = "uart0",
            extraLines = emptyList(),
            gdbPort = 3333,
            machineName = "machine0",
        )

        val expected = """
            mach create "machine0"
            machine LoadPlatformDescription @platforms/cpus/nrf52840.repl
            sysbus LoadELF @/build/nrf52840dk/zephyr/zephyr.elf
            emulation CreateServerSocketTerminal 12345 "term" false
            connector Connect sysbus.uart0 term
            machine StartGdbServer 3333 false
        """.trimIndent() + "\n"

        assertEquals(expected, resc)
    }

    // -------------------------------------------------------------------------
    // Renode golden: generated-repl variant (run)
    // -------------------------------------------------------------------------

    fun testRenodeGeneratedReplRescRun() {
        val replPath = "/tmp/hexana-renode-12345.repl"
        val resc = buildRescScript(
            replPath = replPath,
            libraryPlatform = null,
            elfPath = "/build/stm32f4/zephyr/zephyr.elf",
            uartPort = 54321,
            uartPeripheral = "uart0",
            extraLines = emptyList(),
            gdbPort = null,
            machineName = "machine0",
        )

        val expected = """
            mach create "machine0"
            machine LoadPlatformDescription @/tmp/hexana-renode-12345.repl
            sysbus LoadELF @/build/stm32f4/zephyr/zephyr.elf
            emulation CreateServerSocketTerminal 54321 "term" false
            connector Connect sysbus.uart0 term
            start
        """.trimIndent() + "\n"

        assertEquals(expected, resc)
    }

    // -------------------------------------------------------------------------
    // Renode golden: generated-repl variant (debug/gdb)
    // -------------------------------------------------------------------------

    fun testRenodeGeneratedReplRescDebug() {
        val replPath = "/tmp/hexana-renode-12345.repl"
        val resc = buildRescScript(
            replPath = replPath,
            libraryPlatform = null,
            elfPath = "/build/stm32f4/zephyr/zephyr.elf",
            uartPort = 54321,
            uartPeripheral = "uart0",
            extraLines = emptyList(),
            gdbPort = 3333,
            machineName = "machine0",
        )

        val expected = """
            mach create "machine0"
            machine LoadPlatformDescription @/tmp/hexana-renode-12345.repl
            sysbus LoadELF @/build/stm32f4/zephyr/zephyr.elf
            emulation CreateServerSocketTerminal 54321 "term" false
            connector Connect sysbus.uart0 term
            machine StartGdbServer 3333 false
        """.trimIndent() + "\n"

        assertEquals(expected, resc)
    }

    // -------------------------------------------------------------------------
    // Extra QEMU args pass-through
    // -------------------------------------------------------------------------

    fun testQemuCortexM3ExtraArgsPassThrough() {
        val selection = buildSelectionFor("qemu_cortex_m3.config", "qemu_cortex_m3.dts")
        val elfPath = "/build/zephyr.elf"
        val cmd = buildQemuCommandLine(selection, elfPath, listOf("-d", "trace:uart*"))

        assertEquals(
            listOf(
                "qemu-system-arm",
                "-machine", "lm3s6965evb",
                "-cpu", "cortex-m3",
                "-kernel", elfPath,
                "-nographic",
                "-serial", "mon:stdio",
                "-d", "trace:uart*",
            ),
            cmd,
        )
    }

    // -------------------------------------------------------------------------
    // Renode .resc with extra lines
    // -------------------------------------------------------------------------

    fun testRenodeRescWithExtraLines() {
        val resc = buildRescScript(
            replPath = null,
            libraryPlatform = "platforms/cpus/nrf52840.repl",
            elfPath = "/build/zephyr.elf",
            uartPort = 12345,
            uartPeripheral = "uart0",
            extraLines = listOf("logLevel 0", "logFile @/tmp/renode.log"),
            gdbPort = null,
            machineName = "machine0",
        )

        val expected = """
            mach create "machine0"
            machine LoadPlatformDescription @platforms/cpus/nrf52840.repl
            sysbus LoadELF @/build/zephyr.elf
            emulation CreateServerSocketTerminal 12345 "term" false
            connector Connect sysbus.uart0 term
            logLevel 0
            logFile @/tmp/renode.log
            start
        """.trimIndent() + "\n"

        assertEquals(expected, resc)
    }
}
