package org.jetbrains.hexana.actions

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.run.HexanaSuiteConfigurationFactory
import org.jetbrains.hexana.run.HexanaSuiteRunConfiguration
import org.jetbrains.hexana.run.HexanaSuiteRunConfigurationType
import org.jetbrains.hexana.run.collectSuiteDeclarations
import org.jetbrains.hexana.run.matchingSuiteRole
import org.jetbrains.hexana.run.tryGetCachedSuiteDeclarations
import com.intellij.openapi.util.Key
import org.jetbrains.hexana.shared.binaryFormatId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Run suites for this binary": visible in the Hexana binary editor.
 *
 * Scans project *.test.js files using the artifact declaration cache. Only cached
 * results are consulted in update() so the EDT is never blocked. On a cache miss
 * a background fill is kicked off and the action hides until the cache is warm.
 *
 * A suite matches when it has exactly one required (non-optional) declaration whose
 * formats list either is empty (any format) or includes the open binary's format id.
 */
class HexanaRunSuitesForBinaryAction : AnAction(
    "Run Suites for This Binary",
    "Run Hexana test suites that match the open binary's format",
    null,
) {

    private val fillInFlight = AtomicBoolean(false)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(HexanaFileEditor.KEY)
        if (project == null || editor == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val provider = editor.binaryProvider
        if (provider == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val formatId = binaryFormatId(provider)
        val binaryPath = editor.file.path

        val suiteFiles = findSuiteFilesInProject(project)
        val matchingSuites = suiteFiles.mapNotNull { suiteFile ->
            val decls = tryGetCachedSuiteDeclarations(suiteFile.path) ?: return@mapNotNull null
            val role = matchingSuiteRole(decls, formatId) ?: return@mapNotNull null
            SuiteMatch(suiteFile, role)
        }

        if (suiteFiles.isNotEmpty() && suiteFiles.all { tryGetCachedSuiteDeclarations(it.path) == null }) {
            // Nothing is cached yet: kick background warm-up and hide.
            triggerBackgroundCacheFill(suiteFiles)
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isEnabledAndVisible = matchingSuites.isNotEmpty()
        e.presentation.text = when (matchingSuites.size) {
            1 -> "Run Suite: ${matchingSuites.first().suiteFile.nameWithoutExtension}"
            else -> "Run Suites for This Binary (${matchingSuites.size})"
        }
        @Suppress("UNCHECKED_CAST")
        e.presentation.putClientProperty(MATCHES_KEY, matchingSuites as List<SuiteMatch>)
        e.presentation.putClientProperty(BINARY_PATH_KEY, binaryPath)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        @Suppress("UNCHECKED_CAST")
        val matchingSuites = e.presentation.getClientProperty(MATCHES_KEY) as? List<SuiteMatch>
            ?: return
        val binaryPath = e.presentation.getClientProperty(BINARY_PATH_KEY) as? String ?: return
        if (matchingSuites.isEmpty()) return

        if (matchingSuites.size == 1) {
            runSuite(project, matchingSuites.first(), binaryPath)
        } else {
            showChooser(e, project, matchingSuites, binaryPath)
        }
    }

    private fun runSuite(project: Project, match: SuiteMatch, binaryPath: String) {
        val settings = findOrCreateSuiteConfiguration(project, match, binaryPath)
        val executor = ExecutorRegistry.getInstance().getExecutorById("Run") ?: return
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    private fun showChooser(
        e: AnActionEvent,
        project: Project,
        matches: List<SuiteMatch>,
        binaryPath: String,
    ) {
        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(matches)
            .setTitle("Choose Suite")
            .setItemChosenCallback { match ->
                runSuite(project, match, binaryPath)
            }
            .setRenderer(object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component {
                    val m = value as? SuiteMatch
                    return super.getListCellRendererComponent(
                        list,
                        m?.suiteFile?.name ?: value,
                        index,
                        isSelected,
                        cellHasFocus,
                    )
                }
            })
            .createPopup()
        val component = e.inputEvent?.component
        if (component != null) popup.showUnderneathOf(component) else popup.showInFocusCenter()
    }

    /**
     * Returns an existing run configuration for the suite file if one exists,
     * or creates and registers a new one. Reuse-and-rebind is intentional: an
     * existing configuration for this suite gets its artifact mapping overwritten
     * with the currently selected binary, so the same configuration stays current
     * without proliferating duplicates.
     */
    private fun findOrCreateSuiteConfiguration(
        project: Project,
        match: SuiteMatch,
        binaryPath: String,
    ): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val type = ConfigurationTypeUtil.findConfigurationType(HexanaSuiteRunConfigurationType::class.java)
        val configName = match.suiteFile.nameWithoutExtension

        // Reuse an existing configuration for this suite file if there is one.
        val existing = runManager.allSettings.firstOrNull { settings ->
            settings.type == type &&
                (settings.configuration as? HexanaSuiteRunConfiguration)?.suitePath == match.suiteFile.path
        }
        if (existing != null) {
            val cfg = existing.configuration as HexanaSuiteRunConfiguration
            cfg.artifactValues = listOf("${match.role}=$binaryPath")
            cfg.freeFormArtifacts = ""
            return existing
        }

        val settings = runManager.createConfiguration(configName, type.configurationFactories
            .first { it is HexanaSuiteConfigurationFactory })
        val cfg = settings.configuration as HexanaSuiteRunConfiguration
        cfg.suitePath = match.suiteFile.path
        cfg.artifactValues = listOf("${match.role}=$binaryPath")
        runManager.addConfiguration(settings)
        return settings
    }

    private fun findSuiteFilesInProject(project: Project): List<VirtualFile> {
        return ReadAction.compute<List<VirtualFile>, Exception> {
            val result = mutableListOf<VirtualFile>()
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (!file.isDirectory && file.name.endsWith(".test.js")) result.add(file)
                true
            }
            result
        }
    }

    private fun triggerBackgroundCacheFill(suiteFiles: List<VirtualFile>) {
        if (!fillInFlight.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                for (suiteFile in suiteFiles) {
                    if (tryGetCachedSuiteDeclarations(suiteFile.path) == null) {
                        collectSuiteDeclarations(suiteFile.path)
                    }
                }
            } finally {
                fillInFlight.set(false)
            }
        }
    }

    companion object {
        private val MATCHES_KEY = Key.create<List<SuiteMatch>>("hexana.suiteMatches")
        private val BINARY_PATH_KEY = Key.create<String>("hexana.binaryPath")
    }
}

private data class SuiteMatch(
    val suiteFile: VirtualFile,
    val role: String,
)
