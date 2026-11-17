package org.jetbrains.hexana.firmware.itest

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.firmware.run.buildTimeoutDiagnostic
import org.jetbrains.hexana.firmware.run.scanRenodePeripheralLine
import org.jetbrains.hexana.providers.firmware.buildRescScript
import org.jetbrains.hexana.providers.firmware.ArchClass
import org.jetbrains.hexana.providers.firmware.Backend
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.providers.firmware.ElfHeaderFacts
import org.jetbrains.hexana.providers.firmware.detectTargetProfile
import org.jetbrains.hexana.providers.firmware.selectBackend
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Negative-path integration tests for the firmware emulator feature.
 *
 * 1. Unsupported target (x86-64 ELF): detectTargetProfile → archClass Other,
 *    selectBackend → BackendSelection.Fail with the exact failure reason string.
 *
 * 2. Forced-backend fail (v2m_beetle forced to QEMU): BackendSelection.Fail naming the board
 *    and recommending Renode.
 *
 * 3. Watchdog diagnostic unit-test: feeds the four real Renode WARNING line shapes into
 *    the ring-buffer scan function (via armStartupWatchdog's internal logic, exercised through
 *    the exposed [scanRenodeRingBuffer] helper) and asserts the exact diagnostic message content.
 *    Also asserts the QEMU-variant message omits the peripheral clause.
 *
 * 4. hang_unmodeled live test (Renode only): boots the hang_unmodeled/nrf52840dk fixture,
 *    asserts NO banner within 10s, and asserts at least one sysbus WARNING line mentioning
 *    0x4002F000 appears in Renode output.
 */
class FirmwareNegativeTest : UsefulTestCase() {

    private val projectRoot: File by lazy {
        generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { it.resolve("idea-plugin").isDirectory }
            ?: error("Cannot locate project root from ${File(".").canonicalPath}")
    }

    private fun resolveRenode(): String? {
        val envPath = System.getenv("HEXANA_RENODE_PATH")
        if (envPath != null && File(envPath).isFile) return envPath
        val homeRenode = File(System.getProperty("user.home"))
            .resolve("hexana-tools/renode-1.16.1")
        val candidates = listOf(
            homeRenode.resolve("renode"),
            homeRenode.resolve("Renode"),
            homeRenode.resolve("bin/renode"),
        )
        for (c in candidates) {
            if (c.isFile && c.canExecute()) return c.absolutePath
        }
        val pathSearch = ProcessBuilder("which", "renode").start()
        val line = pathSearch.inputStream.bufferedReader().readLine()?.trim()
        pathSearch.waitFor()
        if (!line.isNullOrBlank() && File(line).isFile) return line
        return null
    }

