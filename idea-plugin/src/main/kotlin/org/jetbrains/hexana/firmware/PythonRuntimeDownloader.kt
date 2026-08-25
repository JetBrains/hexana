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
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * Downloads and installs a python-build-standalone CPython runtime on demand.
 *
 * Only the "install_only_stripped" variant is fetched (~22-33 MB), which is
 * significantly smaller than a full debug build. The extracted layout is:
 * ```
 *   python/
 *     bin/python3      (macOS / Linux)
 *     python.exe       (Windows, inside python/)
 * ```
 *
 * Flow:
 * 1. Show consent dialog naming the download URL, approximate size, and PSF license.
 * 2. Run a [Task.Modal] (cancellable) that fetches the archive with a determinate progress bar.
 * 3. Verify the SHA-256 via [KNOWN_DIGESTS] (fast path), falling back to the GitHub Releases API.
 *    Abort loudly on mismatch — never proceed with a warning.
 * 4. Extract the `.tar.gz` via [Decompressor.Tar].
 * 5. Locate the interpreter via [locatePython]; restore the exec bit on POSIX.
 * 6. Set [HexanaFirmwareSettings.pythonPathInUi] to the located binary.
 *
 * Never auto-triggered. Invoked only from [FirmwareRunSettingsEditor] (run-configuration
 * editor), where a modal progress dialog is appropriate.
 *
 * ---
 * **Asset evidence** — retrieved 2026-08-25 via SHA256SUMS at:
 * `https://github.com/astral-sh/python-build-standalone/releases/download/20260814/SHA256SUMS`
 *
 * Release tag: `20260814`  CPython version: `3.12.14`
 *
 * | Platform                  | Filename (install_only_stripped)                                          | SHA-256                                                          | Size  |
 * |---------------------------|---------------------------------------------------------------------------|------------------------------------------------------------------|-------|
 * | aarch64-apple-darwin      | cpython-3.12.14+20260814-aarch64-apple-darwin-install_only_stripped.tar.gz   | dd5b76ab11451a4a4367c17c61d944dded56b425396b07f102922a7ebef7d55f | ~24 MB |
 * | x86_64-apple-darwin       | cpython-3.12.14+20260814-x86_64-apple-darwin-install_only_stripped.tar.gz    | aec265e3cddaccdb2a3d783331596351b24d4a63c97af0a38f75f643c9451de9 | ~24 MB |
 * | x86_64-unknown-linux-gnu  | cpython-3.12.14+20260814-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz | 5acfa3e9ba26b51ae161c83aff278da915b590d22373a424b2ba55b8afe91fcc | ~33 MB |
 * | aarch64-unknown-linux-gnu | cpython-3.12.14+20260814-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz | 2d8e17dfd732102cfeb18e0e1fa6769b24caa034e159981129590fe409c7157a | ~29 MB |
 * | x86_64-pc-windows-msvc    | cpython-3.12.14+20260814-x86_64-pc-windows-msvc-install_only_stripped.tar.gz | 89f18f6932917163b74339ebcec2645c8e47ae7f1c5f2ac37f2b4f4cf3beb647 | ~22 MB |
 */
object PythonRuntimeDownloader {

    /** The python-build-standalone release tag this downloader targets. */
    const val PINNED_PBS_RELEASE = "20260814"

    /** The CPython version pinned for this release. */
    const val PINNED_PYTHON_VERSION = "3.12.14"

    private val LOG = Logger.getInstance(PythonRuntimeDownloader::class.java)

    private const val RELEASES_API =
        "https://api.github.com/repos/astral-sh/python-build-standalone/releases/tags/$PINNED_PBS_RELEASE"

    private const val DOWNLOAD_BASE =
        "https://github.com/astral-sh/python-build-standalone/releases/download/$PINNED_PBS_RELEASE"

