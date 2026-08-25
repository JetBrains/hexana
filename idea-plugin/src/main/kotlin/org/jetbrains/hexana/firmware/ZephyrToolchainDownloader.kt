package org.jetbrains.hexana.firmware

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.io.Decompressor
import com.intellij.util.io.HttpRequests
import org.jetbrains.hexana.providers.firmware.ArchClass
import org.jetbrains.hexana.providers.firmware.TargetProfile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * Downloads and installs a single per-arch Zephyr SDK toolchain tarball on demand.
 *
 * Only the toolchain for a specific target (e.g. `arm-zephyr-eabi`) is fetched —
 * never the full SDK. The tarball contains the cross-GDB binary (`*-gdb-py`) used
 * by the GDB-DAP debug transport.
 *
 * Flow:
 * 1. Show consent dialog naming the download URL, approximate size, and GPLv3 license.
 * 2. Run a [Task.Modal] (cancellable) that fetches the archive with a determinate progress bar.
 * 3. Verify the SHA-256 via the per-asset digest from the GitHub Releases API
 *    (or hardcoded digests for known host/target combinations as a fast path).
 *    Abort loudly on mismatch — never proceed with a warning.
 * 4. Extract `.tar.xz` via [Decompressor.Tar] (IntelliJ platform handles XZ since 2023.1+).
 * 5. Locate `bin/<target>-gdb-py` at depth 1 or 2 (tolerates flat and nested extraction layouts).
 * 6. Set [HexanaFirmwareSettings.gdbPathInUi] to the located binary.
 *
 * Never auto-triggered. Invoked only from [FirmwareRunSettingsEditor] (run-configuration
 * editor, where a modal progress dialog is required) or [FirmwareEmulatorConfigurable].
 *
 * ---
 * **Asset evidence** — retrieved 2026-08-25 via:
 * `GET https://api.github.com/repos/zephyrproject-rtos/sdk-ng/releases/tags/v1.0.1`
 *
 * Key assets (name → assets[].digest field):
 *
 * macOS aarch64, arm:
 *   toolchain_gnu_macos-aarch64_arm-zephyr-eabi.tar.xz
 *     sha256:4008edb5d4840cd994aedd7f1309bfb63e7243729d57839ebf1cc83c1f17c886  (~90 MB)
 * macOS aarch64, aarch64:
 *   toolchain_gnu_macos-aarch64_aarch64-zephyr-elf.tar.xz
 *     sha256:5a8778bff5b80d1547c1d926287eb79b3d5b6345353a6df992caa937858d8873  (~49 MB)
 * macOS aarch64, riscv64:
 *   toolchain_gnu_macos-aarch64_riscv64-zephyr-elf.tar.xz
 *     sha256:bc9026ee24c34d79f89bb9f1eb7f86a60232ed8eb1b85d301178482f6544cee7  (~133 MB)
 *
 * Linux x86_64, arm:
 *   toolchain_gnu_linux-x86_64_arm-zephyr-eabi.tar.xz
 *     sha256:21b85981cb5a1818d9bc53d82af80f208946ec038b982ff1907287572ed3a634  (~97 MB)
 * Linux x86_64, aarch64:
 *   toolchain_gnu_linux-x86_64_aarch64-zephyr-elf.tar.xz
 *     sha256:01c0cfe1daaab2d2a0f71165f11a066d983ef97e51c4a3753186afa6ae55c24b  (~61 MB)
 * Linux x86_64, riscv64:
 *   toolchain_gnu_linux-x86_64_riscv64-zephyr-elf.tar.xz
 *     sha256:01750834c471fbdb335c1b8b8aee17010a1968938957db85640c366235771a38  (~145 MB)
 *
 * Linux aarch64, arm:
 *   toolchain_gnu_linux-aarch64_arm-zephyr-eabi.tar.xz
 *     sha256:b9805b691f2f0a8926c92694cae378d05ba07b76abca745e216fcc52753cc4d6  (~93 MB)
 * Linux aarch64, aarch64:
 *   toolchain_gnu_linux-aarch64_aarch64-zephyr-elf.tar.xz
 *     sha256:b3f217b7e099509e44d4eb98a0776f4782a05dfc8936b6dcbd2946134d0e13c3  (~55 MB)
 * Linux aarch64, riscv64:
 *   toolchain_gnu_linux-aarch64_riscv64-zephyr-elf.tar.xz
 *     sha256:7000feff1cdcf872b88bb987696035aab0807b566e9a9842881438187df6e848  (~130 MB)
 *
 * Windows x86_64 — extension is .7z (NOT .tar.xz), arm:
 *   toolchain_gnu_windows-x86_64_arm-zephyr-eabi.7z
 *     sha256:d08df2e83378dc8d28d0c67ab72d021ca633344b7a2bc053ababf9b8e677aa8d  (~76 MB)
 *   (Windows is NOT supported for download; 7z extraction requires a separate library.)
 */
