package org.jetbrains.hexana.jit

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.Key
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import org.jdom.Element
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Optionally injects the Hexana JIT Viewer agent (`libjitview.dylib/.so/.dll`,
 * shipped alongside the plugin) into Java-based run configurations.
 *
 * The user opts in per run configuration via the "JIT Viewer" tab; when
 * enabled, [updateJavaParameters] appends `-agentpath:<extracted-libjitview>`
 * to the launched JVM. The agent records every JIT-compiled method into a
 * `mem.userDump` file written to the run's working directory; that file
 * is what `JitFile.parse` consumes.
 *
 * Hosts without a shipped agent slice for their OS/arch see the checkbox
 * disabled and a "not available" hint instead.
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
        params.vmParametersList.add("-agentpath:${agentPath.toAbsolutePath()}")
    }

    override fun <P : RunConfigurationBase<*>> createEditor(configuration: P): SettingsEditor<P> =
        JitviewSettingsEditor()

    override fun readExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val child = element.getChild(SETTINGS_ELEMENT) ?: return
        val enabled = child.getAttributeValue(ATTR_ENABLED)?.toBoolean() ?: false
        runConfiguration.putCopyableUserData(SETTINGS_KEY, JitviewSettings(enabled))
    }

    override fun writeExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val settings = runConfiguration.getCopyableUserData(SETTINGS_KEY) ?: return
        if (!settings.enabled) return // keep run-config XML lean — only write when on
        element.addContent(
            Element(SETTINGS_ELEMENT).apply {
                setAttribute(ATTR_ENABLED, settings.enabled.toString())
            },
        )
    }

    companion object {
        const val SETTINGS_ELEMENT: String = "hexana-jitview"
        const val ATTR_ENABLED: String = "enabled"
        val SETTINGS_KEY: Key<JitviewSettings> = Key.create("hexana.jitview.settings")
    }
}

/** Persisted state for the JIT Viewer tab on a single run configuration. */
data class JitviewSettings(val enabled: Boolean)

/**
 * "JIT Viewer" tab — single checkbox + a hint line telling the user where
 * the dump lands and whether a native agent slice is shipped for their host.
 */
private class JitviewSettingsEditor<P : RunConfigurationBase<*>> : SettingsEditor<P>() {
    private val checkbox = JBCheckBox("Attach Hexana JIT Viewer agent")
    private val statusLabel = JBLabel(buildAvailabilityHint())

    override fun resetEditorFrom(configuration: P) {
        val settings = configuration.getCopyableUserData(JitviewRunConfigurationExtension.SETTINGS_KEY)
        checkbox.isSelected = settings?.enabled ?: false
        val available = JitviewAgentLocator.locate() != null
        checkbox.isEnabled = available
        statusLabel.text = buildAvailabilityHint()
    }

    override fun applyEditorTo(configuration: P) {
        configuration.putCopyableUserData(
            JitviewRunConfigurationExtension.SETTINGS_KEY,
            JitviewSettings(checkbox.isSelected),
        )
    }

    override fun createEditor(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)
        checkbox.toolTipText =
            "Adds -agentpath:<libjitview> to the launched JVM. The agent dumps every JIT-compiled " +
                "method into mem.userDump under the run's working directory."
        panel.add(checkbox)
        panel.add(statusLabel)
        return panel
    }

    private fun buildAvailabilityHint(): String {
        val agent = JitviewAgentLocator.locate()
        return if (agent != null) {
            "<html>Dump file: <code>mem.userDump</code> in the run's working directory.<br/>" +
                "Agent: <code>${agent}</code></html>"
        } else {
            val os = System.getProperty("os.name")
            val arch = System.getProperty("os.arch")
            "<html>No JVMTI agent slice is shipped for <code>$os ($arch)</code>; " +
                "rebuild the plugin on this host or attach a CI-produced slice.</html>"
        }
    }
}
