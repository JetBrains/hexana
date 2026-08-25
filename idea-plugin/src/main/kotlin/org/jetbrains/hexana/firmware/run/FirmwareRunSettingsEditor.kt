package org.jetbrains.hexana.firmware.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.SpinnerNumberModel

/**
 * Settings editor for [FirmwareRunConfiguration].
 *
 * Layout:
 * - ELF path (file chooser, .elf filter)
 * - DTS path (file chooser, optional — prefilled by producer from sidecar discovery)
 * - Config path (file chooser, optional)
 * - Emulator selector (Recommended / QEMU / Renode)
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

    override fun createEditor(): JComponent = panel {
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
}
