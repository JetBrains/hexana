package org.jetbrains.hexana.firmware.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.providers.firmware.ElfHeaderFacts
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.providers.firmware.buildQemuCommandLine
import org.jetbrains.hexana.providers.firmware.detectTargetProfile
import org.jetbrains.hexana.providers.firmware.selectBackend
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Local smoke test: gunzips the qemu_cortex_m3 corpus ELF, launches it via the
 * generated command line, waits up to 15 seconds for the Zephyr boot banner, and
 * asserts it appears.
 *
 * Guards with [org.junit.Assume] so it is a loud skip when qemu-system-arm is absent.
 * This test validates the command-line end-to-end without IntelliJ services.
 */
class QemuSmokeTest : UsefulTestCase() {

    private val qemuBinary = "/opt/homebrew/bin/qemu-system-arm"

    private val bootBannerRegex = Regex("""\*\*\* Booting Zephyr OS build [^*]*\*\*\*""")

    fun testQemuCortexM3BootsBanner() {
        // Guard: skip when qemu-system-arm is not present.
        if (!File(qemuBinary).isFile) {
            System.out.println("SKIP: $qemuBinary not found — skipping QEMU smoke test")
            return
        }

        // Locate the corpus ELF.gz.
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { it.resolve("idea-plugin").isDirectory }
            ?: error("Cannot locate project root from ${File(".").canonicalPath}")
        val elfGz = root.resolve(
            "ui-driver-tests/testData/fixtures/zephyr-corpus/apps/hello_world/qemu_cortex_m3-Os/zephyr.elf.gz"
        )
        assertTrue("corpus ELF.gz not found: ${elfGz.path}", elfGz.exists())

        // Gunzip to a temp file.
        val elfFile = File.createTempFile("hexana-smoke-qemu_cortex_m3-", ".elf")
        elfFile.deleteOnExit()
        GZIPInputStream(elfGz.inputStream()).use { gz ->
            FileOutputStream(elfFile).use { out -> gz.copyTo(out) }
        }

        // Build command line via real config+dts fixtures.
        val dataDir = root.resolve("idea-plugin/src/test/data/firmware")
        val configText = dataDir.resolve("qemu_cortex_m3.config").readText()
        val dtsText = dataDir.resolve("qemu_cortex_m3.dts").readText()
        val header = ElfHeaderFacts(machine = 40, is64Bit = false, flags = 0)
        val profile = detectTargetProfile(header, dtsText, configText)
        val selection = selectBackend(profile) as BackendSelection.Ok
        val args = buildQemuCommandLine(selection, elfFile.absolutePath, emptyList())

        // args[0] is the binary name; replace with absolute path.
        val cmd = mutableListOf(qemuBinary) + args.drop(1)

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        var bannerFound = false
        val deadline = System.currentTimeMillis() + 15_000L

        try {
            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null && System.currentTimeMillis() < deadline) {
                    output.appendLine(line)
                    if (bootBannerRegex.containsMatchIn(line)) {
                        bannerFound = true
                        break
                    }
                    line = reader.readLine()
                }
            }
        } finally {
            process.destroyForcibly()
            elfFile.delete()
        }

        assertTrue(
            "Boot banner not found within 15s. Output:\n$output",
            bannerFound,
        )
    }
}
