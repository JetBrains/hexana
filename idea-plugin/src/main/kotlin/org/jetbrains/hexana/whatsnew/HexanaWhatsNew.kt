package org.jetbrains.hexana.whatsnew

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.HexanaPluginInfo
import org.jetbrains.hexana.HexanaSettings
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon

internal class HexanaWhatsNewStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isHeadlessEnvironment || application.isUnitTestMode || application.isCommandLine) return

        val settings = HexanaSettings.getInstance()
        val currentVersion = currentHexanaMajorMinorVersion() ?: return
        if (!shouldShowWhatsNewOnStartup(settings.showWhatsNewOnNewVersion, settings.lastShownWhatsNewVersion, currentVersion)) {
            return
        }
        if (resolveWhatsNewResourceVersion(currentVersion) == null) return
        if (!wasStarted.compareAndSet(false, true)) return

        val wasWhatsNewOpened = withContext(Dispatchers.EDT) {
            HexanaWhatsNewOpener.open(project, currentVersion)
        }
        if (wasWhatsNewOpened) {
            settings.lastShownWhatsNewVersion = currentVersion
        } else {
            wasStarted.set(false)
        }
    }

    companion object {
        private val wasStarted = AtomicBoolean(false)
    }
}

internal class HexanaWhatsNewAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val version = currentHexanaMajorMinorVersion() ?: HexanaBundle.message("hexana.whatsNew.currentVersion")
        HexanaWhatsNewOpener.open(project, version)
    }
}

internal class HexanaWhatsNewFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is HexanaWhatsNewVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return HexanaWhatsNewFileEditor(file as HexanaWhatsNewVirtualFile)
    }

    override fun getEditorTypeId(): @NonNls String = "hexana-whats-new-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

internal object HexanaWhatsNewOpener {
    fun open(project: Project, version: String): Boolean {
        if (resolveWhatsNewResourceVersion(version) == null) return false
        FileEditorManager.getInstance(project).openFile(HexanaWhatsNewVirtualFile(version), true)
        return true
    }
}

internal fun currentHexanaMajorMinorVersion(): String? {
    val version = HexanaPluginInfo.descriptor?.version ?: return null
    return majorMinorVersion(version)
}

internal fun shouldShowWhatsNewOnStartup(
    isEnabled: Boolean,
    lastShownVersion: String?,
    currentVersion: String?,
): Boolean {
    return isEnabled && currentVersion != null && currentVersion != lastShownVersion
}

internal fun majorMinorVersion(version: String): String? {
    val match = Regex("""^\s*(\d+)\.(\d+)""").find(version) ?: return null
    return "${match.groupValues[1]}.${match.groupValues[2]}"
}

internal class HexanaWhatsNewVirtualFile(
    val version: String,
) : LightVirtualFile(
    HexanaBundle.message("hexana.whatsNew.fileName", version),
    HexanaWhatsNewFileType.INSTANCE,
    "",
) {
    override fun isWritable(): Boolean = false
}

private class HexanaWhatsNewFileType private constructor() : FileType {
    companion object {
        val INSTANCE = HexanaWhatsNewFileType()
    }

    override fun getName(): String = "Hexana what's new"

    override fun getDescription(): String = "Hexana what's new page"

    override fun getDefaultExtension(): String = "hexana-whats-new"

    override fun getIcon(): Icon = AllIcons.General.Information

    override fun isBinary(): Boolean = false
}
