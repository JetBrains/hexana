package org.jetbrains.hexana.firmware

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
 * Downloads and installs the pinned Renode portable release on demand.
 *
 * Flow:
 * 1. Show consent dialog naming the download URL, size, and MIT license.
 * 2. Run a [Task.Modal] (cancellable) that fetches the archive with a determinate progress bar.
 * 3. Verify the SHA-256 from the GitHub Releases API (or ask the user if the
 *    API is unreachable).
 * 4. Unpack: tar.gz / zip via [Decompressor]; DMG via `hdiutil` on macOS.
 * 5. Install under [HexanaFirmwareSettings.effectiveRenodeDownloadDir].
 * 6. Write the resolved executable path into [HexanaFirmwareSettings.renodePathInUi].
 *
 * Never auto-triggered. Invoked only from [FirmwareRunSettingsEditor] (run-configuration
 * editor, where a modal progress dialog is required) or [FirmwareEmulatorConfigurable].
 */
object RenodeDownloader {

    /** The Renode release version this downloader targets. */
    const val PINNED_RENODE_VERSION = "1.16.1"

    private val LOG = Logger.getInstance(RenodeDownloader::class.java)

    private const val RELEASES_API =
        "https://api.github.com/repos/renode/renode/releases/tags/v$PINNED_RENODE_VERSION"

    /**
     * Digest evidence line from the GitHub Releases API for `v1.16.1`.
     * Retrieved 2026-08-24 via GET $RELEASES_API and inspecting `assets[].digest`.
     *
     * macOS arm64: sha256:99b8ae5897b8926ef179868d39a504fe5296555dc9c9b973718ddf3ab09175d9
     * Linux x64:   sha256:00e113cdbd0f5354cf2f64bbe3f5a070d8958409542fca66e45ac97d982938c0
     * Linux arm64: sha256:fff3a098c96ed0a4ffbdff3f028c9c5fde432db09587c7bd7c99406180f90007
     * Windows:     sha256:d09b7934cfd560cd06bde8f131ef78f521f10d423d5aac6096f2a583224aeb3e
     */
    private val KNOWN_DIGESTS: Map<String, String> = mapOf(
        "renode-1.16.1-dotnet.osx-arm64-portable.dmg" to
            "99b8ae5897b8926ef179868d39a504fe5296555dc9c9b973718ddf3ab09175d9",
        "renode-1.16.1.linux-portable-dotnet.tar.gz" to
            "00e113cdbd0f5354cf2f64bbe3f5a070d8958409542fca66e45ac97d982938c0",
        "renode-1.16.1.linux-arm64-portable-dotnet.tar.gz" to
            "fff3a098c96ed0a4ffbdff3f028c9c5fde432db09587c7bd7c99406180f90007",
        "renode-1.16.1.windows-portable-dotnet.zip" to
            "d09b7934cfd560cd06bde8f131ef78f521f10d423d5aac6096f2a583224aeb3e",
    )

