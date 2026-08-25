package org.jetbrains.hexana.firmware.run

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.hexana.firmware.Dts2ReplRunner
import org.jetbrains.hexana.firmware.EmulatorDetection
import org.jetbrains.hexana.firmware.HexanaFirmwareSettings
import org.jetbrains.hexana.firmware.RenodeDownloader
import org.jetbrains.hexana.firmware.PythonRuntimeDownloader
import org.jetbrains.hexana.firmware.ZephyrToolchainDownloader
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SpinnerNumberModel

/**
 * Settings editor for [FirmwareRunConfiguration].
 *
 * Layout:
 * - ELF path (file chooser, .elf filter)
 * - DTS path (file chooser, optional — prefilled by producer from sidecar discovery)
 * - Config path (file chooser, optional)
 * - Emulator selector (Recommended / QEMU / Renode)
 * - Tools section: per-tool status labels + download buttons (runs off-EDT; disposal-safe)
 * - Collapsed "Advanced" group: extra QEMU args, extra .resc lines,
 *   stop-at-entry combo, startup timeout
 */
class FirmwareRunSettingsEditor(private val project: Project) : SettingsEditor<FirmwareRunConfiguration>() {

    private val elfField = TextFieldWithBrowseButton()
    private val dtsField = TextFieldWithBrowseButton()
    private val configField = TextFieldWithBrowseButton()
    private val emulatorCombo = ComboBox(DefaultComboBoxModel(EmulatorChoice.entries.toTypedArray())).apply {
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val display = (value as? EmulatorChoice)?.displayName ?: value
                super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus)
                return this
            }
        }
    }
    private val extraQemuArgsField = RawCommandLineEditor()
    private val extraRescLinesField = RawCommandLineEditor()
    private val stopAtEntryCombo = ComboBox(DefaultComboBoxModel(StopAtEntry.entries.toTypedArray())).apply {
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val display = (value as? StopAtEntry)?.displayName ?: value
                super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus)
                return this
            }
        }
    }
    private val timeoutSpinner = javax.swing.JSpinner(SpinnerNumberModel(30, 1, 3600, 5))

    // --- Tools section state ---
    // Labels and buttons are created once in createEditor() and updated off-EDT.
    // Null until createEditor() is called; null-safe guards prevent callbacks from
    // firing after the editor is disposed.
    private var renodeStatusLabel: JLabel? = null
    private var renodeDownloadButton: JButton? = null
    private var dts2replStatusLabel: JLabel? = null
    private var dts2replFetchButton: JButton? = null
    private var gdbStatusLabel: JLabel? = null
    private var gdbDownloadButton: JButton? = null
    private var pythonStatusLabel: JLabel? = null
    private var pythonDownloadButton: JButton? = null

    // Tracks whether a download is in progress to disable buttons.
    @Volatile private var downloadInProgress = false

    init {
        elfField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile()
                .withFileFilter { it.extension == "elf" || it.name == "zephyr.elf" },
        )
        dtsField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile()
                .withFileFilter { it.extension == "dts" || it.name == "zephyr.dts" },
        )
        (dtsField.childComponent as? JBTextField)?.emptyText?.setText("Discovered automatically")
        configField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile()
                .withFileFilter { it.name == ".config" || it.extension == "config" },
        )
        (configField.childComponent as? JBTextField)?.emptyText?.setText("Discovered automatically")
    }

    override fun resetEditorFrom(s: FirmwareRunConfiguration) {
        elfField.text = s.elfPath
        dtsField.text = s.dtsPath
        configField.text = s.configPath
        emulatorCombo.item = s.emulator
        extraQemuArgsField.text = s.extraQemuArgs
        extraRescLinesField.text = s.extraRescLines
        stopAtEntryCombo.item = s.stopAtEntry
        timeoutSpinner.value = s.startupTimeoutSeconds
    }

    override fun applyEditorTo(s: FirmwareRunConfiguration) {
        s.elfPath = elfField.text
        s.dtsPath = dtsField.text
        s.configPath = configField.text
        s.emulator = emulatorCombo.item as EmulatorChoice
        s.extraQemuArgs = extraQemuArgsField.text
        s.extraRescLines = extraRescLinesField.text
        s.stopAtEntry = stopAtEntryCombo.item as StopAtEntry
        s.startupTimeoutSeconds = (timeoutSpinner.value as Number).toInt()
    }

    override fun createEditor(): JComponent {
        // Create tool widgets before building the panel so they can be embedded.
        val renodeLabel = JLabel("Checking…")
        val renodeBtn = JButton("Download Renode ${RenodeDownloader.PINNED_RENODE_VERSION}…").apply {
            toolTipText = "Download the Renode ${RenodeDownloader.PINNED_RENODE_VERSION} portable package."
            addActionListener { onDownloadRenode() }
        }
        val dts2replLabel = JLabel("Checking…")
        val dts2replBtn = JButton("Fetch dts2repl (commit ${Dts2ReplRunner.PINNED_DTS2REPL_COMMIT.take(7)})…").apply {
            toolTipText = "Download the pinned dts2repl snapshot from GitHub (Apache-2.0). Requires Python 3.9+."
            addActionListener { onFetchDts2repl() }
        }
        val gdbLabel = JLabel("Checking…")
        val gdbBtn = JButton("Download GDB toolchain…").apply {
            toolTipText = "Download the Zephyr SDK GDB toolchain (GPLv3, contains arm-zephyr-eabi-gdb-py)."
            addActionListener { onDownloadGdb() }
        }

        val pythonLabel = JLabel("Checking…")
        val pythonBtn = JButton(
            "Download Python runtime (~${PythonRuntimeDownloader.approxSizeMb()} MB)…"
        ).apply {
            toolTipText = "Download CPython ${PythonRuntimeDownloader.PINNED_PYTHON_VERSION} " +
                "(python-build-standalone, PSF license). Required for dts2repl / Renode Generated platform."
            addActionListener { onDownloadPython() }
        }

        renodeStatusLabel = renodeLabel
        renodeDownloadButton = renodeBtn
        dts2replStatusLabel = dts2replLabel
        dts2replFetchButton = dts2replBtn
        gdbStatusLabel = gdbLabel
        gdbDownloadButton = gdbBtn
        pythonStatusLabel = pythonLabel
        pythonDownloadButton = pythonBtn

        val editorPanel = panel {
            row("ELF file:") {
                cell(elfField).align(AlignX.FILL)
            }
            row("DTS file:") {
                cell(dtsField).align(AlignX.FILL)
            }
            row("Config file:") {
                cell(configField).align(AlignX.FILL)
            }
            row("Emulator:") {
                cell(emulatorCombo).align(AlignX.FILL)
            }
            group("Tools") {
                row("QEMU:") {
                    comment("Install via package manager: <code>brew install qemu</code> / <code>apt install qemu-system-arm</code>")
                }
                row("Renode:") {
                    cell(renodeLabel)
                }
                row {
                    cell(renodeBtn)
                }
                row("dts2repl:") {
                    cell(dts2replLabel)
                }.comment("Required for Renode with generated platform descriptions.")
                row {
                    cell(dts2replBtn)
                }
                row("Python:") {
                    cell(pythonLabel)
                }.comment("Required to run dts2repl for Renode Generated-platform targets.")
                row {
                    cell(pythonBtn)
                }
                row("GDB (DAP):") {
                    cell(gdbLabel)
                }.comment("Required for debugging. Minimum GDB 14+ with Python.")
                row {
                    cell(gdbBtn)
                }
            }
            collapsibleGroup("Advanced") {
                row("Extra QEMU args:") {
                    cell(extraQemuArgsField).align(AlignX.FILL)
                }
                row("Extra .resc lines:") {
                    cell(extraRescLinesField).align(AlignX.FILL)
                }
                row("Stop at entry:") {
                    cell(stopAtEntryCombo).align(AlignX.FILL)
                }
                row("Startup timeout (s):") {
                    cell(timeoutSpinner)
                }
            }
        }

        scheduleVersionProbe()
        return editorPanel
    }

    // -------------------------------------------------------------------------
    // Download actions — called on EDT
    // -------------------------------------------------------------------------

    private fun onDownloadRenode() {
        setDownloading(true)
        RenodeDownloader.downloadWithConsent(project) {
            setDownloading(false)
            refreshVersionLabels()
        }
    }

    private fun onFetchDts2repl() {
        setDownloading(true)
        Dts2ReplRunner.downloadWithConsent(project) {
            setDownloading(false)
            refreshVersionLabels()
        }
    }

    private fun onDownloadPython() {
        setDownloading(true)
        PythonRuntimeDownloader.downloadWithConsent(project) {
            setDownloading(false)
            refreshVersionLabels()
        }
    }

    private fun onDownloadGdb() {
        val target = resolveGdbToolchainTarget()
        if (target == null) {
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "No suitable Zephyr SDK toolchain is available for this OS/arch combination.",
                "GDB Toolchain Download",
                javax.swing.JOptionPane.ERROR_MESSAGE,
            )
            return
        }
        setDownloading(true)
        ZephyrToolchainDownloader.downloadWithConsent(project, target) {
            setDownloading(false)
            refreshVersionLabels()
        }
    }

    private fun resolveGdbToolchainTarget(): String? {
        // Use arm-zephyr-eabi as the default target (covers Cortex-M, the most common case).
        // Returns null when the OS is unsupported (Windows .7z).
        val target = "arm-zephyr-eabi"
        return if (ZephyrToolchainDownloader.downloadUrlFor(target) != null) target else null
    }

    /** Disables/re-enables all download buttons while a download modal is running. */
    private fun setDownloading(inProgress: Boolean) {
        downloadInProgress = inProgress
        invokeLater {
            val enabled = !inProgress
            renodeDownloadButton?.isEnabled = enabled
            dts2replFetchButton?.isEnabled = enabled
            gdbDownloadButton?.isEnabled = enabled
            pythonDownloadButton?.isEnabled = enabled
        }
    }

    // -------------------------------------------------------------------------
    // Off-EDT version probing — disposal-safe
    // -------------------------------------------------------------------------

    private fun scheduleVersionProbe() {
        CoroutineScope(Dispatchers.IO).launch {
            val renode = EmulatorDetection.detectRenode()
            val gdb = EmulatorDetection.detectGdb()
            val dts2replOk = Dts2ReplRunner.isInstalled(Dts2ReplRunner.managedInstallDir()) ||
                HexanaFirmwareSettings.getInstance().effectiveDts2replPath() != null
            val python = Dts2ReplRunner.resolvePython()
            invokeLater { applyProbeResults(renode, gdb, dts2replOk, python) }
        }
    }

    private fun refreshVersionLabels() {
        CoroutineScope(Dispatchers.IO).launch {
            val renode = EmulatorDetection.detectRenode()
            val gdb = EmulatorDetection.detectGdb()
            val dts2replOk = Dts2ReplRunner.isInstalled(Dts2ReplRunner.managedInstallDir()) ||
                HexanaFirmwareSettings.getInstance().effectiveDts2replPath() != null
            val python = Dts2ReplRunner.resolvePython()
            invokeLater { applyProbeResults(renode, gdb, dts2replOk, python) }
        }
    }

    /**
     * Updates status labels and button states from probe results.
     * Guards against null (editor disposed before callback fired).
     */
    private fun applyProbeResults(
        renode: EmulatorDetection.DetectedTool?,
        gdb: EmulatorDetection.DetectedTool?,
        dts2replInstalled: Boolean,
        python: String?,
    ) {
        if (downloadInProgress) return // Don't overwrite while a download is in flight.

        renodeStatusLabel?.text = toolStatus(renode)
        renodeDownloadButton?.isEnabled = (renode == null || !renode.meetsMinimum)

        dts2replStatusLabel?.text = if (dts2replInstalled) "Installed" else "Not installed"
        dts2replFetchButton?.isEnabled = !dts2replInstalled

        pythonStatusLabel?.text = if (python != null) "Found: $python" else "Not found"
        pythonDownloadButton?.isEnabled = (python == null)

        gdbStatusLabel?.text = toolStatus(gdb)
        val gdbTarget = resolveGdbToolchainTarget()
        gdbDownloadButton?.isEnabled = (gdb == null) && (gdbTarget != null)
        if (gdbTarget == null) {
            gdbDownloadButton?.toolTipText =
                "GDB toolchain download is not supported on this OS/arch combination."
        } else if (gdb == null) {
            gdbDownloadButton?.text =
                "Download GDB toolchain ($gdbTarget, ${ZephyrToolchainDownloader.approxSizeMb(gdbTarget)} MB)…"
        }
    }

    private fun toolStatus(tool: EmulatorDetection.DetectedTool?): String = when {
        tool == null -> "Not found"
        tool.version == null -> "Found at ${tool.path} (version unknown)"
        tool.meetsMinimum -> "Found ${tool.version} at ${tool.path}"
        else -> "Found ${tool.version} at ${tool.path} — below minimum"
    }
}
