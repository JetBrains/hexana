package org.jetbrains.hexana.wasisdk

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.io.Decompressor
import com.intellij.util.io.HttpRequests
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.run.WasmRuntimeSettings
import org.jetbrains.hexana.shared.WasiSdkReleases
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * Downloads and installs a wasi-sdk version into [WasiSdkStorage.root].
 *
 * Each install runs as a [Task.Backgroundable] so it never blocks the EDT.
 * On success the user is offered an explicit action to write the managed
 * lldb path into the existing [WasmRuntimeSettings] lldbPath field.
 * The resolution order of [WasmRuntimeSettings.effectiveLldbPath] is unchanged.
 */
object WasiSdkInstaller {

    private val LOG = Logger.getInstance(WasiSdkInstaller::class.java)

    /**
     * Starts a background install of [release].
     *
     * @param project the project context for the progress task (may be null for app-level)
     * @param release the release to install
     * @param onSuccess called on the EDT after a successful install
     */
    fun install(
        project: Project?,
        release: WasiSdkReleasesClient.AvailableRelease,
        onSuccess: (Int) -> Unit = {},
    ) {
        val storage = WasiSdkStorage.getInstance()
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                project,
                "Downloading wasi-sdk ${release.version}",
                true,
            ) {
                private var installedLldbPath: String? = null

                override fun run(indicator: ProgressIndicator) {
                    installedLldbPath = doInstall(storage, release, indicator)
                }

                override fun onSuccess() {
                    val lldbPath = installedLldbPath
                    if (lldbPath != null) {
                        showInstallSuccessNotification(project, release.version, lldbPath)
                    }
                    onSuccess(release.version)
                }

                override fun onThrowable(error: Throwable) {
                    if (error is ProcessCanceledException) return
                    LOG.warn("wasi-sdk install failed: ${error.message}", error)
                    WasiSdkOffer.notify(
                        project,
                        "wasi-sdk install failed: ${error.message}",
                        NotificationType.ERROR,
                    )
                }
            },
        )
    }

    /**
     * Returns the path to the installed lldb binary on success; throws on failure.
     */
    private fun doInstall(
        storage: WasiSdkStorage,
        release: WasiSdkReleasesClient.AvailableRelease,
        indicator: ProgressIndicator,
    ): String {
        storage.sweepTmp()

        val lock = storage.acquireLock()
            ?: throw IOException("Another wasi-sdk install is already in progress")

        val tempFile = storage.downloadTempFile(release.version)
        val stagingDir = storage.stagingDir(release.version)
        val targetDir = storage.installDir(release.version)

        try {
            indicator.isIndeterminate = false

            // Step 1: download
            indicator.text = "Downloading wasi-sdk ${release.version}..."
            indicator.fraction = 0.0
            HttpRequests.request(release.downloadUrl).saveToFile(tempFile, indicator)
            indicator.fraction = 0.5

            // Step 2: verify sha256
            val expectedSha256 = release.sha256
            if (expectedSha256 != null) {
                indicator.text = "Verifying wasi-sdk ${release.version}..."
                val actual = sha256Hex(tempFile)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw IOException(
                        "SHA-256 mismatch for wasi-sdk ${release.version}: " +
                            "expected $expectedSha256, got $actual",
                    )
                }
            }
            indicator.fraction = 0.6

            // Step 3: extract into staging
            indicator.text = "Extracting wasi-sdk ${release.version}..."
            stagingDir.toFile().deleteRecursively()
            Files.createDirectories(stagingDir)
            val topDir = WasiSdkReleases.archiveTopLevelDir(
                release.version,
                storage.currentOs(),
                storage.currentArch(),
            )
            Decompressor.Tar(tempFile)
                .removePrefixPath(topDir)
                .extract(stagingDir)
            indicator.fraction = 0.9

            // Step 4: validate lldb binary in staging
            val lldbInStaging = stagingDir.resolve(WasiSdkReleases.lldbRelativePath(storage.currentOs()))
            if (!lldbInStaging.exists()) {
                throw IOException("lldb binary not found after extraction: $lldbInStaging")
            }

            // Step 5: atomic move staging -> target
            indicator.text = "Installing wasi-sdk ${release.version}..."
            targetDir.toFile().deleteRecursively()
            Files.move(stagingDir, targetDir, StandardCopyOption.ATOMIC_MOVE)
            indicator.fraction = 1.0

            // Step 6: update manifest (records the install; does NOT touch lldbPath setting)
            storage.recordInstalled(release.version)

            return targetDir.resolve(WasiSdkReleases.lldbRelativePath(storage.currentOs())).toString()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            // Clean up on failure; keep previous state intact
            stagingDir.toFile().deleteRecursively()
            throw e
        } finally {
            tempFile.deleteIfExists()
            storage.releaseLock(lock)
        }
    }

    /**
     * Shows an install-success notification with a "Use as LLDB path" action.
     *
     * Clicking the action writes [lldbPath] into [WasmRuntimeSettings.lldbPathInUi],
     * the same field the textFieldWithBrowseButton in Settings binds. The user can
     * see and clear it there like any manually-entered path.
     * The resolution order of [WasmRuntimeSettings.effectiveLldbPath] is unchanged.
     */
    private fun showInstallSuccessNotification(project: Project?, version: Int, lldbPath: String) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("hexana")
            .createNotification(
                HexanaBundle.message("hexana.notification.wasisdk.installed.title", version),
                HexanaBundle.message("hexana.notification.wasisdk.installed.content"),
                NotificationType.INFORMATION,
            )

        notification.addAction(object : com.intellij.openapi.actionSystem.AnAction(
            HexanaBundle.message("hexana.notification.wasisdk.installed.action.use"),
        ) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                notification.expire()
                WasmRuntimeSettings.getInstance().lldbPathInUi = lldbPath
                WasiSdkOffer.notify(
                    project,
                    HexanaBundle.message("hexana.notification.wasisdk.lldb.set", lldbPath),
                    NotificationType.INFORMATION,
                )
            }
        })

        notification.notify(project)
    }

    private fun sha256Hex(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).buffered(8192).use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