    /**
     * Shows a consent dialog and, on confirmation, starts a modal download with a
     * determinate progress bar. Must be called on the EDT.
     *
     * Uses [Task.Modal] so the progress dialog appears above whatever window (settings
     * page or run-configuration editor) triggered the download. [onSuccess] is called
     * on the EDT after a successful install.
     */
    fun downloadWithConsent(project: Project?, onSuccess: () -> Unit) {
        val filename = currentOsFilename() ?: run {
            Messages.showErrorDialog(
                project,
                "No Renode portable package is available for this OS/arch combination.",
                "Renode Download",
            )
            return
        }
        val url = "https://github.com/renode/renode/releases/download/v$PINNED_RENODE_VERSION/$filename"
        val sizeNote = currentOsSizeNote()

        val consent = Messages.showYesNoDialog(
            project,
            "Download Renode $PINNED_RENODE_VERSION ($sizeNote)?\n\n" +
                "URL: $url\n\n" +
                "Renode is MIT-licensed. The download is verified by SHA-256.\n" +
                "The archive is installed under:\n" +
                "  ${HexanaFirmwareSettings.getInstance().effectiveRenodeDownloadDir()}",
            "Download Renode $PINNED_RENODE_VERSION",
            Messages.getQuestionIcon(),
        )
        if (consent != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Modal(
            project,
            "Downloading Renode $PINNED_RENODE_VERSION",
            /* canBeCancelled = */ true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    doDownload(filename, url, indicator)
                    invokeLater { onSuccess() }
                } catch (e: Exception) {
                    LOG.warn("Renode download failed", e)
                    invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Renode download failed: ${e.message}",
                            "Renode Download",
                        )
                    }
                }
            }
        })
    }

    // -------------------------------------------------------------------------
    // Internal implementation — runs off EDT inside Task.Modal
    // -------------------------------------------------------------------------

    private fun doDownload(filename: String, url: String, indicator: ProgressIndicator) {
        val settings = HexanaFirmwareSettings.getInstance()
        val installDir = Path.of(settings.effectiveRenodeDownloadDir())
        Files.createDirectories(installDir)
        val tempFile = installDir.parent.resolve("$filename.tmp")

        try {
            indicator.isIndeterminate = false
            indicator.text = "Downloading Renode $PINNED_RENODE_VERSION..."
            indicator.fraction = 0.0
            HttpRequests.request(url).saveToFile(tempFile.toFile(), indicator)
            indicator.fraction = 0.5

            indicator.text = "Verifying Renode $PINNED_RENODE_VERSION..."
            val actualSha = sha256Hex(tempFile)
            val expectedSha = resolveExpectedSha(filename, url, actualSha, indicator)
            if (expectedSha != null && !actualSha.equals(expectedSha, ignoreCase = true)) {
                throw IOException(
                    "SHA-256 mismatch: expected $expectedSha, got $actualSha",
                )
            }
            indicator.fraction = 0.6

            indicator.isIndeterminate = true
            indicator.text = "Extracting Renode $PINNED_RENODE_VERSION..."
            extractArchive(filename, tempFile, installDir, indicator)
            indicator.isIndeterminate = false
            indicator.fraction = 0.95

            val exePath = guessInstalledExe(installDir)
                ?: throw IOException("Renode executable not found after extraction in $installDir")

            invokeLater {
                settings.renodePathInUi = exePath
            }
            indicator.fraction = 1.0
            LOG.info("Renode $PINNED_RENODE_VERSION installed at $exePath")
        } finally {
            tempFile.deleteIfExists()
        }
    }

    /**
     * Resolves the expected SHA-256 for [filename].
     * Uses [KNOWN_DIGESTS] first; falls back to the GitHub Releases API if not found;
     * if the API is unreachable, shows the computed hash and asks the user to confirm.
     */
    private fun resolveExpectedSha(
        filename: String,
        url: String,
        actualSha: String,
        indicator: ProgressIndicator,
    ): String? {
        val builtin = KNOWN_DIGESTS[filename]
        if (builtin != null) return builtin

        // Try the API
        return try {
            indicator.text = "Fetching checksum from GitHub..."
            val apiJson = HttpRequests.request(RELEASES_API).readString(indicator)
            parseDigestFromApiJson(apiJson, filename)
        } catch (e: Exception) {
            LOG.warn("GitHub API unreachable for checksum; asking user", e)
            // Ask the user on the EDT whether to accept the unverified hash
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
            null // skip further comparison
        }
    }

    /** Extracts [tempFile] into [installDir] based on [filename] extension. */
    private fun extractArchive(
        filename: String,
        tempFile: Path,
        installDir: Path,
        indicator: ProgressIndicator,
    ) {
        installDir.toFile().deleteRecursively()
        Files.createDirectories(installDir)

        when {
            filename.endsWith(".tar.gz") -> {
                Decompressor.Tar(tempFile).extract(installDir)
            }
            filename.endsWith(".zip") -> {
                Decompressor.Zip(tempFile).extract(installDir)
            }
            filename.endsWith(".dmg") -> {
                extractDmg(tempFile, installDir, indicator)
            }
            else -> throw IOException("Unsupported archive type: $filename")
        }
    }

    /** Mounts [dmg], copies the portable directory out, then detaches. macOS only. */
    private fun extractDmg(dmg: Path, installDir: Path, indicator: ProgressIndicator) {
        indicator.text = "Mounting Renode DMG..."
        val mountPoint = Files.createTempDirectory("renode-dmg-mount")
        try {
            val attach = Runtime.getRuntime().exec(
                arrayOf("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint",
                    mountPoint.absolutePathString(), dmg.absolutePathString()),
            )
            val attachExit = attach.waitFor()
            if (attachExit != 0) {
                val err = attach.errorStream.bufferedReader().readText()
                throw IOException("hdiutil attach failed (exit $attachExit): $err")
            }
            // Copy Renode.app or the portable dir out
            indicator.text = "Copying Renode from DMG..."
            val appDir = mountPoint.resolve("Renode.app")
            val portableDir = mountPoint.toFile().listFiles()
                ?.firstOrNull { it.isDirectory && it.name != ".background" && it.name != ".Trashes" }
                ?.toPath()
            val sourceDir = when {
                appDir.exists() -> appDir
                portableDir != null -> portableDir
                else -> throw IOException("No Renode directory found in DMG at $mountPoint")
            }
            installDir.toFile().deleteRecursively()
            sourceDir.toFile().copyRecursively(installDir.toFile())
        } finally {
            Runtime.getRuntime().exec(arrayOf("hdiutil", "detach", mountPoint.absolutePathString()))
                .waitFor()
            mountPoint.toFile().deleteRecursively()
        }
    }

    private fun guessInstalledExe(installDir: Path): String? {
        // macOS: .app bundle
        val macApp = installDir.resolve("Renode.app/Contents/MacOS/Renode")
        if (macApp.exists()) return macApp.absolutePathString()
        // Linux / Windows: renode / renode.exe at the root of the install dir
        val linux = installDir.resolve("renode")
        if (linux.exists()) return linux.absolutePathString()
        val win = installDir.resolve("renode.exe")
        if (win.exists()) return win.absolutePathString()
        // Some portable builds nest under a version subdirectory
        installDir.toFile().listFiles()?.forEach { subdir ->
            if (!subdir.isDirectory) return@forEach
            listOf("renode", "renode.exe", "Renode.app/Contents/MacOS/Renode").forEach { rel ->
                val f = File(subdir, rel)
                if (f.exists()) return f.absolutePath
            }
        }
        return null
    }

    /** Parses the SHA-256 digest for [filename] from the GitHub releases API JSON text. */
    internal fun parseDigestFromApiJson(json: String, filename: String): String? {
        // The API returns a JSON object. We do a simple text scan rather than a full parse
        // to avoid a runtime JSON dependency in this layer.
        // Asset entry looks like: "name":"<filename>",..."digest":"sha256:<hex>"
        val nameIdx = json.indexOf("\"$filename\"")
        if (nameIdx < 0) return null
        val digestSearch = json.indexOf("\"digest\"", nameIdx)
        if (digestSearch < 0) return null
        val valueStart = json.indexOf("sha256:", digestSearch)
        if (valueStart < 0) return null
        val hexStart = valueStart + "sha256:".length
        val hexEnd = json.indexOfFirst { false }.let {
            // find closing quote after hexStart
            var i = hexStart
            while (i < json.length && json[i] != '"') i++
            i
        }
        return json.substring(hexStart, hexEnd).takeIf { it.length == 64 }
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

    // -------------------------------------------------------------------------
    // OS/arch helpers
    // -------------------------------------------------------------------------

    private fun currentOsFilename(): String? = when {
        SystemInfo.isMac && isArm64() ->
            "renode-$PINNED_RENODE_VERSION-dotnet.osx-arm64-portable.dmg"
        SystemInfo.isMac ->
            // x64 macOS (no explicit entry in fact sheet; use linux portable as fallback note)
            null // no x64 macOS portable in 1.16.1 per fact sheet; future: add when available
        SystemInfo.isLinux && isArm64() ->
            "renode-$PINNED_RENODE_VERSION.linux-arm64-portable-dotnet.tar.gz"
        SystemInfo.isLinux ->
            "renode-$PINNED_RENODE_VERSION.linux-portable-dotnet.tar.gz"
        SystemInfo.isWindows ->
            "renode-$PINNED_RENODE_VERSION.windows-portable-dotnet.zip"
        else -> null
    }

    private fun currentOsSizeNote(): String = when {
        SystemInfo.isMac -> "~82 MB"
        SystemInfo.isLinux && isArm64() -> "~72 MB"
        SystemInfo.isLinux -> "~74 MB"
        SystemInfo.isWindows -> "~112 MB"
        else -> ""
    }

    private fun isArm64(): Boolean {
        val arch = System.getProperty("os.arch") ?: return false
        return arch == "aarch64" || arch.contains("arm64", ignoreCase = true)
    }
}
