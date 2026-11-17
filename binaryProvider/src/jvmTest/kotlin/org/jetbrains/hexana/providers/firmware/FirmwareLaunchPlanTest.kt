package org.jetbrains.hexana.providers.firmware

import kotlin.test.Test
import kotlin.test.assertEquals

/** Exact golden values for QEMU command-line and Renode .resc script generation. */
class FirmwareLaunchPlanTest {

    // ---------------------------------------------------------------------------
    // buildQemuCommandLine — qemu_cortex_m3 run variant
    // ---------------------------------------------------------------------------

    @Test
    fun qemuCortexM3RunCommandLine() {
        val selection = BackendSelection.Ok(
            backend = Backend.Qemu,
            recommended = Backend.Qemu,
            qemuMachine = "lm3s6965evb",
            qemuBinary = QemuBinary.SystemArm,
            qemuExtraArgs = listOf("-cpu", "cortex-m3"),
            replSource = null,
            rationale = "test",
        )
        val args = buildQemuCommandLine(selection, elfPath = "/build/zephyr/zephyr.elf", extraArgs = emptyList())
        assertEquals(
            listOf(
                "qemu-system-arm",
                "-machine", "lm3s6965evb",
                "-cpu", "cortex-m3",
                "-kernel", "/build/zephyr/zephyr.elf",
                "-nographic",
                "-serial", "mon:stdio",
            ),
            args,
        )
    }

    // ---------------------------------------------------------------------------
    // buildQemuCommandLine — qemu_cortex_m3 debug variant (gdbStubPort)
    // ---------------------------------------------------------------------------

    @Test
    fun qemuCortexM3DebugCommandLine() {
        val selection = BackendSelection.Ok(
            backend = Backend.Qemu,
            recommended = Backend.Qemu,
            qemuMachine = "lm3s6965evb",
            qemuBinary = QemuBinary.SystemArm,
            qemuExtraArgs = listOf("-cpu", "cortex-m3"),
            replSource = null,
            rationale = "test",
        )
        val args = buildQemuCommandLine(
            selection,
            elfPath = "/build/zephyr/zephyr.elf",
            extraArgs = emptyList(),
            gdbStubPort = 1234,
        )
        assertEquals(
            listOf(
                "qemu-system-arm",
                "-machine", "lm3s6965evb",
                "-cpu", "cortex-m3",
                "-kernel", "/build/zephyr/zephyr.elf",
                "-nographic",
                "-serial", "mon:stdio",
                "-gdb", "tcp::1234",
                "-S",
            ),
            args,
        )
    }

    // ---------------------------------------------------------------------------
    // buildQemuCommandLine — qemu_riscv32 with extra args and user extra args
    // ---------------------------------------------------------------------------

    @Test
    fun qemuRiscV32WithUserExtraArgs() {
        val selection = BackendSelection.Ok(
            backend = Backend.Qemu,
            recommended = Backend.Qemu,
            qemuMachine = "virt",
            qemuBinary = QemuBinary.SystemRiscv32,
            qemuExtraArgs = listOf("-bios", "none", "-m", "256"),
            replSource = null,
            rationale = "test",
        )
        val args = buildQemuCommandLine(
            selection,
            elfPath = "/fw/zephyr.elf",
            extraArgs = listOf("-d", "guest_errors"),
        )
        assertEquals(
            listOf(
                "qemu-system-riscv32",
                "-machine", "virt",
                "-bios", "none",
                "-m", "256",
                "-kernel", "/fw/zephyr.elf",
                "-nographic",
                "-serial", "mon:stdio",
                "-d", "guest_errors",
            ),
            args,
        )
    }

    // ---------------------------------------------------------------------------
    // buildRescScript — generated .repl variant (no gdb)
    // ---------------------------------------------------------------------------

    @Test
    fun rescScriptGenerated() {
        val script = buildRescScript(
            replPath = "/tmp/hexana/generated.repl",
            libraryPlatform = null,
            elfPath = "/build/zephyr/zephyr.elf",
            uartPort = 5000,
        )
        assertEquals(
            """
            mach create "machine0"
            machine LoadPlatformDescription @/tmp/hexana/generated.repl
            sysbus LoadELF @/build/zephyr/zephyr.elf
            emulation CreateServerSocketTerminal 5000 "term" false
            connector Connect sysbus.uart0 term
            start
            """.trimIndent() + "\n",
            script,
        )
    }

