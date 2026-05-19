package org.jetbrains.hexana.jit

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import org.jdom.Element
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Optionally injects the Hexana JIT Viewer agent (`libjitview.dylib/.so/.dll`,
 * shipped alongside the plugin) into Java-based run configurations.
 *
 * The user opts in per run configuration via the "JIT Viewer" tab; when
 * enabled, [updateJavaParameters] appends
 * `-agentpath:<libjitview>=<dump-path>` to the launched JVM. The agent
 * writes every JIT-compiled method into the named file, which lands in the
 * run's working directory when the configured path is relative. After the
 * run terminates successfully, [attachToProcess]'s listener refreshes VFS
 * and opens the dump in Hexana's editor.
 */
class JitviewRunConfigurationExtension : RunConfigurationExtension() {

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = true

    override fun isEnabledFor(applicableConfiguration: RunConfigurationBase<*>, runnerSettings: RunnerSettings?): Boolean =
        true

    /**
     * Non-Java command-line patch hook — required by [RunConfigurationExtension]
     * but not exercised for our use case (we mutate [JavaParameters] in
     * [updateJavaParameters] instead). No-op.
     */
    override fun patchCommandLine(
        configuration: RunConfigurationBase<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String,
    ) {
        // intentionally empty
    }

    override fun getEditorTitle(): String = "JIT Viewer"

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        val settings = configuration.getCopyableUserData(SETTINGS_KEY) ?: return
        if (!settings.enabled) return
        val agentPath = JitviewAgentLocator.locate() ?: return

        // Resolve the dump path against the run's working directory so we can
        // both pass an absolute path to the agent (it stores relative-to-CWD
        // otherwise) and find the file again from attachToProcess later.
        val configuredPath = settings.dumpPath.ifBlank { JitviewSettings.DEFAULT_DUMP_PATH }
        val resolvedDump = File(configuredPath).let { req ->
            if (req.isAbsolute) req else File(params.workingDirectory ?: "", req.path)
        }.absoluteFile

        val agentOptions = buildString {
            append(resolvedDump.path)
            // Only emit `log=` when it diverges from the agent's default so
            // the agentpath stays clean in the launched-process command line.
            if (settings.logLevel != JitviewLogLevel.Off) {
                append(',')
                append("log=")
                append(settings.logLevel.cliValue)
            }
            if (settings.snapshotOnUnload) {
                append(',')
                append("snapshot_on_unload")
            }
        }
        params.vmParametersList.add("-agentpath:${agentPath.toAbsolutePath()}=$agentOptions")
        configuration.putCopyableUserData(RESOLVED_DUMP_KEY, resolvedDump.path)
    }

    override fun attachToProcess(
        configuration: RunConfigurationBase<*>,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?,
    ) {
        val settings = configuration.getCopyableUserData(SETTINGS_KEY) ?: return
        if (!settings.enabled) return
        val resolvedDump = configuration.getCopyableUserData(RESOLVED_DUMP_KEY) ?: return
        val project = configuration.project

        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                // Don't try to open a half-written dump if the JVM crashed
                // before Agent_OnUnload had a chance to finalise it.
                if (event.exitCode != 0) return
                ApplicationManager.getApplication().invokeLater {
                    val vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedDump)
                        ?: return@invokeLater
                    FileEditorManager.getInstance(project).openFile(vfile, true)
                }
            }
        })
    }

    override fun <P : RunConfigurationBase<*>> createEditor(configuration: P): SettingsEditor<P> =
        JitviewSettingsEditor()

    override fun readExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val child = element.getChild(SETTINGS_ELEMENT) ?: return
        val enabled = child.getAttributeValue(ATTR_ENABLED)?.toBoolean() ?: false
        val dumpPath = child.getAttributeValue(ATTR_DUMP_PATH).orEmpty()
            .ifBlank { JitviewSettings.DEFAULT_DUMP_PATH }
        val logLevel = child.getAttributeValue(ATTR_LOG_LEVEL)
            ?.let { stored -> JitviewLogLevel.entries.firstOrNull { it.cliValue == stored } }
            ?: JitviewLogLevel.Off
        val snapshotOnUnload = child.getAttributeValue(ATTR_SNAPSHOT_ON_UNLOAD)?.toBoolean() ?: false
        runConfiguration.putCopyableUserData(SETTINGS_KEY, JitviewSettings(enabled, dumpPath, logLevel, snapshotOnUnload))
    }

    override fun writeExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val settings = runConfiguration.getCopyableUserData(SETTINGS_KEY) ?: return
        // Only persist when at least one field diverges from defaults so the
        // run-config XML stays lean for users who never touch the tab.
        if (!settings.enabled &&
            settings.dumpPath == JitviewSettings.DEFAULT_DUMP_PATH &&
            settings.logLevel == JitviewLogLevel.Off &&
            !settings.snapshotOnUnload
        ) {
            return
        }
        element.addContent(
            Element(SETTINGS_ELEMENT).apply {
                setAttribute(ATTR_ENABLED, settings.enabled.toString())
                setAttribute(ATTR_DUMP_PATH, settings.dumpPath)
                if (settings.logLevel != JitviewLogLevel.Off) {
                    setAttribute(ATTR_LOG_LEVEL, settings.logLevel.cliValue)
                }
                if (settings.snapshotOnUnload) {
                    setAttribute(ATTR_SNAPSHOT_ON_UNLOAD, "true")
                }
            },
        )
    }

    companion object {
        const val SETTINGS_ELEMENT: String = "hexana-jitview"
        const val ATTR_ENABLED: String = "enabled"
        const val ATTR_DUMP_PATH: String = "dump-path"
        const val ATTR_LOG_LEVEL: String = "log-level"
        const val ATTR_SNAPSHOT_ON_UNLOAD: String = "snapshot-on-unload"
        val SETTINGS_KEY: Key<JitviewSettings> = Key.create("hexana.jitview.settings")
        val RESOLVED_DUMP_KEY: Key<String> = Key.create("hexana.jitview.resolvedDumpPath")
    }
}