    private fun killTree(process: Process) {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    // -------------------------------------------------------------------------
    // 1. Unsupported target: x86-64 ELF → archClass Other → Fail
    // -------------------------------------------------------------------------

    fun testUnsupportedTargetX86_64() {
        val elfFile = projectRoot.resolve("ui-driver-tests/testData/fixtures/b2-control/hello-x86_64.elf")
        assertTrue("x86-64 ELF fixture not found at ${elfFile.path}", elfFile.exists())

        val bytes = elfFile.readBytes()
        // EI_CLASS offset 4: 2 = ELF64
        val is64Bit = bytes[4].toInt() and 0xFF == 2
        // e_machine bytes 18-19 LE: x86-64 = 62 = 0x3E
        val machine = ((bytes[19].toInt() and 0xFF) shl 8) or (bytes[18].toInt() and 0xFF)
        val header = ElfHeaderFacts(machine = machine, is64Bit = is64Bit, flags = 0)

        val profile = detectTargetProfile(header, dtsText = null, configText = null)
        assertEquals(
            "x86-64 ELF must produce archClass Other",
            ArchClass.Other,
            profile.archClass,
        )

        val selection = selectBackend(profile)
        assertTrue(
            "x86-64 target must produce BackendSelection.Fail, got $selection",
            selection is BackendSelection.Fail,
        )
        val fail = selection as BackendSelection.Fail

        // Exact reason string from BackendSelection.kt rule 4.
        val expectedReasonSubstring = "maps to no supported emulator (arch class Other)"
        assertTrue(
            "Fail.reason must contain '$expectedReasonSubstring' but was: '${fail.reason}'",
            fail.reason.contains(expectedReasonSubstring),
        )
        val expectedAltSubstring = "No QEMU machine and no Renode platform are available"
        assertNotNull("Fail.alternative must be non-null", fail.alternative)
        assertTrue(
            "Fail.alternative must contain '$expectedAltSubstring' but was: '${fail.alternative}'",
            fail.alternative!!.contains(expectedAltSubstring),
        )
    }

    // -------------------------------------------------------------------------
    // 2. Forced-backend fail: v2m_beetle forced to QEMU
    // -------------------------------------------------------------------------

    fun testForcedQemuOnMcuWithoutMachineMapping() {
        val fixtureDir = projectRoot
            .resolve("ui-driver-tests/testData/fixtures/zephyr-corpus/apps/hello_world/v2m_beetle-Os")
        assertTrue("v2m_beetle fixture not found", fixtureDir.isDirectory)

        val dtsText = fixtureDir.resolve("zephyr.dts").readText()
        // v2m_beetle has no .config in the fixture; ELF32 ARM (EM_ARM = 40).
        val header = ElfHeaderFacts(machine = 40, is64Bit = false, flags = 0)
        val profile = detectTargetProfile(header, dtsText = dtsText, configText = null)

        // Natural selection should be Renode (CortexM MCU with no QEMU machine).
        val naturalSel = selectBackend(profile)
        assertTrue(
            "v2m_beetle natural selection expected Ok(Renode), got $naturalSel",
            naturalSel is BackendSelection.Ok && (naturalSel as BackendSelection.Ok).backend == Backend.Renode,
        )

        // Force QEMU — must fail.
        val forcedSel = selectBackend(profile, forced = Backend.Qemu)
        assertTrue(
            "v2m_beetle forced=Qemu must produce BackendSelection.Fail, got $forcedSel",
            forcedSel is BackendSelection.Fail,
        )
        val fail = forcedSel as BackendSelection.Fail

        // The reason must name the board (beetle) and say QEMU can't model it.
        assertTrue(
            "Fail.reason must mention board name, got: '${fail.reason}'",
            fail.reason.contains("beetle"),
        )
        assertTrue(
            "Fail.reason must mention QEMU inability, got: '${fail.reason}'",
            fail.reason.contains("QEMU"),
        )
        // The alternative must name Renode.
        assertNotNull("Fail.alternative must be non-null for MCU forced-QEMU case", fail.alternative)
        assertTrue(
            "Fail.alternative must recommend Renode, got: '${fail.alternative}'",
            fail.alternative!!.contains("Renode"),
        )
    }

    // -------------------------------------------------------------------------
    // 3. Watchdog diagnostic unit test (no emulator needed)
    // -------------------------------------------------------------------------

    /**
     * Tests the ring-buffer scan logic embedded in [armStartupWatchdog] via the
     * [scanRenodeRingBuffer] helper extracted from [FirmwareProcessHygiene].
     *
     * The four real Renode WARNING line shapes (from emulator-tooling-facts.md §1) are
     * fed as ring-buffer contents together with decoy INFO lines. Asserts:
     * - The scan returns the FIRST matching WARNING line.
     * - The Renode-variant diagnostic message contains "did not reach console output",
     *   "blocking on unmodeled hardware", and the peripheral line verbatim.
     * - The QEMU-variant diagnostic message omits the peripheral clause.
     */
    fun testWatchdogDiagnosticMessages() {
        // The four real Renode WARNING shapes from emulator-tooling-facts.md §1.
        val renodeWarningShape1 =
            "09:21:8.1960 [WARNING] sysbus: [cpu: 0x08001200] WriteDoubleWord to non existing peripheral at 0x400D0114, value 0xFFFFFFFF."
        val renodeWarningShape2 =
            "09:21:9.4538 [WARNING] sysbus: [cpu: 0x080012E6] ReadDoubleWord from non existing peripheral at 0x400D0118, returning 0x0."
        val renodeWarningShape3 =
            "08:46:53 [WARNING] sysbus: [cpu: 0x800073A] (tag: 'SYSCFGCOMP') ReadDoubleWord from non existing peripheral at 0x40010014, returning 0x0."
        val renodeWarningShape4 =
            "07:23:45.2192 [WARNING] scu: Unhandled write to offset 0xC, value 0xFFFF."

        // Decoy INFO lines that must NOT match.
        val infoLine1 = "09:21:7.0000 [INFO] sysbus: Machine started."
        val infoLine2 = "09:21:7.5000 [INFO] cpu: Executing at 0x08000000."

        // Ring buffer: decoys first, then the WARNING lines.
        val ringBuffer = listOf(
            infoLine1,
            infoLine2,
            renodeWarningShape1,
            renodeWarningShape2,
            renodeWarningShape3,
            renodeWarningShape4,
        )

        // Use the scan function extracted from FirmwareProcessHygiene.
        val firstPeripheral = scanRenodePeripheralLine(ringBuffer)
        assertNotNull(
            "scanRenodePeripheralLine must find the first WARNING line, got null",
            firstPeripheral,
        )
        assertEquals(
            "Must return the FIRST WARNING line (shape 1)",
            renodeWarningShape1,
            firstPeripheral,
        )

        // Renode-variant message.
        val timeoutSeconds = 30
        val renodeMsg = buildTimeoutDiagnostic(
            timeoutSeconds = timeoutSeconds,
            isRenode = true,
            peripheralLine = firstPeripheral,
        )
        assertTrue(
            "Renode message must contain 'did not reach console output': $renodeMsg",
            renodeMsg.contains("did not reach console output"),
        )
        assertTrue(
            "Renode message must contain 'blocking on unmodeled hardware': $renodeMsg",
            renodeMsg.contains("blocking on unmodeled hardware"),
        )
        assertTrue(
            "Renode message must contain the peripheral line verbatim: $renodeMsg",
            renodeMsg.contains(firstPeripheral!!),
        )

        // QEMU-variant message (isRenode=false, no peripheral clause).
        val qemuMsg = buildTimeoutDiagnostic(
            timeoutSeconds = timeoutSeconds,
            isRenode = false,
            peripheralLine = null,
        )
        assertTrue(
            "QEMU message must contain 'did not reach console output': $qemuMsg",
            qemuMsg.contains("did not reach console output"),
        )
        assertFalse(
            "QEMU message must NOT contain 'blocking on unmodeled hardware': $qemuMsg",
            qemuMsg.contains("blocking on unmodeled hardware"),
        )
        assertFalse(
            "QEMU message must NOT contain 'unimplemented peripheral': $qemuMsg",
            qemuMsg.contains("unimplemented peripheral"),
        )
    }

    // -------------------------------------------------------------------------
    // 4. hang_unmodeled live test (Renode only)
    // -------------------------------------------------------------------------

    fun testHangUnmodeledNoBootBanner() {
        val renodeBin = resolveRenode()
        if (renodeBin == null) {
            println("SKIP hang_unmodeled: renode not installed — skipped hang_unmodeled")
            return
        }

        val fixtureDir = projectRoot
            .resolve("ui-driver-tests/testData/fixtures/zephyr-corpus/apps/hang_unmodeled/nrf52840dk-Os")
        assertTrue("hang_unmodeled fixture not found", fixtureDir.isDirectory)

        val elfGz = fixtureDir.resolve("zephyr.elf.gz")
        assertTrue("hang_unmodeled ELF.gz not found", elfGz.exists())

        val elfFile = File.createTempFile("hexana-itest-hang-", ".elf")
        elfFile.deleteOnExit()
        GZIPInputStream(elfGz.inputStream()).use { gz ->
            elfFile.outputStream().use { out -> gz.copyTo(out) }
        }

        // Use nrf52840 library platform (same as nrf52840dk positive test).
        val libraryPlatform = "platforms/cpus/nrf52840.repl"
        val uartPort = java.net.ServerSocket(0).use { it.localPort }
        val resc = buildRescScript(
            replPath = null,
            libraryPlatform = libraryPlatform,
            elfPath = elfFile.absolutePath,
            uartPort = uartPort,
            uartPeripheral = "uart0",
            extraLines = emptyList(),
            gdbPort = null,
        )
        val rescFile = File.createTempFile("hexana-itest-hang-", ".resc")
        rescFile.deleteOnExit()
        rescFile.writeText(resc)

        val process = ProcessBuilder(renodeBin, "--disable-xwt", "--console", rescFile.absolutePath)
            .redirectErrorStream(true)
            .start()

        // The banner regex from the manifest.
        val bannerRegex = Regex("""\*\*\* Booting Zephyr OS build [^*]*\*\*\*""")
        // Expected peripheral warning address from manifest note.
        val expectedPeripheralAddress = "0x4002F000"

        val renodeOutput = StringBuilder()
        val bannerRef = java.util.concurrent.atomic.AtomicBoolean(false)
        val peripheralRef = java.util.concurrent.atomic.AtomicBoolean(false)

        // Read Renode output on a daemon thread so the 10s deadline is hard.
        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        synchronized(renodeOutput) { renodeOutput.appendLine(line) }
                        if (bannerRegex.containsMatchIn(line)) bannerRef.set(true)
                        if (line.contains("[WARNING]") && line.contains(expectedPeripheralAddress)) {
                            peripheralRef.set(true)
                        }
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) { }
        }
        readerThread.isDaemon = true
        readerThread.start()

        Thread.sleep(10_000L)
        killTree(process)
        readerThread.join(3_000)

        elfFile.delete()
        rescFile.delete()

        val bannerFound = bannerRef.get()
        val peripheralWarningFound = peripheralRef.get()

        assertFalse(
            "hang_unmodeled: banner MUST NOT appear — firmware is expected to hang.\nOutput:\n$renodeOutput",
            bannerFound,
        )
        assertTrue(
            "hang_unmodeled: expected at least one Renode [WARNING] line containing $expectedPeripheralAddress." +
                "\nOutput:\n$renodeOutput",
            peripheralWarningFound,
        )
    }
}


