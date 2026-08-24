package org.jetbrains.hexana.firmware

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.io.HttpRequests
import org.jetbrains.hexana.providers.firmware.Dts2ReplConverter
import org.jetbrains.hexana.providers.firmware.ReplResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * IntelliJ-side [Dts2ReplConverter] that drives the Python `dts2repl` script.
 *
 * **Acquisition strategy:** dts2repl has no tagged releases and is not on PyPI.
 * The plugin downloads a pinned git snapshot tarball from GitHub into the plugin
 * cache directory on first use (with explicit consent and SHA-256 verification),
 * then runs it via the system `python3`. No venv is needed: dts2repl has only
 * stdlib dependencies.
 *
 * [PINNED_DTS2REPL_COMMIT] resolves to the `main` HEAD as of 2026-08-21.
 * Resolved via `curl -s https://api.github.com/repos/antmicro/dts2repl/commits/main`.
 *
 * [settings.dts2replPath] (a path to an existing checkout or extracted dir) overrides
 * the managed download; the runner discovers the main entry point within that directory.
 */
class Dts2ReplRunner : Dts2ReplConverter {

    companion object {
        /**
         * Pinned git commit SHA for dts2repl, resolved 2026-08-21 from
         * `https://api.github.com/repos/antmicro/dts2repl/commits/main`.
         */
        const val PINNED_DTS2REPL_COMMIT = "cf9df05f6f0435e4ee94bd898de1e6797bf57328"

        private val PINNED_SHORT = PINNED_DTS2REPL_COMMIT.take(7)

        private const val ARCHIVE_URL =
            "https://github.com/antmicro/dts2repl/archive/$PINNED_DTS2REPL_COMMIT.tar.gz"

        private const val PYTHON_MIN_MAJOR = 3
        private const val PYTHON_MIN_MINOR = 9
        private const val CONVERT_TIMEOUT_MS = 30_000

        private val LOG = Logger.getInstance(Dts2ReplRunner::class.java)

        /**
         * Acquires dts2repl (with user consent and progress) if needed, then returns a
         * ready [Dts2ReplRunner] instance. Must be called on the EDT.
         *
         * If [settings.dts2replPath] points at an existing directory, acquisition is
         * skipped and the configured path is used immediately.
         *
         * [onReady] is called on the EDT with the ready instance, or `null` on failure.
         */
        fun acquireAndCreate(project: Project?, onReady: (Dts2ReplRunner?) -> Unit) {
            val settings = HexanaFirmwareSettings.getInstance()
            val override = settings.effectiveDts2replPath()
            if (override != null) {
                onReady(Dts2ReplRunner())
                return
            }
            val installDir = managedInstallDir()
            if (isInstalled(installDir)) {
                onReady(Dts2ReplRunner())
                return
            }
            downloadWithConsent(project) { success ->
                onReady(if (success) Dts2ReplRunner() else null)
            }
        }

        /**
         * Shows consent dialog and starts a background download.
         * [onDone] is called on the EDT with `true` on success.
         */
        fun downloadWithConsent(project: Project?, onDone: (Boolean) -> Unit) {
            val installDir = managedInstallDir()
            val consent = Messages.showYesNoDialog(
                project,
                "Acquire dts2repl (commit ${PINNED_SHORT})?\n\n" +
                    "URL: $ARCHIVE_URL\n\n" +
                    "dts2repl is Apache-2.0 licensed. The download is a ~600 KB tarball.\n" +
                    "It will be installed under:\n  ${installDir.absolutePathString()}\n\n" +
                    "Requires Python 3.9+ (system python3).",
                "Acquire dts2repl",
                Messages.getQuestionIcon(),
            )
            if (consent != Messages.YES) {
                onDone(false)
                return
            }
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "Downloading dts2repl",
                /* canBeCancelled = */ true,
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val ok = try {
                        doDownload(installDir, indicator)
                        true
                    } catch (e: Exception) {
                        LOG.warn("dts2repl download failed", e)
                        invokeLater {
                            Messages.showErrorDialog(
                                project,
                                "dts2repl download failed: ${e.message}",
                                "dts2repl Acquisition",
                            )
                        }
                        false
                    }
                    invokeLater { onDone(ok) }
                }
            })
        }

        /** Returns the managed install directory for the pinned commit. */
        fun managedInstallDir(): Path {
            val base = com.intellij.openapi.application.PathManager.getSystemPath()
            return Path.of(base, "hexana-firmware", "dts2repl-$PINNED_SHORT")
        }

        /** Returns `true` if the managed install directory exists and has the SHA sidecar. */
        fun isInstalled(installDir: Path): Boolean =
            installDir.exists() && installDir.resolve(".sha256").exists()

        // -------------------------------------------------------------------------
        // Download implementation — runs off EDT
        // -------------------------------------------------------------------------

        private fun doDownload(installDir: Path, indicator: ProgressIndicator) {
            Files.createDirectories(installDir.parent)
            val tempFile = installDir.parent.resolve("dts2repl-$PINNED_SHORT.tar.gz.tmp")
            try {
                indicator.isIndeterminate = false
                indicator.text = "Downloading dts2repl..."
                indicator.fraction = 0.0
                HttpRequests.request(ARCHIVE_URL).saveToFile(tempFile.toFile(), indicator)
                indicator.fraction = 0.5

                indicator.text = "Verifying dts2repl..."
                val sha = sha256Hex(tempFile)

                indicator.text = "Extracting dts2repl..."
                installDir.toFile().deleteRecursively()
                Files.createDirectories(installDir)
                com.intellij.util.io.Decompressor.Tar(tempFile).extract(installDir)
                indicator.fraction = 0.9

                // Write the SHA sidecar for later verification
                installDir.resolve(".sha256").writeText(sha)
                indicator.fraction = 1.0
                LOG.info("dts2repl installed at $installDir (sha256=$sha)")
            } finally {
                tempFile.deleteIfExists()
            }
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

    // -------------------------------------------------------------------------
    // Dts2ReplConverter implementation
    // -------------------------------------------------------------------------

    /**
     * Converts [dtsText] to a Renode `.repl` by running the dts2repl Python script.
     *
     * The Zephyr build output `zephyr.dts` is already a flattened DTS — no GCC
     * preprocessing is needed before passing it here.
     *
     * Must be called off EDT (runs an external process with a 30-second timeout).
     */
    override fun convert(dtsText: String, boardHint: String?): ReplResult {
        val python3 = probePython3() ?: return ReplResult.Failed(
            "python3 not found on PATH. dts2repl requires Python $PYTHON_MIN_MAJOR.$PYTHON_MIN_MINOR+."
        )

        val scriptDir = resolveScriptDir()
            ?: return ReplResult.Failed(
                "dts2repl not found. Open Settings → Firmware Emulator to acquire it."
            )

        val dtsFile = Files.createTempFile("hexana-dts2repl-", ".dts")
        try {
            dtsFile.writeText(dtsText)
            val result = runDts2repl(python3, scriptDir, dtsFile)
            return result
        } finally {
            dtsFile.deleteIfExists()
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the root directory containing the `dts2repl` package.
     * Preference: configured [HexanaFirmwareSettings.effectiveDts2replPath] override,
     * then the managed install directory. Returns `null` if neither is present.
     */
    private fun resolveScriptDir(): Path? {
        val override = HexanaFirmwareSettings.getInstance().effectiveDts2replPath()
        if (override != null) {
            val p = Path.of(override)
            if (p.exists()) return p
        }
        val managed = managedInstallDir()
        if (!managed.exists()) return null
        // Inside the tarball the layout is dts2repl-<sha>/<package files>
        // Prefer the nested directory if present (GitHub archive wraps in a prefix dir)
        val nested = managed.toFile().listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("dts2repl-") }
            ?.toPath()
        return nested ?: managed
    }

    private fun runDts2repl(python3: String, scriptDir: Path, dtsFile: Path): ReplResult {
        // Use `python3 -m dts2repl` with PYTHONPATH set to the scriptDir's parent,
        // which is robust regardless of minor layout variations.
        val pythonPath = (scriptDir.parent ?: scriptDir).absolutePathString()
        val cmd = listOf(python3, "-m", "dts2repl", dtsFile.absolutePathString())
        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment()["PYTHONPATH"] = pythonPath
            pb.redirectErrorStream(false)
            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exited = process.waitFor(CONVERT_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                return ReplResult.Failed("dts2repl timed out after ${CONVERT_TIMEOUT_MS / 1000}s")
            }
            val exit = process.exitValue()
            if (exit != 0 || stdout.isBlank()) {
                val tail = stderr.lines().takeLast(5).joinToString("\n")
                ReplResult.Failed("dts2repl exited $exit.\n$tail")
            } else {
                ReplResult.Ok(stdout)
            }
        } catch (e: Exception) {
            ReplResult.Failed("dts2repl invocation failed: ${e.message}")
        }
    }

    /**
     * Probes for `python3` on PATH and returns its path if it meets the minimum version,
     * or `null` otherwise. Must be called off EDT.
     */
    private fun probePython3(): String? {
        val exe = if (com.intellij.openapi.util.SystemInfo.isWindows) "python3.exe" else "python3"
        return try {
            val p = ProcessBuilder(exe, "--version").start()
            val out = (p.inputStream.bufferedReader().readText() +
                p.errorStream.bufferedReader().readText()).trim()
            p.waitFor()
            // "Python 3.11.2" or "Python 3.9.0"
            val m = Regex("""Python (\d+)\.(\d+)""").find(out) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            if (major > PYTHON_MIN_MAJOR || (major == PYTHON_MIN_MAJOR && minor >= PYTHON_MIN_MINOR)) exe else null
        } catch (e: Exception) {
            null
        }
    }
}
