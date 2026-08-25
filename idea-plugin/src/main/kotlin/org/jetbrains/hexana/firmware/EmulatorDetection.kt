package org.jetbrains.hexana.firmware

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.hexana.providers.firmware.QemuBinary
import org.jetbrains.hexana.providers.firmware.toCliName
import java.io.File

/**
 * Probes the host system for emulator and debugger executables.
 *
 * All methods that run an external process must be called off the EDT.
 * Callers are responsible for dispatching to a background thread (e.g.
 * [com.intellij.openapi.application.invokeLater] + coroutine on Dispatchers.IO,
 * or a [com.intellij.openapi.progress.Task.Backgroundable]).
 */
object EmulatorDetection {

    private val LOG = Logger.getInstance(EmulatorDetection::class.java)

    private const val PROBE_TIMEOUT_MS = 5_000
    private const val QEMU_MIN_MAJOR = 7
    private const val RENODE_MIN_MAJOR = 1
    private const val RENODE_MIN_MINOR = 15
    private const val GDB_MIN_MAJOR = 14

    /** A tool found on the system, with its parsed version and minimum-version result. */
    data class DetectedTool(
        val path: String,
        val version: String?,
        val meetsMinimum: Boolean,
    )

    // -------------------------------------------------------------------------
    // Public detection methods
    // -------------------------------------------------------------------------

    /**
     * Detects [binary] (`qemu-system-*`) on PATH and standard install locations.
     * Returns `null` if the binary is not found or does not respond to `--version`.
     * Must be called off EDT.
     */
    fun detectQemu(binary: QemuBinary): DetectedTool? {
        val name = binary.toCliName()
        val exe = if (SystemInfo.isWindows) "$name.exe" else name
        val candidates = buildList {
            // PATH first
            add(exe)
            if (SystemInfo.isMac) {
                add("/opt/homebrew/bin/$exe")
                add("/usr/local/bin/$exe")
                add("/usr/bin/$exe")
            } else if (!SystemInfo.isWindows) {
                add("/usr/bin/$exe")
                add("/usr/local/bin/$exe")
            }
        }
        for (candidate in candidates) {
            val resolved = if (File(candidate).isAbsolute) {
                if (File(candidate).exists()) candidate else continue
            } else {
                // bare name — rely on PATH at probe time
                candidate
            }
            val output = runVersion(resolved) ?: continue
            val firstLine = output.lines().firstOrNull() ?: continue
            val version = parseQemuVersion(firstLine)
            val meets = version?.let { meetsQemuMinimum(it) } ?: false
            return DetectedTool(path = resolved, version = version, meetsMinimum = meets)
        }
        return null
    }

    /**
     * Detects Renode on PATH, standard install locations, and the plugin download directory.
     * Must be called off EDT.
     */
    fun detectRenode(): DetectedTool? {
        val exe = if (SystemInfo.isWindows) "renode.exe" else "renode"
        val candidates = buildList {
            add(exe) // PATH
            if (SystemInfo.isMac) {
                add("/opt/homebrew/bin/renode")
                add("/usr/local/bin/renode")
                add("/Applications/Renode.app/Contents/MacOS/Renode")
            } else if (!SystemInfo.isWindows) {
                add("/usr/bin/renode")
                add("/usr/local/bin/renode")
            }
            // plugin download directory
            val downloadDir = HexanaFirmwareSettings.getInstance().effectiveRenodeDownloadDir()
            val inDownload = guessRenodeExeInDir(downloadDir)
            if (inDownload != null) add(inDownload)
        }
        for (candidate in candidates) {
            val exists = if (File(candidate).isAbsolute) File(candidate).exists() else true
            if (!exists) continue
            val output = runVersion(candidate, "--version") ?: continue
            val firstLine = output.lines().firstOrNull() ?: continue
            val version = parseRenodeVersion(firstLine)
            val meets = version?.let { meetsRenodeMinimum(it) } ?: false
            return DetectedTool(path = candidate, version = version, meetsMinimum = meets)
        }
        return null
    }

