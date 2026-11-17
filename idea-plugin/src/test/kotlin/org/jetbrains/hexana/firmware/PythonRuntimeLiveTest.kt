package org.jetbrains.hexana.firmware

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.Socket

/**
 * Live integration test for [PythonRuntimeDownloader].
 *
 * Downloads the REAL macOS-aarch64 CPython asset from GitHub (or reuses a cached copy),
 * verifies the SHA-256, extracts it, and runs a real dts2repl conversion, then compares
 * the output against the system python3.
 *
 * Guard: skips automatically when offline (cannot connect to github.com:443).
 *
 * Cache directory: `~/hexana-tools/python-cache` (reused across runs) or /tmp/hexana-python-cache.
 *
 * Run standalone:
 * ```
 * ./gradlew :idea-plugin:test --tests "org.jetbrains.hexana.firmware.PythonRuntimeLiveTest"
 * ```
 */
class PythonRuntimeLiveTest {

    companion object {
        private val cacheDir: File = run {
            val home = File(System.getProperty("user.home"))
            val preferred = home.resolve("hexana-tools/python-cache")
            if (preferred.parentFile.isDirectory) preferred.also { it.mkdirs() }
            else File(System.getProperty("java.io.tmpdir"), "hexana-python-cache").also { it.mkdirs() }
        }

        private var cachedPythonExe: String? = null
        private var skipReason: String? = null

        @JvmStatic
        @BeforeClass
        fun acquirePython() {
            // Guard: offline check
            val online = try {
                Socket("github.com", 443).use { true }
            } catch (_: Exception) {
                false
            }
            if (!online) {
                skipReason = "offline (cannot reach github.com:443)"
                return
            }

            // Only run on macOS aarch64 for CI speed
            val arch = System.getProperty("os.arch") ?: ""
            val isMacAarch64 = (System.getProperty("os.name") ?: "").contains("mac", ignoreCase = true) &&
                (arch == "aarch64" || arch.contains("arm64", ignoreCase = true))
            if (!isMacAarch64) {
                skipReason = "live test only runs on macOS aarch64 (current: $arch)"
                return
            }

            val filename =
                "cpython-${PythonRuntimeDownloader.PINNED_PYTHON_VERSION}+" +
                    "${PythonRuntimeDownloader.PINNED_PBS_RELEASE}-aarch64-apple-darwin-install_only_stripped.tar.gz"
            val url = "https://github.com/astral-sh/python-build-standalone/releases/download/" +
                "${PythonRuntimeDownloader.PINNED_PBS_RELEASE}/$filename"
            val expectedSha = "dd5b76ab11451a4a4367c17c61d944dded56b425396b07f102922a7ebef7d55f"

            val installDir = cacheDir.resolve("python-${PythonRuntimeDownloader.PINNED_PYTHON_VERSION}")

            // Check if already extracted
            if (installDir.isDirectory) {
                val existing = PythonRuntimeDownloader.locatePython(installDir.toPath())
                if (existing != null) {
                    cachedPythonExe = existing
                    println("[PythonRuntimeLiveTest] Reusing cached python at $existing")
                    return
                }
            }

            // Download
            println("[PythonRuntimeLiveTest] Downloading $url (~24 MB)...")
            val startMs = System.currentTimeMillis()
            val archive = cacheDir.resolve("$filename.tmp")
            try {
                java.net.URL(url).openStream().use { input ->
                    archive.outputStream().use { out -> input.copyTo(out) }
                }
                val elapsed = System.currentTimeMillis() - startMs
                println("[PythonRuntimeLiveTest] Downloaded in ${elapsed}ms")
            } catch (e: Exception) {
                skipReason = "download failed: ${e.message}"
                return
            }

            // Verify SHA-256
            val actualSha = run {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                archive.inputStream().use { stream ->
                    val buf = ByteArray(65536)
                    var n = stream.read(buf)
                    while (n >= 0) { md.update(buf, 0, n); n = stream.read(buf) }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            assertEquals("SHA-256 mismatch for downloaded CPython asset", expectedSha, actualSha)
            println("[PythonRuntimeLiveTest] SHA-256 verified OK: $actualSha")

            // Extract
            installDir.deleteRecursively()
            installDir.mkdirs()
            com.intellij.util.io.Decompressor.Tar(archive.toPath()).extract(installDir.toPath())
            archive.delete()
            println("[PythonRuntimeLiveTest] Extracted to $installDir")

            val exe = PythonRuntimeDownloader.locatePython(installDir.toPath())
            assertNotNull("locatePython must find interpreter after extraction", exe)
            cachedPythonExe = exe
            println("[PythonRuntimeLiveTest] Python interpreter: $exe")
        }
    }

    @Test fun pythonVersionCheck() {
        val reason = skipReason
        assumeTrue("Skipping: $reason", reason == null)
        val pythonExe = cachedPythonExe ?: error("no python exe")

        val proc = ProcessBuilder(pythonExe, "--version").redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        println("[PythonRuntimeLiveTest] $pythonExe --version => $output")
        assertTrue("Expected 'Python 3.12.x', got: $output", output.startsWith("Python 3.12."))
    }

    @Test fun dts2replConversionWithDownloadedPython() {
        val reason = skipReason
        assumeTrue("Skipping: $reason", reason == null)
        val pythonExe = cachedPythonExe ?: error("no python exe")

        // Locate the zephyr.dts fixture
        val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { it.resolve("idea-plugin").isDirectory }
            ?: error("Cannot locate project root")
        val dtsFile = projectRoot.resolve(
            "ui-driver-tests/testData/fixtures/zephyr-corpus/apps/hello_world/nucleo_f429zi-Os/zephyr.dts"
        )
        assumeTrue("nucleo_f429zi-Os/zephyr.dts fixture not found", dtsFile.exists())

        // Acquire dts2repl — accept managed install or HEXANA_DTS2REPL_PATH env override.
        val dts2replBase = run {
            val managed = Dts2ReplRunner.managedInstallDir().toFile()
            if (Dts2ReplRunner.isInstalled(managed.toPath())) return@run managed
            val env = System.getenv("HEXANA_DTS2REPL_PATH")
            if (env != null) { val f = File(env); if (f.isDirectory) return@run f }
            null
        }
        assumeTrue(
            "dts2repl not installed — set HEXANA_DTS2REPL_PATH or run the boot matrix test first",
            dts2replBase != null,
        )
        requireNotNull(dts2replBase)
        val archiveRoot = dts2replBase.listFiles()?.firstOrNull { it.isDirectory } ?: dts2replBase

        // Run with downloaded CPython; `-o -` directs output to stdout.
        val startMs = System.currentTimeMillis()
        val proc = ProcessBuilder(
            pythonExe, "-m", "dts2repl", "-o", "-", dtsFile.absolutePath,
        ).apply {
            environment()["PYTHONPATH"] = archiveRoot.absolutePath
        }.redirectErrorStream(false).start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        val elapsed = System.currentTimeMillis() - startMs
        println("[PythonRuntimeLiveTest] dts2repl conversion: exit=$exitCode, ${elapsed}ms")

        assertTrue(
            "dts2repl conversion with downloaded python failed (exit=$exitCode).\nstderr:\n$stderr",
            exitCode == 0 && stdout.isNotBlank(),
        )
        assertTrue(
            "Expected .repl output to contain a 'cpu' line, got:\n${stdout.take(500)}",
            stdout.contains("cpu", ignoreCase = true),
        )
        println("[PythonRuntimeLiveTest] Conversion output (first 300 chars):\n${stdout.take(300)}")

        // Compare with system python3 if available
        val systemPython = try {
            val p = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
            val ver = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (ver.startsWith("Python 3.")) "python3" else null
        } catch (_: Exception) {
            null
        }

        if (systemPython != null) {
            println("[PythonRuntimeLiveTest] Comparing against system $systemPython...")
            val sysproc = ProcessBuilder(
                systemPython, "-m", "dts2repl", "-o", "-", dtsFile.absolutePath,
            ).apply {
                environment()["PYTHONPATH"] = archiveRoot.absolutePath
            }.redirectErrorStream(false).start()
            val sysStdout = sysproc.inputStream.bufferedReader().readText()
            sysproc.waitFor()

            val normalize = { s: String -> s.lines().map { it.trimEnd() }.joinToString("\n").trimEnd() }
            val normalized = normalize(stdout)
            val sysNormalized = normalize(sysStdout)
            if (normalized == sysNormalized) {
                println("[PythonRuntimeLiveTest] CPython diff verdict: EQUAL")
            } else {
                println("[PythonRuntimeLiveTest] CPython diff verdict: DIFFER (whitespace-normalized)")
                println("  Downloaded output length: ${stdout.length}")
                println("  System output length: ${sysStdout.length}")
            }
        } else {
            println("[PythonRuntimeLiveTest] System python3 not found — skipping diff comparison")
        }
    }
}