    // ---------------------------------------------------------------------------
    // buildRescScript — library .repl variant (no gdb)
    // ---------------------------------------------------------------------------

    @Test
    fun rescScriptLibrary() {
        val script = buildRescScript(
            replPath = null,
            libraryPlatform = "platforms/boards/nrf52840dk.repl",
            elfPath = "/build/zephyr/zephyr.elf",
            uartPort = 5001,
        )
        assertEquals(
            """
            mach create "machine0"
            machine LoadPlatformDescription @platforms/boards/nrf52840dk.repl
            sysbus LoadELF @/build/zephyr/zephyr.elf
            emulation CreateServerSocketTerminal 5001 "term" false
            connector Connect sysbus.uart0 term
            start
            """.trimIndent() + "\n",
            script,
        )
    }

    // ---------------------------------------------------------------------------
    // buildRescScript — gdb variant (no `start`, StartGdbServer autostart=false: the
    // debugger materializes breakpoints first, then starts emulation via `monitor start`)
    // ---------------------------------------------------------------------------

    @Test
    fun rescScriptWithGdb() {
        val script = buildRescScript(
            replPath = "/tmp/hexana/generated.repl",
            libraryPlatform = null,
            elfPath = "/build/zephyr/zephyr.elf",
            uartPort = 5000,
            gdbPort = 3333,
        )
        assertEquals(
            """
            mach create "machine0"
            machine LoadPlatformDescription @/tmp/hexana/generated.repl
            sysbus LoadELF @/build/zephyr/zephyr.elf
            emulation CreateServerSocketTerminal 5000 "term" false
            connector Connect sysbus.uart0 term
            machine StartGdbServer 3333 false
            """.trimIndent() + "\n",
            script,
        )
    }

    // ---------------------------------------------------------------------------
    // buildRescScript — path with spaces is quoted
    // ---------------------------------------------------------------------------

    @Test
    fun rescScriptPathWithSpaces() {
        val script = buildRescScript(
            replPath = "/my path/hexana/generated.repl",
            libraryPlatform = null,
            elfPath = "/my path/build/zephyr.elf",
            uartPort = 5002,
        )
        val lines = script.lines().filter { it.isNotBlank() }
        assertEquals("machine LoadPlatformDescription @\"/my path/hexana/generated.repl\"", lines[1])
        assertEquals("sysbus LoadELF @\"/my path/build/zephyr.elf\"", lines[2])
    }

    // ---------------------------------------------------------------------------
    // buildRescScript — custom uart peripheral and machine name
    // ---------------------------------------------------------------------------

    @Test
    fun rescScriptCustomUartAndMachineName() {
        val script = buildRescScript(
            replPath = "/tmp/hexana/generated.repl",
            libraryPlatform = null,
            elfPath = "/fw/zephyr.elf",
            uartPort = 5003,
            uartPeripheral = "ns16550",
            machineName = "myMachine",
        )
        val lines = script.lines().filter { it.isNotBlank() }
        assertEquals("mach create \"myMachine\"", lines[0])
        assertEquals("connector Connect sysbus.ns16550 term", lines[4])
    }

    // ---------------------------------------------------------------------------
    // buildRescScript — extra lines are inserted before start
    // ---------------------------------------------------------------------------

    @Test
    fun rescScriptWithExtraLines() {
        val script = buildRescScript(
            replPath = "/tmp/hexana/generated.repl",
            libraryPlatform = null,
            elfPath = "/fw/zephyr.elf",
            uartPort = 5004,
            extraLines = listOf("logLevel 3 sysbus"),
        )
        val lines = script.lines().filter { it.isNotBlank() }
        assertEquals("logLevel 3 sysbus", lines[5])
        assertEquals("start", lines[6])
    }

    // ---------------------------------------------------------------------------
    // QemuBinary.toCliName — all variants
    // ---------------------------------------------------------------------------

    @Test
    fun qemuBinaryToCliNames() {
        assertEquals("qemu-system-arm", QemuBinary.SystemArm.toCliName())
        assertEquals("qemu-system-aarch64", QemuBinary.SystemAarch64.toCliName())
        assertEquals("qemu-system-riscv32", QemuBinary.SystemRiscv32.toCliName())
        assertEquals("qemu-system-riscv64", QemuBinary.SystemRiscv64.toCliName())
    }
}
