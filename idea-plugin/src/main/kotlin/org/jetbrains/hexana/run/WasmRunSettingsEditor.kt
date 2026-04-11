package org.jetbrains.hexana.run

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.hexana.HexanaBundle
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class WasmRunSettingsEditor(private val project: Project) : SettingsEditor<WasmRunConfiguration>() {

    private val engineCombo = ComboBox(DefaultComboBoxModel(WasmEngine.entries.toTypedArray())).apply {
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                super.getListCellRendererComponent(list, (value as? WasmEngine)?.displayName ?: value, index, isSelected, cellHasFocus)
                return this
            }
        }
    }
    private val wasmFileField = TextFieldWithBrowseButton()
    private val wasmtimePathField = TextFieldWithBrowseButton()
    private val wasmtimeOptionsField = RawCommandLineEditor()
    private val programArgumentsField = RawCommandLineEditor()
    private val workingDirectoryField = TextFieldWithBrowseButton()
    private val dependenciesField = JBTextArea(3, 40).apply { isEditable = false }

    private var wasmtimePathRow: Row? = null
    private var wasmtimeOptionsRow: Row? = null

    init {
        wasmFileField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile()
                .withFileFilter { it.extension == "wasm" },
        )
        wasmtimePathField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile(),
        )
        (wasmtimePathField.childComponent as? JBTextField)?.emptyText?.setText(WasmtimeCommandLineState.DEFAULT_WASMTIME_PATH)
        workingDirectoryField.addBrowseFolderListener(
            project,
            FileChooserDescriptor(false, true, false, false, false, false),
        )
        engineCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                updateWasmtimeFieldsVisibility()
            }
        }
    }

    private fun updateWasmtimeFieldsVisibility() {
        val isWasmtime = engineCombo.item == WasmEngine.WASMTIME
        wasmtimePathRow?.visible(isWasmtime)
        wasmtimeOptionsRow?.visible(isWasmtime)
    }

    override fun resetEditorFrom(configuration: WasmRunConfiguration) {
        engineCombo.item = configuration.engine
        wasmFileField.text = configuration.wasmFilePath
        wasmtimePathField.text = configuration.wasmtimePath
        wasmtimeOptionsField.text = configuration.wasmtimeOptions
        programArgumentsField.text = configuration.programArguments
        workingDirectoryField.text = configuration.workingDirectory
        dependenciesField.text = configuration.dependencies.joinToString("\n")
        updateWasmtimeFieldsVisibility()
    }

    override fun applyEditorTo(configuration: WasmRunConfiguration) {
        configuration.engine = engineCombo.item as WasmEngine
        configuration.wasmFilePath = wasmFileField.text
        configuration.wasmtimePath = wasmtimePathField.text
        configuration.wasmtimeOptions = wasmtimeOptionsField.text
        configuration.programArguments = programArgumentsField.text
        configuration.workingDirectory = workingDirectoryField.text
    }

    override fun createEditor(): JComponent = panel {
        row(HexanaBundle.message("wasm.run.configuration.engine")) {
            cell(engineCombo).align(AlignX.FILL)
        }
        row(HexanaBundle.message("wasm.run.configuration.wasmFile")) {
            cell(wasmFileField).align(AlignX.FILL)
        }
        wasmtimePathRow = row(HexanaBundle.message("wasm.run.configuration.wasmtime.path")) {
            cell(wasmtimePathField).align(AlignX.FILL)
        }
        wasmtimeOptionsRow = row(HexanaBundle.message("wasm.run.configuration.wasmtime.options")) {
            cell(wasmtimeOptionsField).align(AlignX.FILL)
        }
        row(HexanaBundle.message("wasm.run.configuration.programArguments")) {
            cell(programArgumentsField).align(AlignX.FILL)
        }
        row(HexanaBundle.message("wasm.run.configuration.workingDirectory")) {
            cell(workingDirectoryField).align(AlignX.FILL)
        }
        row(HexanaBundle.message("wasm.run.configuration.dependencies")) {
            scrollCell(dependenciesField).align(AlignX.FILL)
        }
    }
}