/** Persisted state for the JIT Viewer tab on a single run configuration. */
data class JitviewSettings(
    val enabled: Boolean = false,
    val dumpPath: String = DEFAULT_DUMP_PATH,
    val logLevel: JitviewLogLevel = JitviewLogLevel.Off,
    /**
     * When true, passes `snapshot_on_unload` to the agent, causing it to
     * write a post-patch snapshot of each method's native bytes inside the
     * `CompiledMethodUnload` callback. Adds one synchronous `write()` per
     * unloaded JIT-compiled method; HotSpot-only tested.
     */
    val snapshotOnUnload: Boolean = false,
) {
    companion object {
        const val DEFAULT_DUMP_PATH: String = "default.jit"
    }
}

/**
 * Logging verbosity passed to the agent via the `log=` option. Values map
 * 1:1 to the agent's [LOG_OFF | LOG_INFO | LOG_DEBUG] levels in `agent.c`.
 */
enum class JitviewLogLevel(val cliValue: String, val label: String) {
    Off("off", "Off"),
    Info("info", "Info (load/unload summary)"),
    Debug("debug", "Debug (one line per JIT event)"),
    ;
    override fun toString(): String = label
}

/**
 * "JIT Viewer" tab — checkbox + dump-path field + availability hint.
 */
private class JitviewSettingsEditor<P : RunConfigurationBase<*>> : SettingsEditor<P>() {
    private val checkbox = JBCheckBox("Attach Hexana JIT Viewer agent")
    private val dumpPathField = JBTextField(JitviewSettings.DEFAULT_DUMP_PATH)
    private val dumpPathLabel = JLabel("Dump file:")
    private val logLevelLabel = JLabel("Log level:")
    private val logLevelCombo = JComboBox(DefaultComboBoxModel(JitviewLogLevel.entries.toTypedArray()))
    private val snapshotCheckbox = JBCheckBox("Capture post-patch snapshot on method unload")
    private val statusLabel = JBLabel(buildAvailabilityHint())

