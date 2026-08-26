package org.jetbrains.hexana.providers.firmware

/**
 * Pure generators for emulator launch parameters.
 *
 * All functions in this file are pure (no I/O, no side effects). They take
 * already-resolved information and produce command-line argument lists or
 * script text that the IntelliJ-side run/debug infrastructure executes.
 *
 * The command-line generators return the **binary name only** (e.g. `"qemu-system-arm"`)
 * as the first element; callers resolve the absolute path via settings or PATH lookup.
 */

// ---------------------------------------------------------------------------
// Renode script syntax constants
// ---------------------------------------------------------------------------
// These lines encode Renode Monitor command syntax. They are named constants so
// LANE-RUN can update them in one place if the Renode syntax changes.
// Syntax confirmed against Renode Monitor documentation and fact-sheet.

/** Renode command: create a new machine named [machineName]. */
private fun rescMachCreate(machineName: String) = "mach create \"$machineName\""

/** Renode command: load a platform description from a file path or library alias. */
private fun rescLoadPlatform(path: String) = "machine LoadPlatformDescription $path"

/** Renode command: load an ELF binary onto the machine. */
private fun rescLoadElf(elfPath: String) = "sysbus LoadELF ${rescPath(elfPath)}"

/**
 * Renode command: create a server-socket terminal for UART bridging.
 * Syntax confirmed: `emulation CreateServerSocketTerminal <port> "<name>" false`.
 * The UART peripheral name varies by board (e.g. `uart0` for nRF52840, `ns16550` for lm3s6965);
 * LANE-RUN passes the board-specific name via [buildRescScript]'s [uartPeripheral] parameter.
 */
private fun rescCreateTerminal(uartPort: Int, termName: String) =
    "emulation CreateServerSocketTerminal $uartPort \"$termName\" false"

private fun rescConnectUart(uartPeripheral: String, termName: String) =
    "connector Connect sysbus.$uartPeripheral $termName"

/**
 * Renode command: start the GDB server on the machine.
 * The `true` argument tells Renode to auto-start emulation on GDB connect.
 * When this is emitted, `start` is NOT emitted (emulation stays frozen until GDB attaches).
 */
// autostart=false: Renode's `true` flag starts EMULATION at gdb TCP connect (which happens at
// the DAP attach request, before breakpoints exist); fast firmware boots to idle in those
// milliseconds. With false the machine stays unstarted until the debugger issues `monitor start`
// after materializing breakpoints; the CPU then comes up halted under gdb at the reset vector.
private fun rescStartGdb(gdbPort: Int) = "machine StartGdbServer $gdbPort false"

/** Renode command: start emulation. Only emitted when NOT starting GDB (debug mode is frozen). */
private const val RESC_START = "start"

/**
 * Wraps a filesystem path for Renode's `@path` syntax.
 * Paths with spaces are quoted; others are bare (Renode handles both).
 */
private fun rescPath(path: String): String =
    if (' ' in path) "@\"$path\"" else "@$path"

// ---------------------------------------------------------------------------
// QEMU command-line builder
// ---------------------------------------------------------------------------

/**
 * Builds the QEMU command-line argument list for a firmware run.
 *
 * The first element is the binary name (e.g. `"qemu-system-arm"`); the caller
 * resolves it to an absolute path. The list is suitable for passing directly to
 * [com.intellij.execution.configurations.GeneralCommandLine].
 *
 * @param selection A successful backend selection (must be [BackendSelection.Ok] with
 *   [BackendSelection.Ok.backend] == [Backend.Qemu]).
 * @param elfPath Absolute path to the ELF firmware binary.
 * @param extraArgs Additional QEMU arguments from the run configuration.
 * @param gdbStubPort When non-null, appends `-gdb tcp::<port> -S` to start QEMU
 *   in frozen GDB-stub mode (for debug sessions).
 * @return Ordered argument list including the binary name as element 0.
 */