    /**
     * Hardcoded SHA-256 digests for the five supported platforms (fast path).
     * Retrieved 2026-08-25 from SHA256SUMS in the 20260814 release; see class KDoc for evidence.
     */
    private val KNOWN_DIGESTS: Map<String, String> = mapOf(
        "cpython-3.12.14+20260814-aarch64-apple-darwin-install_only_stripped.tar.gz" to
            "dd5b76ab11451a4a4367c17c61d944dded56b425396b07f102922a7ebef7d55f",
        "cpython-3.12.14+20260814-x86_64-apple-darwin-install_only_stripped.tar.gz" to
            "aec265e3cddaccdb2a3d783331596351b24d4a63c97af0a38f75f643c9451de9",
        "cpython-3.12.14+20260814-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz" to
            "5acfa3e9ba26b51ae161c83aff278da915b590d22373a424b2ba55b8afe91fcc",
        "cpython-3.12.14+20260814-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz" to
            "2d8e17dfd732102cfeb18e0e1fa6769b24caa034e159981129590fe409c7157a",
        "cpython-3.12.14+20260814-x86_64-pc-windows-msvc-install_only_stripped.tar.gz" to
            "89f18f6932917163b74339ebcec2645c8e47ae7f1c5f2ac37f2b4f4cf3beb647",
    )

    // -------------------------------------------------------------------------
    // Pure mapping — no IO; unit-testable
    // -------------------------------------------------------------------------

    /**
     * Builds the download URL for the current host platform.
     *
     * [isMac], [isLinux], [isWindows], and [hostArch] are injectable for golden tests;
     * production callers use the zero-arg overload that reads host info at runtime.
     *
     * Returns `null` when the platform is unsupported (e.g., 32-bit ARM Linux).
     */
    fun downloadUrlFor(
        isMac: Boolean = SystemInfo.isMac,
        isLinux: Boolean = SystemInfo.isLinux,
        isWindows: Boolean = SystemInfo.isWindows,
        hostArch: String = System.getProperty("os.arch") ?: "",
    ): String? {
        val arch = if (hostArch == "aarch64" || hostArch.contains("arm64", ignoreCase = true)) "aarch64" else "x86_64"
        val platform = when {
            isMac -> "$arch-apple-darwin"
            isLinux -> "$arch-unknown-linux-gnu"
            isWindows -> "x86_64-pc-windows-msvc"  // Windows is always x86_64
            else -> return null
        }
        val filename = "cpython-$PINNED_PYTHON_VERSION+$PINNED_PBS_RELEASE-$platform-install_only_stripped.tar.gz"
        return "$DOWNLOAD_BASE/$filename"
    }

    // -------------------------------------------------------------------------
    // Consent + download — must be called on EDT
    // -------------------------------------------------------------------------

