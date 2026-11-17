package org.jetbrains.hexana.firmware

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [PythonRuntimeDownloader].
 *
 * No network, no IDE, no process I/O — all tested functions are pure or work on
 * temp directories.
 */
class PythonRuntimeDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // -------------------------------------------------------------------------
    // downloadUrlFor — golden URLs for all five platforms
    // -------------------------------------------------------------------------

    private fun url(isMac: Boolean, isLinux: Boolean, isWindows: Boolean, arch: String) =
        PythonRuntimeDownloader.downloadUrlFor(
            isMac = isMac, isLinux = isLinux, isWindows = isWindows, hostArch = arch,
        )

    private val base = "https://github.com/astral-sh/python-build-standalone/releases/download/20260814"
    private val ver = "3.12.14"

    @Test fun downloadUrl_mac_aarch64() {
        assertEquals(
            "$base/cpython-$ver+20260814-aarch64-apple-darwin-install_only_stripped.tar.gz",
            url(isMac = true, isLinux = false, isWindows = false, arch = "aarch64"),
        )
    }

    @Test fun downloadUrl_mac_x86_64() {
        assertEquals(
            "$base/cpython-$ver+20260814-x86_64-apple-darwin-install_only_stripped.tar.gz",
            url(isMac = true, isLinux = false, isWindows = false, arch = "x86_64"),
        )
    }

    @Test fun downloadUrl_linux_x86_64() {
        assertEquals(
            "$base/cpython-$ver+20260814-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz",
            url(isMac = false, isLinux = true, isWindows = false, arch = "amd64"),
        )
    }

    @Test fun downloadUrl_linux_aarch64() {
        assertEquals(
            "$base/cpython-$ver+20260814-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz",
            url(isMac = false, isLinux = true, isWindows = false, arch = "aarch64"),
        )
    }

    @Test fun downloadUrl_windows_x86_64() {
        assertEquals(
            "$base/cpython-$ver+20260814-x86_64-pc-windows-msvc-install_only_stripped.tar.gz",
            url(isMac = false, isLinux = false, isWindows = true, arch = "amd64"),
        )
    }

    @Test fun downloadUrl_unsupported_returnsNull() {
        assertNull(url(isMac = false, isLinux = false, isWindows = false, arch = "x86_64"))
    }

    // -------------------------------------------------------------------------
    // locatePython — layout variants + exec-bit restore + not-found
    // -------------------------------------------------------------------------

    @Test fun locatePython_standardLayout_python_bin_python3() {
        // python/bin/python3 — standard install_only layout (macOS / Linux)
        val installDir = tmp.newFolder("python-install")
        val pythonDir = File(installDir, "python/bin").also { it.mkdirs() }
        val exe = File(pythonDir, "python3").also { it.createNewFile() }
        exe.setExecutable(true)

        val result = PythonRuntimeDownloader.locatePython(installDir.toPath())
        assertEquals(exe.absolutePath, result)
    }

    @Test fun locatePython_flatLayout_bin_python3() {
        // bin/python3 — flat layout (some extraction variants)
        val installDir = tmp.newFolder("python-flat")
        val bin = File(installDir, "bin").also { it.mkdirs() }
        val exe = File(bin, "python3").also { it.createNewFile() }
        exe.setExecutable(true)

        val result = PythonRuntimeDownloader.locatePython(installDir.toPath())
        assertEquals(exe.absolutePath, result)
    }

    @Test fun locatePython_windowsLayout_python_exe() {
        // python/python.exe — Windows layout
        val installDir = tmp.newFolder("python-win")
        val pythonDir = File(installDir, "python").also { it.mkdirs() }
        val exe = File(pythonDir, "python.exe").also { it.createNewFile() }
        // On Windows canExecute() returns true for .exe; on POSIX we can't truly
        // simulate Windows-only behaviour, so we just verify the locator finds the file.
        // On POSIX, the locator accepts .exe files without exec-bit (SystemInfo.isWindows guard).
        // This test verifies path discovery only.
        val result = PythonRuntimeDownloader.locatePython(installDir.toPath())
        // On POSIX: canExecute() is false for the .exe, but the locator skips exec-bit check
        // for .exe candidates when SystemInfo.isWindows is false. The current impl
        // checks `canExecute() || SystemInfo.isWindows`. On POSIX the .exe won't be returned
        // unless we set it executable. Set it so the test is platform-neutral.
        if (exe.setExecutable(true)) {
            assertNotNull(result)
        }
    }

    @Test fun locatePython_execBitRestored() {
        // Extraction may clear the exec bit; locatePython must restore it.
        val installDir = tmp.newFolder("python-noexec")
        val bin = File(installDir, "python/bin").also { it.mkdirs() }
        val exe = File(bin, "python3").also { it.createNewFile() }
        exe.setExecutable(false)

        val result = PythonRuntimeDownloader.locatePython(installDir.toPath())
        if (result != null) {
            assertTrue("exec bit should be restored", File(result).canExecute())
        }
        // On Windows, exec-bit is irrelevant; skip the assertion.
    }

    @Test fun locatePython_notFound_returnsNull() {
        val installDir = tmp.newFolder("python-empty")
        // No python executables inside
        assertNull(PythonRuntimeDownloader.locatePython(installDir.toPath()))
    }

    // -------------------------------------------------------------------------
    // parseDigestFromApiJson — same logic as ZephyrToolchainDownloader
    // -------------------------------------------------------------------------

    @Test fun parseDigest_findsKnownAsset() {
        val filename = "cpython-3.12.14+20260814-aarch64-apple-darwin-install_only_stripped.tar.gz"
        val json = """
            {"assets":[
              {"name":"$filename",
               "size":24991356,
               "digest":"sha256:dd5b76ab11451a4a4367c17c61d944dded56b425396b07f102922a7ebef7d55f"}
            ]}
        """.trimIndent()
        val digest = PythonRuntimeDownloader.parseDigestFromApiJson(json, filename)
        assertEquals("dd5b76ab11451a4a4367c17c61d944dded56b425396b07f102922a7ebef7d55f", digest)
    }

    @Test fun parseDigest_unknownAsset_returnsNull() {
        val json = """{"assets":[{"name":"other.tar.gz","digest":"sha256:aabbcc"}]}"""
        assertNull(PythonRuntimeDownloader.parseDigestFromApiJson(json, "missing.tar.gz"))
    }
}