public fun buildQemuCommandLine(
    selection: BackendSelection.Ok,
    elfPath: String,
    extraArgs: List<String>,
    gdbStubPort: Int? = null,
): List<String> {
    val binary = requireNotNull(selection.qemuBinary) { "selection.qemuBinary must be non-null for QEMU backend" }
    val machine = requireNotNull(selection.qemuMachine) { "selection.qemuMachine must be non-null for QEMU backend" }

    return buildList {
        add(binary.toCliName())
        add("-machine")
        add(machine)
        addAll(selection.qemuExtraArgs)
        add("-kernel")
        add(elfPath)
        add("-nographic")
        add("-serial")
        add("mon:stdio")
        addAll(extraArgs)
        if (gdbStubPort != null) {
            add("-gdb")
            add("tcp::$gdbStubPort")
            add("-S")
        }
    }
}

/** Returns the `qemu-system-*` CLI binary name for this [QemuBinary]. */
public fun QemuBinary.toCliName(): String = when (this) {
    QemuBinary.SystemArm -> "qemu-system-arm"
    QemuBinary.SystemAarch64 -> "qemu-system-aarch64"
    QemuBinary.SystemRiscv32 -> "qemu-system-riscv32"
    QemuBinary.SystemRiscv64 -> "qemu-system-riscv64"
}

// ---------------------------------------------------------------------------
// Renode .resc script builder
// ---------------------------------------------------------------------------

/**
 * Builds a Renode Monitor script (`.resc`) for a firmware run.
 *
 * @param replPath Absolute path to a generated `.repl` platform file, or null when
 *   [libraryPlatform] is provided instead.
 * @param libraryPlatform Renode built-in platform alias (e.g. `"platforms/boards/nrf52840dk.repl"`),
 *   or null when [replPath] is provided.
 * @param elfPath Absolute path to the ELF firmware binary.
 * @param uartPort Local TCP port for the UART terminal server socket.
 * @param uartPeripheral The Renode sysbus peripheral name for the UART
 *   (e.g. `"uart0"` for nRF52840/STM32, `"ns16550"` for lm3s6965).
 *   LANE-RUN passes the board-specific name; [DEFAULT_UART_PERIPHERAL] is used by default.
 * @param extraLines Additional Renode Monitor commands appended before start/gdb.
 * @param gdbPort When non-null, adds `machine StartGdbServer <port>` and does NOT
 *   emit `start` (emulation stays frozen for GDB attach).
 * @param machineName Renode machine name (default `"machine0"`).
 * @return The complete `.resc` script text.
 */
public fun buildRescScript(
    replPath: String?,
    libraryPlatform: String?,
    elfPath: String,
    uartPort: Int,
    uartPeripheral: String = DEFAULT_UART_PERIPHERAL,
    extraLines: List<String> = emptyList(),
    gdbPort: Int? = null,
    machineName: String = "machine0",
): String {
    require((replPath == null) != (libraryPlatform == null)) {
        "Exactly one of replPath or libraryPlatform must be non-null"
    }

    val platformArg = if (replPath != null) {
        rescPath(replPath)
    } else {
        "@$libraryPlatform" // library aliases are never quoted
    }

    val termName = "term"
    return buildString {
        appendLine(rescMachCreate(machineName))
        appendLine(rescLoadPlatform(platformArg))
        appendLine(rescLoadElf(elfPath))
        appendLine(rescCreateTerminal(uartPort, termName))
        appendLine(rescConnectUart(uartPeripheral, termName))
        for (extra in extraLines) appendLine(extra)
        if (gdbPort != null) {
            appendLine(rescStartGdb(gdbPort))
            // No `start` — emulation is frozen for GDB to attach.
        } else {
            appendLine(RESC_START)
        }
    }
}

/**
 * Default UART peripheral name used in [buildRescScript].
 *
 * Common values: `"uart0"` (nRF52840, STM32), `"ns16550"` (QEMU lm3s6965/cortex_m3).
 * LANE-RUN passes the board-specific name when it differs from this default.
 */
public const val DEFAULT_UART_PERIPHERAL: String = "uart0"
