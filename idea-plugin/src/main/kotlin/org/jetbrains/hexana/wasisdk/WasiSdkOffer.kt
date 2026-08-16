package org.jetbrains.hexana.wasisdk

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import org.jetbrains.hexana.HexanaBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * User-visible notifications and dialogs for managed wasi-sdk.
 *
 * [showLldbMissingNotification] is called from [org.jetbrains.hexana.run.WasmDebugRunner]
 * when LLDB resolution fails. The notification offers to download or configure LLDB.
 */
object WasiSdkOffer {

    private val LOG = Logger.getInstance(WasiSdkOffer::class.java)

    // ── Notification ──────────────────────────────────────────────────────────

    /**
     * Shows a "LLDB not found" balloon notification with actions to download
     * the latest wasi-sdk or open settings.
     */
    fun showLldbMissingNotification(project: Project?) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("hexana")
            .createNotification(
                HexanaBundle.message("hexana.notification.wasisdk.lldb.missing.title"),
                HexanaBundle.message("hexana.notification.wasisdk.lldb.missing.content"),
                NotificationType.WARNING,
            )
        addDownloadAndSettingsActions(notification, project)
        notification.notify(project)
    }

    /**
     * Shows a "wasi-sdk is not installed" balloon offering the download.
     * Used when a wasm debug session starts on a discovered system LLDB
     * with no managed wasi-sdk present; purely informational, the session
     * proceeds. Callers are responsible for not showing it repeatedly.
     */
    fun showWasiSdkAbsentNotification(project: Project?) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("hexana")
            .createNotification(
                HexanaBundle.message("hexana.notification.wasisdk.absent.title"),
                HexanaBundle.message("hexana.notification.wasisdk.absent.content"),
                NotificationType.INFORMATION,
            )
        addDownloadAndSettingsActions(notification, project)
        notification.notify(project)
    }

    private fun addDownloadAndSettingsActions(
        notification: com.intellij.notification.Notification,
        project: Project?,
    ) {
        notification.addAction(object : com.intellij.openapi.actionSystem.AnAction(
            HexanaBundle.message("hexana.notification.wasisdk.lldb.missing.action.download"),
        ) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                notification.expire()
                showVersionChooserDialog(project)
            }
        })

        notification.addAction(object : com.intellij.openapi.actionSystem.AnAction(
            HexanaBundle.message("hexana.notification.wasisdk.lldb.missing.action.settings"),
        ) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                notification.expire()
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "wasm.runtime.settings")
            }
        })
    }

    /**
     * Shows the version chooser dialog, fetching available versions in the background.
     *
     * @param onInstalled called on the EDT with the installed version number after a successful
     *   install. Use this to refresh UI state (e.g. the wasi-sdk combo in Settings).
     */
    fun showVersionChooserDialog(project: Project?, onInstalled: (Int) -> Unit = {}) {
        // Fetch releases off the EDT
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            HexanaBundle.message("hexana.notification.wasisdk.fetching.releases"),
            false,
        ) {
            private var result: WasiSdkReleasesClient.FetchResult? = null

            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val storage = WasiSdkStorage.getInstance()
                val client = WasiSdkReleasesClient(storage)
                result = client.fetchReleases()
            }

            override fun onSuccess() {
                val fetchResult = result ?: return
                invokeLater {
                    val dialog = VersionChooserDialog(project, fetchResult)
                    if (dialog.showAndGet()) {
                        val selected = dialog.selectedRelease ?: return@invokeLater
                        WasiSdkInstaller.install(project, selected, onInstalled)
                    }
                }
            }

            override fun onThrowable(error: Throwable) {
                LOG.warn("Failed to fetch wasi-sdk releases for chooser dialog", error)
                notify(project, "Failed to fetch wasi-sdk releases: ${error.message}", NotificationType.ERROR)
            }
        })
    }

    /** Posts a bare notification to the hexana group. */
    fun notify(project: Project?, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("hexana")
            .createNotification(content, type)
            .notify(project)
    }

    // ── Version chooser dialog ─────────────────────────────────────────────

    class VersionChooserDialog(
        private val project: Project?,
        private val fetchResult: WasiSdkReleasesClient.FetchResult,
    ) : DialogWrapper(project) {

        private val storage = WasiSdkStorage.getInstance()
        private val installedVersions = storage.installedVersions().toSet()
        private val latestVersion = fetchResult.releases.firstOrNull()?.version

        private val combo = ComboBox<WasiSdkReleasesClient.AvailableRelease>()

        var selectedRelease: WasiSdkReleasesClient.AvailableRelease? = null
            private set

        init {
            title = HexanaBundle.message("hexana.wasisdk.chooser.title")
            init()
        }

        override fun createCenterPanel(): JComponent {
            fetchResult.releases.forEach { combo.addItem(it) }
            combo.renderer = ReleaseRenderer()
            combo.preferredSize = Dimension(480, combo.preferredSize.height)

            val staleLabel = if (fetchResult.stale) {
                JLabel(HexanaBundle.message("hexana.wasisdk.chooser.stale"))
            } else null

            val panel = JPanel(BorderLayout(4, 4))
            panel.add(combo, BorderLayout.CENTER)
            if (staleLabel != null) panel.add(staleLabel, BorderLayout.SOUTH)
            return panel
        }

        override fun doOKAction() {
            selectedRelease = combo.selectedItem as? WasiSdkReleasesClient.AvailableRelease
            super.doOKAction()
        }

        private inner class ReleaseRenderer : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val release = value as? WasiSdkReleasesClient.AvailableRelease
                if (release != null) {
                    val mb = release.sizeBytes / (1024 * 1024)
                    val tags = buildList<String> {
                        if (release.version == latestVersion) add("latest")
                        if (release.version in installedVersions) add("installed")
                    }
                    val tagStr = if (tags.isEmpty()) "" else "  [${tags.joinToString(", ")}]"
                    label.text = "wasi-sdk-${release.version}  (~${mb} MB)$tagStr"
                }
                return label
            }
        }
    }
}
