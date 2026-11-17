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
    private val wamrPathField = TextFieldWithBrowseButton()
    private val wamrOptionsField = RawCommandLineEditor()
    private val graalVmPathField = TextFieldWithBrowseButton()
    private val nodePathField = TextFieldWithBrowseButton()
    private val nodeOptionsField = RawCommandLineEditor()
    private val chicoryPathField = TextFieldWithBrowseButton()
    private val chicoryOptionsField = RawCommandLineEditor()
    private val wazeroPathField = TextFieldWithBrowseButton()
    private val wazeroOptionsField = RawCommandLineEditor()
    private val lldbPathField = TextFieldWithBrowseButton()
    private val chromePathField = TextFieldWithBrowseButton()
    private val exportNameField = JBTextField()
    private val programArgumentsField = RawCommandLineEditor()
    private val workingDirectoryField = TextFieldWithBrowseButton()
    private val dependenciesField = JBTextArea(3, 40).apply { isEditable = false }

    private var wasmtimePathRow: Row? = null
    private var wasmtimeOptionsRow: Row? = null
    private var wamrPathRow: Row? = null
    private var wamrOptionsRow: Row? = null
    private var graalVmPathRow: Row? = null
    private var nodePathRow: Row? = null
    private var nodeOptionsRow: Row? = null
    private var chicoryPathRow: Row? = null
    private var chicoryOptionsRow: Row? = null
    private var wazeroPathRow: Row? = null
    private var wazeroOptionsRow: Row? = null
    private var lldbPathRow: Row? = null
    private var chromePathRow: Row? = null
    private var exportNameRow: Row? = null
    private var noDebugHintRow: Row? = null
    private val noDebugHintLabel = javax.swing.JLabel()

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
        (wasmtimePathField.childComponent as? JBTextField)?.emptyText?.setText(
            WasmRuntimeSettings.getInstance().effectiveWasmtimePath()
        )
        wamrPathField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile(),
        )
        (wamrPathField.childComponent as? JBTextField)?.emptyText?.setText(
            WasmRuntimeSettings.getInstance().effectiveWamrPath()
        )
        graalVmPathField.addBrowseFolderListener(
            project,
            FileChooserDescriptor(false, true, false, false, false, false),
        )
        val effectiveGraalVm = WasmRuntimeSettings.getInstance().effectiveGraalVmPath()
        if (effectiveGraalVm != null) {
            (graalVmPathField.childComponent as? JBTextField)?.emptyText?.setText(effectiveGraalVm)
        }
        nodePathField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile(),
        )
        (nodePathField.childComponent as? JBTextField)?.emptyText?.setText(
            WasmRuntimeSettings.getInstance().effectiveNodePath()
        )
        chicoryPathField.addBrowseFolderListener(
            project,
            FileChooserDescriptor(false, true, false, false, false, false),
        )
        val effectiveChicoryJdk = WasmRuntimeSettings.getInstance().effectiveChicoryJdkPath()
        if (effectiveChicoryJdk != null) {
            (chicoryPathField.childComponent as? JBTextField)?.emptyText?.setText(effectiveChicoryJdk)
        }
        wazeroPathField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile(),
        )
        (wazeroPathField.childComponent as? JBTextField)?.emptyText?.setText(
            WasmRuntimeSettings.getInstance().effectiveWazeroPath()
        )
        lldbPathField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile(),
        )
        (lldbPathField.childComponent as? JBTextField)?.emptyText?.setText(
            WasmRuntimeSettings.getInstance().effectiveLldbPath()
        )
        chromePathField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile(),
        )
        (chromePathField.childComponent as? JBTextField)?.emptyText?.setText(
            WasmRuntimeSettings.getInstance().effectiveChromePath()
        )
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
            WasmEngine.WAMR -> migrateWasmtimeToGraalWasm()
            WasmEngine.BROWSER -> migrateWasmtimeToGraalWasm()
            WasmEngine.NODE_JS -> migrateWasmtimeToGraalWasm()
            WasmEngine.CHICORY_INTERPRETER -> migrateWasmtimeToGraalWasm()
            WasmEngine.CHICORY_AOT -> migrateWasmtimeToGraalWasm()
            WasmEngine.WAZERO -> migrateWasmtimeToGraalWasm()
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
        val selected = engineCombo.item
        val isWasmtime = selected == WasmEngine.WASMTIME
        val isWamr = selected == WasmEngine.WAMR
        val isGraalWasm = selected == WasmEngine.GRAALWASM
        val isNodeJs = selected == WasmEngine.NODE_JS
        val isBrowser = selected == WasmEngine.BROWSER
        val isChicory = selected == WasmEngine.CHICORY_INTERPRETER || selected == WasmEngine.CHICORY_AOT
        val isWazero = selected == WasmEngine.WAZERO
        wasmtimePathRow?.visible(isWasmtime)
        wasmtimeOptionsRow?.visible(isWasmtime)
        wamrPathRow?.visible(isWamr)
        wamrOptionsRow?.visible(isWamr)
        graalVmPathRow?.visible(isGraalWasm)
        nodePathRow?.visible(isNodeJs)
        nodeOptionsRow?.visible(isNodeJs)
        chicoryPathRow?.visible(isChicory)
        chicoryOptionsRow?.visible(false)
        wazeroPathRow?.visible(isWazero)
        wazeroOptionsRow?.visible(isWazero)
        lldbPathRow?.visible(isWasmtime || isWamr)
        chromePathRow?.visible(isBrowser)
        exportNameRow?.visible(!isWasmtime)
        val showNoDebugHint = !selected.supportsDebug
        if (showNoDebugHint) {
            noDebugHintLabel.text = if (isWazero) {
                HexanaBundle.message("wasm.run.configuration.no.debug.hint.wazero")
            } else {
                HexanaBundle.message("wasm.run.configuration.no.debug.hint", selected.displayName)
            }
        }
        noDebugHintRow?.visible(showNoDebugHint)
    }

    override fun resetEditorFrom(configuration: WasmRunConfiguration) {
        engineCombo.item = configuration.engine
        wasmFileField.text = configuration.wasmFilePath
        wasmtimePathField.text = configuration.wasmtimePath
        wasmtimeOptionsField.text = configuration.wasmtimeOptions
        wamrPathField.text = configuration.wamrPath
        wamrOptionsField.text = configuration.wamrOptions
        graalVmPathField.text = configuration.graalVmPath
        nodePathField.text = configuration.nodePath
        nodeOptionsField.text = configuration.nodeOptions
        chicoryPathField.text = configuration.chicoryPath
        chicoryOptionsField.text = configuration.chicoryOptions
        wazeroPathField.text = configuration.wazeroPath
        wazeroOptionsField.text = configuration.wazeroOptions
        lldbPathField.text = configuration.lldbPath
        chromePathField.text = configuration.chromePath
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
        configuration.wamrPath = wamrPathField.text
        configuration.wamrOptions = wamrOptionsField.text
        configuration.graalVmPath = graalVmPathField.text
        configuration.nodePath = nodePathField.text
        configuration.nodeOptions = nodeOptionsField.text
        configuration.chicoryPath = chicoryPathField.text
        configuration.chicoryOptions = chicoryOptionsField.text
        configuration.wazeroPath = wazeroPathField.text
        configuration.wazeroOptions = wazeroOptionsField.text
        configuration.lldbPath = lldbPathField.text
        configuration.chromePath = chromePathField.text
        configuration.exportName = exportNameField.text
        configuration.programArguments = programArgumentsField.text
        configuration.workingDirectory = workingDirectoryField.text
    }

    override fun createEditor(): JComponent = panel {
        row(HexanaBundle.message("wasm.run.configuration.engine")) {
            cell(engineCombo).align(AlignX.FILL)
        }
        noDebugHintRow = row {
            cell(noDebugHintLabel)
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
        wamrPathRow = row(HexanaBundle.message("wasm.run.configuration.wamr.path")) {
            cell(wamrPathField).align(AlignX.FILL)
        }
        wamrOptionsRow = row(HexanaBundle.message("wasm.run.configuration.wamr.options")) {
            cell(wamrOptionsField).align(AlignX.FILL)
        }
        graalVmPathRow = row(HexanaBundle.message("wasm.run.configuration.graalvm.path")) {
            cell(graalVmPathField).align(AlignX.FILL)
        }
        nodePathRow = row(HexanaBundle.message("wasm.run.configuration.node.path")) {
            cell(nodePathField).align(AlignX.FILL)
        }
        nodeOptionsRow = row(HexanaBundle.message("wasm.run.configuration.node.options")) {
            cell(nodeOptionsField).align(AlignX.FILL)
        }
        chicoryPathRow = row(HexanaBundle.message("wasm.run.configuration.chicory.path")) {
            cell(chicoryPathField).align(AlignX.FILL)
        }
        chicoryOptionsRow = row(HexanaBundle.message("wasm.run.configuration.chicory.options")) {
            cell(chicoryOptionsField).align(AlignX.FILL)
        }
        wazeroPathRow = row(HexanaBundle.message("wasm.run.configuration.wazero.path")) {
            cell(wazeroPathField).align(AlignX.FILL)
        }
        wazeroOptionsRow = row(HexanaBundle.message("wasm.run.configuration.wazero.options")) {
            cell(wazeroOptionsField).align(AlignX.FILL)
        }
        lldbPathRow = row(HexanaBundle.message("wasm.run.configuration.lldb.path")) {
            cell(lldbPathField).align(AlignX.FILL)
        }
        chromePathRow = row(HexanaBundle.message("wasm.run.configuration.chrome.path")) {
            cell(chromePathField).align(AlignX.FILL)
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