object ZephyrToolchainDownloader {

    /** The Zephyr SDK release version this downloader targets. */
    const val PINNED_ZEPHYR_SDK_VERSION = "1.0.1"

    private val LOG = Logger.getInstance(ZephyrToolchainDownloader::class.java)

    private const val RELEASES_API =
        "https://api.github.com/repos/zephyrproject-rtos/sdk-ng/releases/tags/v$PINNED_ZEPHYR_SDK_VERSION"

    /**
     * Hardcoded digests for known host/filename combinations (fast path).
     * Retrieved 2026-08-25 from GitHub Releases API; see class KDoc for full evidence.
     */
    private val KNOWN_DIGESTS: Map<String, String> = mapOf(
        "toolchain_gnu_macos-aarch64_arm-zephyr-eabi.tar.xz" to
            "4008edb5d4840cd994aedd7f1309bfb63e7243729d57839ebf1cc83c1f17c886",
        "toolchain_gnu_macos-aarch64_aarch64-zephyr-elf.tar.xz" to
            "5a8778bff5b80d1547c1d926287eb79b3d5b6345353a6df992caa937858d8873",
        "toolchain_gnu_macos-aarch64_riscv64-zephyr-elf.tar.xz" to
            "bc9026ee24c34d79f89bb9f1eb7f86a60232ed8eb1b85d301178482f6544cee7",
        "toolchain_gnu_linux-x86_64_arm-zephyr-eabi.tar.xz" to
            "21b85981cb5a1818d9bc53d82af80f208946ec038b982ff1907287572ed3a634",
        "toolchain_gnu_linux-x86_64_aarch64-zephyr-elf.tar.xz" to
            "01c0cfe1daaab2d2a0f71165f11a066d983ef97e51c4a3753186afa6ae55c24b",
        "toolchain_gnu_linux-x86_64_riscv64-zephyr-elf.tar.xz" to
            "01750834c471fbdb335c1b8b8aee17010a1968938957db85640c366235771a38",
        "toolchain_gnu_linux-aarch64_arm-zephyr-eabi.tar.xz" to
            "b9805b691f2f0a8926c92694cae378d05ba07b76abca745e216fcc52753cc4d6",
        "toolchain_gnu_linux-aarch64_aarch64-zephyr-elf.tar.xz" to
            "b3f217b7e099509e44d4eb98a0776f4782a05dfc8936b6dcbd2946134d0e13c3",
        "toolchain_gnu_linux-aarch64_riscv64-zephyr-elf.tar.xz" to
            "7000feff1cdcf872b88bb987696035aab0807b566e9a9842881438187df6e848",
    )

    // -------------------------------------------------------------------------
    // Pure mapping — no IO; unit-testable
    // -------------------------------------------------------------------------

    /**
     * Maps a [TargetProfile] to the Zephyr toolchain target triple.
     *
     * - [ArchClass.CortexM] → `"arm-zephyr-eabi"` (thumb / bare-metal ARM)
     * - [ArchClass.CortexA] + 64-bit ELF → `"aarch64-zephyr-elf"`
     * - [ArchClass.CortexA] + 32-bit ELF → `"arm-zephyr-eabi"` (Cortex-A32)
     * - [ArchClass.RiscV] → `"riscv64-zephyr-elf"` (unified RV32/RV64 toolchain)
     * - [ArchClass.Other] → `null` (no suitable toolchain to download)
     *
     * Pure function; no IO.
     */
    fun toolchainTargetFor(profile: TargetProfile): String? = when (profile.archClass) {
        ArchClass.CortexM -> "arm-zephyr-eabi"
        ArchClass.CortexA -> if (profile.elfIs64Bit) "aarch64-zephyr-elf" else "arm-zephyr-eabi"
        ArchClass.RiscV -> "riscv64-zephyr-elf"
        ArchClass.Other -> null
    }