    /**
     * Detects a DAP-capable GDB on the host system.
     *
     * Probe order:
     * 1. [HexanaFirmwareSettings.effectiveGdbPath] override.
     * 2. `${'$'}ZEPHYR_SDK_INSTALL_DIR/gnu/<arch>/bin/arm-zephyr-eabi-gdb-py` (glob).
     * 3. `~/zephyr-sdk-<ver>/gnu/<arch>/bin/arm-zephyr-eabi-gdb-py` (glob).
     * 4. `arm-none-eabi-gdb-py`, `arm-none-eabi-gdb`, `gdb-multiarch`, `riscv64-elf-gdb`, `gdb` on PATH.
     *
     * Validation: version >= 14 AND `--batch -ex "python print(1)"` exits 0 printing "1".
     *
     * Returns `null` if no suitable GDB is found.
     * Must be called off EDT.
     */
    fun detectGdb(): DetectedTool? {
        val candidates = buildList<String> {
            val settings = HexanaFirmwareSettings.getInstance()
            val override = settings.effectiveGdbPath()
            if (override != null) add(override)

            // Explicit Zephyr SDK path from settings (highest-priority SDK probe).
            val configuredSdkPath = settings.effectiveZephyrSdkPath()
            if (configuredSdkPath != null) addZephyrSdkGdbCandidates(this, File(configuredSdkPath))

            val sdkEnvDir = System.getenv("ZEPHYR_SDK_INSTALL_DIR")
            if (sdkEnvDir != null) addZephyrSdkGdbCandidates(this, File(sdkEnvDir))

            val homeDir = File(System.getProperty("user.home"))
            homeDir.listFiles { f -> f.isDirectory && f.name.startsWith("zephyr-sdk-") }
                ?.forEach { sdkDir -> addZephyrSdkGdbCandidates(this, sdkDir) }

            // Plugin-managed per-arch toolchain downloads (ZephyrToolchainDownloader installs here).
            val pluginBase = com.intellij.openapi.application.PathManager.getSystemPath()
            val pluginToolchainRoot = File("$pluginBase/hexana-firmware")
            pluginToolchainRoot.listFiles { f -> f.isDirectory && f.name.startsWith("zephyr-toolchain-") }
                ?.forEach { toolchainDir -> addBareToolchainGdbCandidates(this, toolchainDir) }

            add("arm-none-eabi-gdb-py")
            add("arm-none-eabi-gdb")
            add("gdb-multiarch")
            add("riscv64-elf-gdb")
            add("gdb")
        }

        for (candidate in candidates) {
            if (File(candidate).isAbsolute && !File(candidate).exists()) continue

            val versionOutput = runVersion(candidate, "--version") ?: continue
            val firstLine = versionOutput.lines().firstOrNull() ?: continue
            val parsed = parseGdbVersion(firstLine) ?: continue
            val (_, versionStr) = parsed
            val numericVersion = versionStr.split("-").first().split(".").take(2).joinToString(".")
            if (!meetsGdbMinimum(numericVersion)) continue

            val pythonOk = try {
                val cmd = GeneralCommandLine(candidate, "--batch", "-ex", "python print(1)")
                val handler = CapturingProcessHandler(cmd)
                val result = handler.runProcess(PROBE_TIMEOUT_MS)
                result.exitCode == 0 && result.stdout.trim().contains("1")
            } catch (_: Exception) { false }

            if (!pythonOk) {
                LOG.debug("GDB at $candidate has version $numericVersion >= 14 but Python probe failed; skipping")
                continue
            }

            return DetectedTool(path = candidate, version = numericVersion, meetsMinimum = true)
        }
        return null
    }

    /**
     * Adds `*-gdb-py` candidates from an SDK root that contains a `gnu/` sub-directory.
     * Layout: `<sdkDir>/gnu/<target>/bin/<target>-gdb-py`.
     */
    private fun addZephyrSdkGdbCandidates(list: MutableList<String>, sdkDir: File) {
        val gnuDir = File(sdkDir, "gnu")
        if (!gnuDir.isDirectory) return
        gnuDir.listFiles()?.forEach { archDir ->
            addBareToolchainGdbCandidates(list, archDir)
        }
    }