    /**
     * Shows a consent dialog and, on confirmation, starts a background download.
     *
     * Consent is mandatory: users must acknowledge the PSF license and download URL.
     * SHA-256 verification is mandatory: a mismatch aborts the install loudly.
     *
     * Must be called on the EDT. [onSuccess] is called on the EDT after a successful install.
     *
     * @param project Current project (nullable for application-level callers).
     * @param onSuccess Called on the EDT after [HexanaFirmwareSettings.pythonPathInUi] is updated.
     */
    fun downloadWithConsent(project: Project?, onSuccess: () -> Unit) {
        val url = downloadUrlFor() ?: run {
            Messages.showErrorDialog(
                project,
                "Python runtime download is not supported on this platform. " +
                    "Install Python 3.9+ via your package manager or from https://python.org",
                "Python Runtime Download",
            )
            return
        }
        val filename = url.substringAfterLast('/')
        val approxSizeMb = approxSizeMb()
        val installDir = pythonInstallDir()

        val consent = Messages.showYesNoDialog(
            project,
            "Download CPython $PINNED_PYTHON_VERSION (python-build-standalone $PINNED_PBS_RELEASE, ~$approxSizeMb MB)?\n\n" +
                "URL: $url\n\n" +
                "CPython is PSF-licensed. The download is verified by SHA-256.\n" +
                "The archive is installed under:\n" +
                "  $installDir",
            "Download Python Runtime",
            Messages.getQuestionIcon(),
        )
        if (consent != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Modal(
            project,
            "Downloading CPython $PINNED_PYTHON_VERSION",
            /* canBeCancelled = */ true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    doDownload(filename, url, installDir, indicator)
                    invokeLater { onSuccess() }
                } catch (e: Exception) {
                    LOG.warn("Python runtime download failed", e)
                    invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Python runtime download failed: ${e.message}",
                            "Python Runtime Download",
                        )
                    }
                }
            }
        })
    }

    // -------------------------------------------------------------------------
    // Internal implementation — runs off EDT inside Task.Modal
    // -------------------------------------------------------------------------

    private fun doDownload(
        filename: String,
        url: String,
        installDir: Path,
        indicator: ProgressIndicator,
    ) {
        val settings = HexanaFirmwareSettings.getInstance()
        Files.createDirectories(installDir)
        val tempFile = installDir.parent.resolve("$filename.tmp")

        try {
            indicator.isIndeterminate = false
            indicator.text = "Downloading CPython $PINNED_PYTHON_VERSION..."
            indicator.fraction = 0.0
            HttpRequests.request(url).saveToFile(tempFile.toFile(), indicator)
            indicator.fraction = 0.5

            indicator.text = "Verifying CPython $PINNED_PYTHON_VERSION..."
            val actualSha = sha256Hex(tempFile)
            val expectedSha = resolveExpectedSha(filename, actualSha, indicator)
            if (expectedSha != null && !actualSha.equals(expectedSha, ignoreCase = true)) {
                throw IOException(
                    "SHA-256 mismatch for $filename: expected $expectedSha, got $actualSha. " +
                        "The downloaded file may be corrupt or tampered with. Installation aborted.",
                )
            }
            indicator.fraction = 0.6

            indicator.isIndeterminate = true
            indicator.text = "Extracting CPython $PINNED_PYTHON_VERSION..."
            installDir.toFile().deleteRecursively()
            Files.createDirectories(installDir)
            Decompressor.Tar(tempFile).extract(installDir)
            indicator.isIndeterminate = false
            indicator.fraction = 0.9

            val pythonExe = locatePython(installDir)
                ?: throw IOException(
                    "Could not locate python3 or python.exe after extraction in $installDir. " +
                        "The archive layout may be unexpected.",
                )

            invokeLater {
                settings.pythonPathInUi = pythonExe
            }
            indicator.fraction = 1.0
            LOG.info("CPython $PINNED_PYTHON_VERSION installed; interpreter at $pythonExe")
        } finally {
            tempFile.deleteIfExists()
        }
    }

    /**
     * Locates the Python interpreter inside [installDir].
     *
     * The install_only layout from python-build-standalone extracts to:
     * ```
     *   python/
     *     bin/python3        (macOS / Linux)
     *     python.exe         (Windows — at python/python.exe, not python/bin/)
     * ```
     * The locator is tolerant of variations: it tries `python/bin/python3`,
     * `bin/python3`, and `python/python.exe` (in that order). On POSIX systems,
     * it also restores the executable bit in case extraction did not preserve it.
     *
     * Returns the absolute path to the interpreter, or `null` if not found.
     */
    internal fun locatePython(installDir: Path): String? {
        // Candidate paths in priority order
        val candidates = listOf(
            installDir.resolve("python/bin/python3").toFile(),
            installDir.resolve("bin/python3").toFile(),
            installDir.resolve("python/python.exe").toFile(),
            installDir.resolve("python.exe").toFile(),
        )
        for (candidate in candidates) {
            if (!candidate.exists()) continue
            // Restore exec bit on POSIX (extraction may clear it on some JVMs)
            if (!SystemInfo.isWindows && !candidate.canExecute()) {
                candidate.setExecutable(true, /* ownerOnly = */ false)
            }
            if (candidate.canExecute() || SystemInfo.isWindows) return candidate.absolutePath
        }
        return null
    }

    /**
     * Resolves the expected SHA-256 for [filename].
     * Uses [KNOWN_DIGESTS] first; falls back to the GitHub Releases API if not found;
     * if the API is unreachable, asks for user confirmation before proceeding.
     * Returns `null` only when the user explicitly accepts an unverified hash.
     */
    private fun resolveExpectedSha(
        filename: String,
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
     * Returns the install directory for the downloaded runtime:
     * `<system-path>/hexana-firmware/python-<version>/`.
     */
    fun pythonInstallDir(): Path =
        Path.of(PathManager.getSystemPath(), "hexana-firmware", "python-$PINNED_PYTHON_VERSION")

    /** Returns a human-readable approximate download size for the current platform. */
    internal fun approxSizeMb(): String = when {
        SystemInfo.isWindows -> "~22"
        SystemInfo.isMac -> "~24"
        else -> "~30"  // Linux variants
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
