package org.jetbrains.hexana.firmware.itest

import com.intellij.testFramework.UsefulTestCase
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Process-hygiene integration test.
 *
 * Boots the qemu_cortex_m3 corpus ELF, waits 1 second (mid-boot), then destroys
 * the process tree using the same path production code uses (ProcessHandle descendants +
 * destroyForcibly). Then polls up to 5 seconds asserting that the process AND all its
 * recorded descendants are gone.
 *
 * This test asserts on real PIDs, not mere absence of exceptions. It must be able to fail:
 * if QEMU leaves a child alive after tree-kill, the assertion fails explicitly.
 *
 * Guard: skipped loudly when qemu-system-arm is absent.
 */
class FirmwareProcessHygieneTest : UsefulTestCase() {

    private val qemuBinary = "/opt/homebrew/bin/qemu-system-arm"

    private val projectRoot: File by lazy {
        generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { it.resolve("idea-plugin").isDirectory }
            ?: error("Cannot locate project root from ${File(".").canonicalPath}")
    }

    fun testProcessTreeKillLeavesNoOrphans() {
        if (!File(qemuBinary).isFile) {
            println("SKIP FirmwareProcessHygieneTest: $qemuBinary not found")
            return
        }

        val elfGz = projectRoot.resolve(
            "ui-driver-tests/testData/fixtures/zephyr-corpus/apps/hello_world/qemu_cortex_m3-Os/zephyr.elf.gz"
        )
        assertTrue("corpus ELF.gz not found: ${elfGz.path}", elfGz.exists())

        val elfFile = File.createTempFile("hexana-hygiene-", ".elf")
        elfFile.deleteOnExit()
        GZIPInputStream(elfGz.inputStream()).use { gz ->
            elfFile.outputStream().use { out -> gz.copyTo(out) }
        }

        // Launch QEMU — same command as the smoke test (matches production QemuCommandLineState).
        val process = ProcessBuilder(
            qemuBinary,
            "-machine", "lm3s6965evb",
            "-cpu", "cortex-m3",
            "-kernel", elfFile.absolutePath,
            "-nographic",
            "-serial", "mon:stdio",
        )
            .redirectErrorStream(true)
            .start()

        val qemuPid = process.pid()

        // Record all descendants at this moment. In practice QEMU has none for this target,
        // but we enumerate to cover any spawned helpers.
        val descendantPids: List<Long>
        try {
            // Wait 1s so QEMU is mid-boot — ensures the process is alive and running.
            Thread.sleep(1_000)

            assertTrue(
                "QEMU process (pid=$qemuPid) must be alive after 1s",
                process.isAlive,
            )

            // Snapshot descendants before kill.
            descendantPids = process.toHandle().descendants()
                .mapToLong { it.pid() }
                .boxed()
                .toList()

            // Kill the tree — same path as production FirmwareProcessHygiene.
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        } finally {
            elfFile.delete()
        }

        // Poll up to 5s asserting both the parent and all recorded descendants are gone.
        val allPids = listOf(qemuPid) + descendantPids
        val deadline = System.currentTimeMillis() + 5_000L

        var allGone = false
        while (System.currentTimeMillis() < deadline) {
            val anyAlive = allPids.any { pid ->
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            }
            if (!anyAlive) {
                allGone = true
                break
            }
            Thread.sleep(100)
        }

        // Final assertion: each PID must be absent (isEmpty means the OS reclaimed it).
        for (pid in allPids) {
            val handle = ProcessHandle.of(pid)
            val alive = handle.map { it.isAlive }.orElse(false)
            assertFalse(
                "Process/descendant pid=$pid is still alive after tree-kill + 5s poll",
                alive,
            )
        }

        assertTrue(
            "Some process(es) in PIDs $allPids were still alive after 5s",
            allGone,
        )
    }
}