    /**
     * Adds `*-gdb-py` candidates from a bare per-target toolchain directory.
     *
     * Tolerates two extraction layouts observed in Zephyr SDK v1.0.1:
     * - Flat:   `<toolchainDir>/bin/<target>-gdb-py`
     * - Nested: `<toolchainDir>/<target>/bin/<target>-gdb-py`
     *           (seen when the tarball extracts with an extra top-level dir)
     */
    private fun addBareToolchainGdbCandidates(list: MutableList<String>, toolchainDir: File) {
        if (!toolchainDir.isDirectory) return
        // Flat: bin/ directly under toolchainDir
        File(toolchainDir, "bin").listFiles()?.filter { it.name.endsWith("-gdb-py") }
            ?.forEach { list.add(it.absolutePath) }
        // Nested: one sub-directory level before bin/
        toolchainDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
            File(subDir, "bin").listFiles()?.filter { it.name.endsWith("-gdb-py") }
                ?.forEach { list.add(it.absolutePath) }
        }
    }

    // -------------------------------------------------------------------------
    // Pure version parsers (top-level, trivially testable — no process I/O)
    // -------------------------------------------------------------------------

    /**
     * Parses the QEMU version from the first line of `qemu-system-* --version`.
     *
     * Accepted formats:
     * - `QEMU emulator version 9.2.2 (Homebrew)`
     * - `QEMU emulator version 8.2.0 (Debian 1:8.2.0+ds-1)`
     * - `QEMU emulator version 7.0.0`
     *
     * Returns the dotted numeric version token (e.g. `"9.2.2"`), or `null` on mismatch.
     */
    fun parseQemuVersion(firstLine: String): String? {
        val match = Regex("""QEMU emulator version (\d+\.\d+(?:\.\d+)?)""").find(firstLine)
        return match?.groupValues?.get(1)
    }

    /**
     * Parses the Renode version from the first output line of `renode --version`.
     *
     * Accepted formats:
     * - `Renode, version 1.16.1.12345 (commit abc...)`
     * - `Renode v1.16.1`
     * - Any line containing a semver-like token `M.m.p` — lenient.
     *
     * Returns the M.m.p prefix (e.g. `"1.16.1"`), or `null` on mismatch.
     */
    fun parseRenodeVersion(line: String): String? {
        // Prefer "version M.m.p" or "vM.m.p" first
        val canonical = Regex("""[Vv](?:ersion\s+)?(\d+\.\d+\.\d+)""").find(line)
        if (canonical != null) return canonical.groupValues[1]
        // Fallback: first bare semver-like token in the line
        val fallback = Regex("""\b(\d+\.\d+\.\d+)""").find(line)
        return fallback?.groupValues?.get(1)
    }

    /**
     * Parses the GDB distributor and version from the first line of `gdb --version`.
     *
     * Accepted formats:
     * - `GNU gdb (Arm GNU Toolchain 13.3.Rel1 (Build arm-13.24)) 14.2.90.20240531-git`
     * - `GNU gdb (Ubuntu 12.1-0ubuntu1~22.04) 12.1`
     *
     * Returns `(distributor, version)` where distributor is the content of the
     * parenthesised field and version is the trailing numeric token, or `null` on mismatch.
     */
    fun parseGdbVersion(firstLine: String): Pair<String, String>? {
        // Handle nested parens: e.g. "GNU gdb (Arm GNU Toolchain 13.3.Rel1 (Build arm-13.24)) 14.2.90…"
        // Match the outermost () by finding the last ")" before the version number.
        val match = Regex("""^GNU gdb \((.+)\) ([\d.]+)""").find(firstLine.trim())
            ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /**
     * Parses the LLDB version from the first line of `lldb --version`.
     *
     * Accepted formats:
     * - `lldb version 17.0.6`
     * - `lldb-1500.0.404.7` (Apple LLDB)
     *
     * Returns the version string, or `null` on mismatch.
     */
    fun parseLldbVersion(firstLine: String): String? {
        // LLVM upstream: "lldb version M.m.p"
        val upstream = Regex("""lldb version (\d+\.\d+(?:\.\d+)?)""").find(firstLine)
        if (upstream != null) return upstream.groupValues[1]
        // Apple: "lldb-NNNN.M.P.Q" or "Apple Swift version … (swiftlang-… clang-…)"
        val apple = Regex("""lldb-(\d+\.\d+\.\d+(?:\.\d+)?)""").find(firstLine)
        return apple?.groupValues?.get(1)
    }

    // -------------------------------------------------------------------------
    // Version comparison helpers
    // -------------------------------------------------------------------------

    /** Compares two dotted numeric version strings. Returns negative/zero/positive. */
    fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").mapNotNull { it.toIntOrNull() }
        val bParts = b.split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val aVal = aParts.getOrElse(i) { 0 }
            val bVal = bParts.getOrElse(i) { 0 }
            if (aVal != bVal) return aVal - bVal
        }
        return 0
    }

    /** Returns `true` if [version] is `>= 7.0`. */
    fun meetsQemuMinimum(version: String): Boolean =
        compareVersions(version, "$QEMU_MIN_MAJOR.0") >= 0

    /** Returns `true` if [version] is `>= 1.15`. */
    fun meetsRenodeMinimum(version: String): Boolean =
        compareVersions(version, "$RENODE_MIN_MAJOR.$RENODE_MIN_MINOR") >= 0

    /** Returns `true` if [gdbVersion] is `>= 14`. */
    fun meetsGdbMinimum(gdbVersion: String): Boolean =
        compareVersions(gdbVersion, "$GDB_MIN_MAJOR.0") >= 0

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Runs `<exe> --version` (or `<exe> [flag]`) with a 5-second timeout and
     * returns stdout, or `null` on failure/timeout.
     * Must be called off EDT.
     */
    private fun runVersion(exe: String, flag: String = "--version"): String? {
        return try {
            val cmd = GeneralCommandLine(exe, flag)
            val handler = CapturingProcessHandler(cmd)
            val result = handler.runProcess(PROBE_TIMEOUT_MS)
            if (result.isTimeout || result.exitCode != 0 && result.stdout.isBlank()) null
            else (result.stdout + result.stderr).trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            LOG.debug("Version probe failed for $exe: ${e.message}")
            null
        }
    }

    /**
     * Given a directory path, returns the most likely Renode executable path
     * inside it, or `null` if the directory doesn't exist.
     */
    private fun guessRenodeExeInDir(dir: String): String? {
        val d = File(dir)
        if (!d.isDirectory) return null
        // macOS portable: Renode.app/Contents/MacOS/Renode inside the extracted dir
        val macApp = File(d, "Renode.app/Contents/MacOS/Renode")
        if (macApp.exists()) return macApp.absolutePath
        // Linux/Windows portable: renode or renode.exe at root
        val linux = File(d, "renode")
        if (linux.exists()) return linux.absolutePath
        val win = File(d, "renode.exe")
        if (win.exists()) return win.absolutePath
        return null
    }
}
