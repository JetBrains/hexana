package org.jetbrains.hexana.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import org.jetbrains.hexana.ElfFileType
import org.jetbrains.hexana.firmware.ElfSidecarDiscovery
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows a notification strip on ELF files when no capacity source has been
 * auto-discovered or manually linked, prompting the user to link a .map or
 * .dts file so the Memory tab can display capacities.
 *
 * The eligibility check is intentionally shallow (file type only) to avoid
 * triggering ELF parsing on the EDT. The Memory tab itself gates on the full
 * [isMemoryBudgetEligible] result; if the file is not eligible the Memory tab
 * will not appear and the notification is harmless.
 *
 * The user can hide the notification per file; the choice is persisted in [MemoryBudgetSettings].
 */
class ElfMemoryBudgetNotification : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (!looksLikeElf(file)) return null
        if (MemoryBudgetSettings.getInstance(project).isNotificationDismissed(file.path)) return null
        if (hasCapacitySource(project, file)) return null

        return Function {
            val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
            panel.text = "Linker map not found - link a .map or .dts to see capacities"
            panel.createActionLabel("Link capacity source...") {
                if (project.isDisposed) return@createActionLabel
                showCapacityChooser(project, file)
            }
            val dismiss = Runnable {
                if (project.isDisposed) return@Runnable
                MemoryBudgetSettings.getInstance(project).dismissNotification(file.path)
                EditorNotifications.getInstance(project).updateNotifications(file)
            }
            panel.createActionLabel("Don't show again", dismiss)
            panel.setCloseAction(dismiss)
            panel
        }
    }

    private fun looksLikeElf(file: VirtualFile): Boolean =
        file.fileType is ElfFileType || file.extension == "elf" || file.extension == "axf"

    private fun hasCapacitySource(project: Project, file: VirtualFile): Boolean {
        val elfPath = file.path
        val entry = MemoryBudgetSettings.getInstance(project).getEntry(elfPath)
        if (entry != null) {
            if ((entry.linkedFilePath ?: "").isNotEmpty()) return true
            if ((entry.manualFlashOrigin ?: "").isNotEmpty()) return true
        }
        // Quick filesystem check via ElfSidecarDiscovery (VFS findChild calls are EDT-safe).
        // The service's discover() is the canonical walk; here we only need a yes/no answer.
        val sidecars = project.getService(ElfSidecarDiscovery::class.java).discover(file)
        return sidecars.mapFile != null || sidecars.dtsFile != null
    }

    private fun showCapacityChooser(project: Project, file: VirtualFile) {
        val choice = Messages.showYesNoCancelDialog(
            project,
            "Choose how to link the capacity source:",
            "Link Capacity Source",
            "Select .map or .dts file...",
            "Enter manually...",
            "Cancel",
            null,
        )
        when (choice) {
            Messages.YES -> {
                val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                    .withTitle("Link Capacity Source")
                    .withDescription("Select a linker .map or .dts file for this ELF")
                    .withFileFilter { it.extension == "map" || it.extension == "dts" }
                FileChooser.chooseFile(descriptor, project, file) { chosen ->
                    MemoryBudgetSettings.getInstance(project).setLinkedFile(file.path, chosen.path)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
            }
            Messages.NO -> {
                val flashOriginStr = Messages.showInputDialog(
                    project, "Flash origin (hex 0x... or decimal):", "Flash Origin", null,
                ) ?: return
                val flashSizeStr = Messages.showInputDialog(
                    project, "Flash size (hex 0x... or decimal):", "Flash Size", null,
                ) ?: return
                val ramOriginStr = Messages.showInputDialog(
                    project, "RAM origin (hex 0x... or decimal):", "RAM Origin", null,
                ) ?: return
                val ramSizeStr = Messages.showInputDialog(
                    project, "RAM size (hex 0x... or decimal):", "RAM Size", null,
                ) ?: return
                MemoryBudgetSettings.getInstance(project).setManualRegions(
                    elfPath = file.path,
                    flashOrigin = flashOriginStr,
                    flashSize = flashSizeStr,
                    ramOrigin = ramOriginStr,
                    ramSize = ramSizeStr,
                )
                EditorNotifications.getInstance(project).updateNotifications(file)
            }
            else -> return
        }
    }
}
