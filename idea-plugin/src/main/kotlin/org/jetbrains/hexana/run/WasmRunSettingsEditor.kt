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

internal data class ParsedInvoke(
    val exportName: String,
    val inlineArgs: String,
    val remainingOptions: String,
)

/**
 * Recognises the standard `--invoke` patterns that Hexana generates:
 *  - `--invoke exportName`            (core-module style)
 *  - `--invoke "exportName(a, b)"`    (component style)
 *
 * Returns `null` when `wasmtimeOptions` does not contain `--invoke`.
 */
internal fun parseStandardInvoke(wasmtimeOptions: String): ParsedInvoke? {
    val trimmed = wasmtimeOptions.trim()
    if (!trimmed.contains("--invoke")) return null

    // Match: --invoke "name(args)" or --invoke name
    val quotedPattern = Regex("""--invoke\s+"([^"]+)"""")
    val simplePattern = Regex("""--invoke\s+(\S+)""")

    val quotedMatch = quotedPattern.find(trimmed)
    val invokeValue: String
    val matchRange: IntRange

    if (quotedMatch != null) {
        invokeValue = quotedMatch.groupValues[1]
        matchRange = quotedMatch.range
    } else {
        val simpleMatch = simplePattern.find(trimmed) ?: return null
        invokeValue = simpleMatch.groupValues[1]
        matchRange = simpleMatch.range
    }

    val remaining = (trimmed.removeRange(matchRange)).trim()

    // Check for component-style "exportName(args)"
    val parenIdx = invokeValue.indexOf('(')
    return if (parenIdx >= 0 && invokeValue.endsWith(')')) {
        val name = invokeValue.substring(0, parenIdx)
        val args = invokeValue.substring(parenIdx + 1, invokeValue.length - 1)
        ParsedInvoke(name, args, remaining)
    } else {
        ParsedInvoke(invokeValue, "", remaining)
    }
}

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
    private val exportNameField = JBTextField()
    private val programArgumentsField = RawCommandLineEditor()
    private val workingDirectoryField = TextFieldWithBrowseButton()
    private val dependenciesField = JBTextArea(3, 40).apply { isEditable = false }

    private var wasmtimePathRow: Row? = null
    private var wasmtimeOptionsRow: Row? = null
    private var exportNameRow: Row? = null

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
                val newEngine = engineCombo.item as? WasmEngine ?: return@addItemListener
                migrateInvokeArgs(newEngine)
                updateFieldsVisibility()
            }
        }
    }

    /**
     * When the user switches engine, migrate the standard `--invoke` wasmtime
     * option to/from the engine-independent [exportName] and [programArguments]
     * fields so the configuration stays meaningful for the new engine.
     */
    private fun migrateInvokeArgs(newEngine: WasmEngine) {
        when (newEngine) {
            WasmEngine.GRAALWASM -> migrateWasmtimeToGraalWasm()
            WasmEngine.WASMTIME -> migrateGraalWasmToWasmtime()
        }
    }

    private fun migrateWasmtimeToGraalWasm() {
        val options = wasmtimeOptionsField.text.trim()
        val parsed = parseStandardInvoke(options) ?: return
        exportNameField.text = parsed.exportName
        if (parsed.inlineArgs.isNotEmpty()) {
            programArgumentsField.text = parsed.inlineArgs
        }
        wasmtimeOptionsField.text = parsed.remainingOptions
    }

    private fun migrateGraalWasmToWasmtime() {
        val name = exportNameField.text.trim().ifBlank { return }
        val currentOptions = wasmtimeOptionsField.text.trim()
        if (currentOptions.contains("--invoke")) return

        val args = programArgumentsField.text.trim()
        val invokeOption = if (args.contains(",")) {
            "--invoke \"$name($args)\""
        } else {
            "--invoke $name"
        }
        wasmtimeOptionsField.text = if (currentOptions.isEmpty()) invokeOption else "$currentOptions $invokeOption"
    }


    private fun updateFieldsVisibility() {
        val isWasmtime = engineCombo.item == WasmEngine.WASMTIME
        wasmtimePathRow?.visible(isWasmtime)
        wasmtimeOptionsRow?.visible(isWasmtime)
        exportNameRow?.visible(!isWasmtime)
    }

    override fun resetEditorFrom(configuration: WasmRunConfiguration) {
        engineCombo.item = configuration.engine
        wasmFileField.text = configuration.wasmFilePath
        wasmtimePathField.text = configuration.wasmtimePath
        wasmtimeOptionsField.text = configuration.wasmtimeOptions
        exportNameField.text = configuration.exportName
        programArgumentsField.text = configuration.programArguments
        workingDirectoryField.text = configuration.workingDirectory
        dependenciesField.text = configuration.dependencies.joinToString("\n")
        updateFieldsVisibility()
    }

    override fun applyEditorTo(configuration: WasmRunConfiguration) {
        configuration.engine = engineCombo.item as WasmEngine
        configuration.wasmFilePath = wasmFileField.text
        configuration.wasmtimePath = wasmtimePathField.text
        configuration.wasmtimeOptions = wasmtimeOptionsField.text
        configuration.exportName = exportNameField.text
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
        exportNameRow = row(HexanaBundle.message("wasm.run.configuration.exportName")) {
            cell(exportNameField).align(AlignX.FILL)
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