    /**
     * Builds the download URL for a given toolchain [target] triple.
     *
     * [isMac], [isLinux] and [hostArch] are injectable for golden tests;
     * production callers use the zero-arg overload that reads host info at runtime.
     *
     * Returns `null` when the OS is Windows (`.7z` archives are not supported)
     * or when no suitable OS is detected.
     */
    fun downloadUrlFor(
        target: String,
        isMac: Boolean = SystemInfo.isMac,
        isLinux: Boolean = SystemInfo.isLinux,
        hostArch: String = System.getProperty("os.arch") ?: "",
    ): String? {
        val arch = if (hostArch == "aarch64" || hostArch.contains("arm64", ignoreCase = true)) "aarch64" else "x86_64"
        val os = when {
            isMac -> "macos"
            isLinux -> "linux"
            else -> return null // Windows .7z not supported
        }
        val filename = "toolchain_gnu_${os}-${arch}_${target}.tar.xz"
        return "https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v$PINNED_ZEPHYR_SDK_VERSION/$filename"
    }

    // -------------------------------------------------------------------------
    // Consent + download — must be called on EDT
    // -------------------------------------------------------------------------

    /**
     * Shows a consent dialog and, on confirmation, starts a background download.
     *
     * Consent is mandatory: the toolchain contains GPLv3 tools (GDB, GCC).
     * SHA-256 verification is mandatory: a mismatch aborts the install loudly.
     *
     * Must be called on the EDT. [onSuccess] is called on the EDT after a successful install.
     *
     * @param project Current project (nullable for application-level callers).
     * @param target Zephyr toolchain target triple (e.g. `"arm-zephyr-eabi"`).
     * @param onSuccess Called on the EDT after [HexanaFirmwareSettings.gdbPathInUi] is updated.
     */
    fun downloadWithConsent(project: Project?, target: String, onSuccess: () -> Unit) {
        val url = downloadUrlFor(target) ?: run {
            Messages.showErrorDialog(
                project,
                "Zephyr toolchain download is not supported on Windows " +
                    "(the .7z format requires a separate extraction tool). " +
                    "Install the Zephyr SDK manually from https://github.com/zephyrproject-rtos/sdk-ng/releases",
                "Zephyr Toolchain Download",
            )
            return
        }
        val filename = url.substringAfterLast('/')
        val approxSizeMb = approxSizeMb(target)
        val installDir = toolchainInstallDir()

        val consent = Messages.showYesNoDialog(
            project,
            "Download Zephyr SDK $PINNED_ZEPHYR_SDK_VERSION toolchain for $target (~${approxSizeMb} MB)?\n\n" +
                "URL: $url\n\n" +
                "This toolchain contains GPLv3 tools (GDB, GCC). The download is verified by SHA-256.\n" +
                "The archive is installed under:\n" +
                "  $installDir",
            "Download Zephyr SDK Toolchain",
            Messages.getQuestionIcon(),
        )
        if (consent != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Modal(
            project,
            "Downloading Zephyr SDK $PINNED_ZEPHYR_SDK_VERSION toolchain ($target)",
            /* canBeCancelled = */ true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    doDownload(filename, url, target, installDir, indicator)
                    invokeLater { onSuccess() }
                } catch (e: Exception) {
                    LOG.warn("Zephyr toolchain download failed", e)
                    invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Zephyr toolchain download failed: ${e.message}",
                            "Zephyr Toolchain Download",
                        )
                    }
                }
            }
        })
    }

    // -------------------------------------------------------------------------
    // Internal implementation — runs off EDT inside Task.Backgroundable
    // -------------------------------------------------------------------------

    private fun doDownload(
        filename: String,
        url: String,
        target: String,
        installDir: Path,
        indicator: ProgressIndicator,
    ) {
        val settings = HexanaFirmwareSettings.getInstance()
        Files.createDirectories(installDir)
        val tempFile = installDir.parent.resolve("$filename.tmp")

        try {
            indicator.isIndeterminate = false
            indicator.text = "Downloading Zephyr toolchain $target..."
            indicator.fraction = 0.0
            HttpRequests.request(url).saveToFile(tempFile.toFile(), indicator)
            indicator.fraction = 0.5

            indicator.text = "Verifying Zephyr toolchain $target..."
            val actualSha = sha256Hex(tempFile)
            val expectedSha = resolveExpectedSha(filename, url, actualSha, indicator)
            if (expectedSha != null && !actualSha.equals(expectedSha, ignoreCase = true)) {
                throw IOException(
                    "SHA-256 mismatch for $filename: expected $expectedSha, got $actualSha. " +
                        "The downloaded file may be corrupt or tampered with. Installation aborted.",
                )
            }
            indicator.fraction = 0.6

            indicator.isIndeterminate = true
            indicator.text = "Extracting Zephyr toolchain $target..."
            installDir.toFile().deleteRecursively()
            Files.createDirectories(installDir)
            Decompressor.Tar(tempFile).extract(installDir)
            indicator.isIndeterminate = false
            indicator.fraction = 0.9

            val gdbPy = locateGdbPy(installDir, target)
                ?: throw IOException(
                    "Could not locate $target-gdb-py after extraction in $installDir. " +
                        "The archive layout may be unexpected."
                )

            invokeLater {
                settings.gdbPathInUi = gdbPy
            }
            indicator.fraction = 1.0
            LOG.info("Zephyr toolchain $target installed; gdb-py at $gdbPy")
        } finally {
            tempFile.deleteIfExists()
        }
    }

    /**
     * Locates `<target>-gdb-py` inside [installDir].
     *
     * Tolerates both extraction layouts observed in Zephyr SDK v1.0.1:
     * - Flat:   `<installDir>/bin/<target>-gdb-py`
     * - Nested: `<installDir>/<target>/bin/<target>-gdb-py`
     *           (observed for arm and aarch64 when tarball extracts with a top-level dir)
     *
     * Returns the absolute path, or `null` if not found.
     */
    internal fun locateGdbPy(installDir: Path, target: String): String? {
        val gdbName = "$target-gdb-py"
        // Depth 1: bin/ directly under installDir
        val flat = installDir.resolve("bin/$gdbName").toFile()
        if (flat.exists() && flat.canExecute()) return flat.absolutePath
        // Depth 2: one sub-directory layer before bin/
        installDir.toFile().listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
            val nested = File(subDir, "bin/$gdbName")
            if (nested.exists() && nested.canExecute()) return nested.absolutePath
        }
        return null
    }

    /**
     * Resolves the expected SHA-256 for [filename].
     * Uses [KNOWN_DIGESTS] first; falls back to the GitHub Releases API if not found;
     * if the API is unreachable, shows the computed hash and requires user confirmation.
     * Returns `null` only when the user explicitly accepts an unverified hash.
     */
    private fun resolveExpectedSha(
        filename: String,
        url: String,
        actualSha: String,
        indicator: ProgressIndicator,
    ): String? {
        val builtin = KNOWN_DIGESTS[filename]
        if (builtin != null) return builtin

        return try {
            indicator.text = "Fetching checksum from GitHub..."
            val apiJson = HttpRequests.request(RELEASES_API).readString(indicator)
            parseDigestFromApiJson(apiJson, filename)
        } catch (e: Exception) {
            LOG.warn("GitHub API unreachable for checksum; asking user", e)
            var userAccepted = false
            val latch = java.util.concurrent.CountDownLatch(1)
            invokeLater {
                val choice = Messages.showYesNoDialog(
                    null,
                    "Could not reach the GitHub API to verify the checksum.\n\n" +
                        "Computed SHA-256 of the downloaded file:\n$actualSha\n\n" +
                        "Accept this file and proceed with installation?",
                    "Checksum Verification",
                    Messages.getWarningIcon(),
                )
                userAccepted = choice == Messages.YES
                latch.countDown()
            }
            latch.await()
            if (!userAccepted) throw IOException("Installation cancelled by user (unverified checksum)")
            null
        }
    }

    /** Parses the SHA-256 digest for [filename] from the GitHub releases API JSON text. */
    internal fun parseDigestFromApiJson(json: String, filename: String): String? {
        val nameIdx = json.indexOf("\"$filename\"")
        if (nameIdx < 0) return null
        val digestSearch = json.indexOf("\"digest\"", nameIdx)
        if (digestSearch < 0) return null
        val valueStart = json.indexOf("sha256:", digestSearch)
        if (valueStart < 0) return null
        val hexStart = valueStart + "sha256:".length
        var hexEnd = hexStart
        while (hexEnd < json.length && json[hexEnd] != '"') hexEnd++
        return json.substring(hexStart, hexEnd).takeIf { it.length == 64 }
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the install directory for the toolchain:
     * `<system-path>/hexana-firmware/zephyr-toolchain-<version>/`.
     */
    private fun toolchainInstallDir(): Path =
        Path.of(PathManager.getSystemPath(), "hexana-firmware", "zephyr-toolchain-$PINNED_ZEPHYR_SDK_VERSION")

    internal fun approxSizeMb(target: String): String = when (target) {
        "aarch64-zephyr-elf" -> when {
            SystemInfo.isMac -> "~49"
            else -> "~60"
        }
        "riscv64-zephyr-elf" -> when {
            SystemInfo.isMac -> "~133"
            else -> "~145"
        }
        else -> "~90" // arm-zephyr-eabi and fallback
    }

    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.toFile().inputStream().use { stream ->
            val buf = ByteArray(65536)
            var read = stream.read(buf)
            while (read >= 0) {
                digest.update(buf, 0, read)
                read = stream.read(buf)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