    override fun resetEditorFrom(configuration: P) {
        val settings = configuration.getCopyableUserData(JitviewRunConfigurationExtension.SETTINGS_KEY)
        checkbox.isSelected = settings?.enabled ?: false
        dumpPathField.text = settings?.dumpPath?.takeIf { it.isNotBlank() }
            ?: JitviewSettings.DEFAULT_DUMP_PATH
        logLevelCombo.selectedItem = settings?.logLevel ?: JitviewLogLevel.Off
        snapshotCheckbox.isSelected = settings?.snapshotOnUnload ?: false
        val available = JitviewAgentLocator.locate() != null
        checkbox.isEnabled = available
        dumpPathField.isEnabled = available
        dumpPathLabel.isEnabled = available
        logLevelCombo.isEnabled = available
        logLevelLabel.isEnabled = available
        snapshotCheckbox.isEnabled = available
        statusLabel.text = buildAvailabilityHint()
    }

    override fun applyEditorTo(configuration: P) {
        configuration.putCopyableUserData(
            JitviewRunConfigurationExtension.SETTINGS_KEY,
            JitviewSettings(
                enabled = checkbox.isSelected,
                dumpPath = dumpPathField.text.ifBlank { JitviewSettings.DEFAULT_DUMP_PATH },
                logLevel = (logLevelCombo.selectedItem as? JitviewLogLevel) ?: JitviewLogLevel.Off,
                snapshotOnUnload = snapshotCheckbox.isSelected,
            ),
        )
    }

    override fun createEditor(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)
        val c = GridBagConstraints().apply {
            gridx = 0; gridy = 0; gridwidth = 2
            anchor = GridBagConstraints.WEST
            insets = Insets(0, 0, 6, 0)
        }
        panel.add(checkbox, c)

        c.gridy = 1; c.gridwidth = 1
        c.insets = Insets(0, 0, 4, 6)
        panel.add(dumpPathLabel, c)

        c.gridx = 1
        c.fill = GridBagConstraints.HORIZONTAL
        c.weightx = 1.0
        panel.add(dumpPathField, c)

        c.gridx = 0; c.gridy = 2; c.gridwidth = 1
        c.weightx = 0.0; c.fill = GridBagConstraints.NONE
        c.insets = Insets(0, 0, 4, 6)
        panel.add(logLevelLabel, c)

        c.gridx = 1
        c.fill = GridBagConstraints.HORIZONTAL
        c.weightx = 1.0
        panel.add(logLevelCombo, c)

        c.gridx = 0; c.gridy = 3; c.gridwidth = 2
        c.weightx = 0.0; c.fill = GridBagConstraints.NONE
        c.insets = Insets(0, 0, 4, 0)
        panel.add(snapshotCheckbox, c)

        c.gridy = 4
        c.insets = Insets(8, 0, 0, 0)
        panel.add(statusLabel, c)

        checkbox.toolTipText =
            "Adds -agentpath:<libjitview>=<dump-file> to the launched JVM and opens the dump " +
                "in Hexana when the run completes."
        dumpPathField.toolTipText =
            "Path passed to the agent as its dump-file argument. Relative paths resolve against " +
                "the run's working directory."
        logLevelCombo.toolTipText =
            "Verbosity of the agent's stderr output. \"Off\" is fully silent; \"Info\" emits a " +
                "load/unload summary; \"Debug\" prints one line per JIT-compiled method."
        snapshotCheckbox.toolTipText =
            "Passes snapshot_on_unload to the agent. Inside each CompiledMethodUnload callback the " +
                "agent performs a synchronous write() of the method's native bytes in their final " +
                "post-patch state. Adds one I/O per JIT-compiled method unload; HotSpot-only tested."
        return panel
    }

    private fun buildAvailabilityHint(): String {
        val agent = JitviewAgentLocator.locate()
        return if (agent != null) {
            "<html>Agent: <code>${agent}</code></html>"
        } else {
            val os = System.getProperty("os.name")
            val arch = System.getProperty("os.arch")
            "<html>No JVMTI agent slice is shipped for <code>$os ($arch)</code>; " +
                "rebuild the plugin on this host or attach a CI-produced slice.</html>"
        }
    }
}
