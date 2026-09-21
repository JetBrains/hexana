package org.jetbrains.hexana.run

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaScriptRunner
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSource
import org.jetbrains.hexana.shared.scripting.FlagSpec
import org.jetbrains.hexana.shared.scripting.FlagType
import java.awt.datatransfer.StringSelection
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.table.AbstractTableModel

/** Caches collected flagDeclarations by script sha-256, so re-opening the editor is instant. */
private val flagDeclCache = ConcurrentHashMap<String, Map<String, FlagSpec>>()

class HexanaScriptRunSettingsEditor(
    private val project: Project,
) : SettingsEditor<HexanaScriptRunConfiguration>() {

    // --- Fields ---

    private val scriptField = TextFieldWithBrowseButton()
    private val baseDirField = TextFieldWithBrowseButton()
    private val copyCliButton = JButton("Copy CLI Command")

    /** Input paths list model. */
    private val inputsModel = DefaultListModel<String>()
    private val inputsList = JBList(inputsModel)

    /** Flags table model. Null = degraded mode, use freeFormField. */
    private var flagTableModel: FlagTableModel? = null
    private val flagTable = JTable()
    private val freeFormField = JTextField()
    private val flagsStatusLabel = JLabel()

    /** Track the last script path for which we triggered a collection pass. */
    private var lastCollectedScript = ""

    init {
        scriptField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile()
                .withFileFilter { it.extension == "js" },
        )
        baseDirField.addBrowseFolderListener(
            project,
            // chooseFiles=false, chooseFolders=true, chooseJars=false, chooseJarsAsFiles=false,
            // chooseJarContents=false, chooseMultiple=false
            FileChooserDescriptor(false, true, false, false, false, false),
        )
        scriptField.textField.addActionListener { onScriptChanged() }
        scriptField.childComponent.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) { onScriptChanged() }
        })
        copyCliButton.addActionListener { copyCliToClipboard() }
    }

    private fun onScriptChanged() {
        val path = scriptField.text.trim()
        if (path == lastCollectedScript || path.isEmpty()) return
        lastCollectedScript = path
        triggerFlagsCollection(path)
    }

    /**
     * Runs a collection pass off the EDT: loads the script (with a stub binary),
     * reads flagDeclarations, updates the table via invokeLater.
     * Results are cached by sha-256 of the script file contents.
     */
    private fun triggerFlagsCollection(scriptPath: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val declarations = collectFlagDeclarations(scriptPath)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                applyFlagDeclarations(declarations)
            }
        }
    }

    private fun applyFlagDeclarations(declarations: Map<String, FlagSpec>?) {
        if (declarations == null || declarations.isEmpty()) {
            // Degraded: show free-form field
            flagTableModel = null
            updateFlagsVisibility()
            flagsStatusLabel.text = if (declarations == null) "Flag declarations unavailable" else "No flags declared"
        } else {
            val model = FlagTableModel(FlagsFormModel.fromDeclarations(declarations).rows.toMutableList())
            flagTableModel = model
            flagTable.model = model
            updateFlagsVisibility()
            flagsStatusLabel.text = "${declarations.size} flag(s)"
        }
    }

    private fun updateFlagsVisibility() {
        val hasFlagTable = flagTableModel != null
        flagTable.isVisible = hasFlagTable
        flagTable.tableHeader?.isVisible = hasFlagTable
        freeFormField.isVisible = !hasFlagTable
    }

    private fun copyCliToClipboard() {
        val cfg = HexanaScriptRunConfiguration(project, HexanaScriptConfigurationFactory(HexanaScriptRunConfigurationType()), "tmp")
        applyEditorTo(cfg)
        val flagModel = flagTableModel
        val flagTokens = if (flagModel != null) {
            buildFlagArgvTokens(FlagsFormModel(rows = flagModel.rows, degraded = false), "")
        } else {
            buildFlagArgvTokens(FlagsFormModel.Empty, freeFormField.text)
        }
        val cmd = buildCliCommand(
            scriptPath = cfg.scriptPath,
            inputPaths = cfg.inputPaths,
            baselinesDir = cfg.baselinesDir,
            flagTokens = flagTokens,
        )
        CopyPasteManager.getInstance().setContents(StringSelection(cmd.joinToString(" ")))
    }

    override fun resetEditorFrom(s: HexanaScriptRunConfiguration) {
        scriptField.text = s.scriptPath
        baseDirField.text = s.baselinesDir
        freeFormField.text = s.freeFormFlags

        inputsModel.clear()
        s.inputPaths.forEach { inputsModel.addElement(it) }

        // Restore flag table from stored values if declarations are cached
        val path = s.scriptPath
        if (path.isNotEmpty()) {
            val cached = tryGetCachedDeclarations(path)
            if (cached != null) {
                val model = FlagTableModel(FlagsFormModel.fromDeclarations(cached).rows.toMutableList())
                // Load stored values into rows
                for (entry in s.flagValues) {
                    val eq = entry.indexOf('=')
                    if (eq < 0) continue
                    val name = entry.substring(0, eq)
                    val value = entry.substring(eq + 1)
                    model.rows.find { it.name == name }?.value = if (value == "__BOOL_TRUE__") "true" else value
                }
                flagTableModel = model
                flagTable.model = model
            }
            updateFlagsVisibility()
            if (path != lastCollectedScript) {
                lastCollectedScript = path
                triggerFlagsCollection(path)
            }
        }
    }

    override fun applyEditorTo(s: HexanaScriptRunConfiguration) {
        s.scriptPath = scriptField.text.trim()
        s.baselinesDir = baseDirField.text.trim()
        s.freeFormFlags = freeFormField.text.trim()

        val inputs = mutableListOf<String>()
        for (i in 0 until inputsModel.size()) inputs.add(inputsModel.getElementAt(i))
        s.inputPaths = inputs

        val model = flagTableModel
        if (model != null) {
            s.flagValues = model.rows.mapNotNull { row ->
                val v = row.value ?: return@mapNotNull null
                if (row.spec.type == FlagType.BOOL && v == "true") "${row.name}=__BOOL_TRUE__"
                else if (row.spec.type == FlagType.BOOL && v != "true") null
                else "${row.name}=$v"
            }
        } else {
            s.flagValues = emptyList()
        }
    }

    override fun createEditor(): JComponent {
        val inputsDecorator = ToolbarDecorator.createDecorator(inputsList)
            .setAddAction {
                val chooser = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.singleFile()
                val files = com.intellij.openapi.fileChooser.FileChooser.chooseFiles(chooser, project, null)
                for (f in files) inputsModel.addElement(f.path)
            }
            .setRemoveAction {
                val idx = inputsList.selectedIndex
                if (idx >= 0) inputsModel.remove(idx)
            }
            .setMoveUpAction {
                val idx = inputsList.selectedIndex
                if (idx > 0) {
                    val a = inputsModel.get(idx - 1)
                    val b = inputsModel.get(idx)
                    inputsModel.set(idx - 1, b)
                    inputsModel.set(idx, a)
                    inputsList.selectedIndex = idx - 1
                }
            }
            .setMoveDownAction {
                val idx = inputsList.selectedIndex
                if (idx >= 0 && idx < inputsModel.size() - 1) {
                    val a = inputsModel.get(idx)
                    val b = inputsModel.get(idx + 1)
                    inputsModel.set(idx, b)
                    inputsModel.set(idx + 1, a)
                    inputsList.selectedIndex = idx + 1
                }
            }
            .createPanel()

        val flagsDecorator = ToolbarDecorator.createDecorator(flagTable).disableAddAction().disableRemoveAction().createPanel()

        updateFlagsVisibility()

        return panel {
            row("Script:") {
                cell(scriptField).align(AlignX.FILL)
            }
            row("Input binaries (first = primary):") {}
            row {
                cell(inputsDecorator).align(AlignX.FILL)
            }
            row("Baselines dir:") {
                cell(baseDirField).align(AlignX.FILL)
            }
            row("Flags:") {
                cell(flagsStatusLabel)
            }
            row {
                cell(flagsDecorator).align(AlignX.FILL)
                cell(freeFormField).align(AlignX.FILL)
            }
            row {
                cell(copyCliButton)
            }
        }
    }
}

/** Simple MutableTableModel for the flags table (Name / Type / Default / Value). */
private class FlagTableModel(val rows: MutableList<FlagRow>) : AbstractTableModel() {
    private val columns = listOf("Name", "Type", "Default", "Value")

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 3

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val row = rows.getOrNull(rowIndex) ?: return null
        return when (columnIndex) {
            0 -> row.name
            1 -> row.spec.type.name.lowercase()
            2 -> row.spec.default ?: ""
            3 -> row.value ?: row.spec.default ?: ""
            else -> null
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex != 3) return
        rows.getOrNull(rowIndex)?.value = aValue?.toString()?.takeIf { it.isNotEmpty() }
        fireTableCellUpdated(rowIndex, columnIndex)
    }
}

/**
 * Runs the script against a stub wasm binary (magic+version) to collect flagDeclarations.
 * Returns null on any failure. Results are cached by sha-256.
 */
internal fun collectFlagDeclarations(scriptPath: String): Map<String, FlagSpec>? {
    return try {
        val file = java.io.File(scriptPath)
        if (!file.exists()) return null
        val cacheKey = sha256Hex(file.readBytes())
        flagDeclCache[cacheKey]?.let { return it }

        val runner = HexanaScriptRunner(
            limits = HexanaScriptLimits(),
            allowedFilePolicy = AllowedFilePolicy.allowAll,
        )
        val stubWasm = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val result = runner.run(BinaryInput.RawBytes(stubWasm), HexanaScriptSource.FilePath(scriptPath))
        val decls = result.flagDeclarations
        flagDeclCache[cacheKey] = decls
        decls
    } catch (_: Exception) {
        null
    }
}

private fun tryGetCachedDeclarations(scriptPath: String): Map<String, FlagSpec>? {
    return try {
        val file = java.io.File(scriptPath)
        if (!file.exists()) return null
        val key = sha256Hex(file.readBytes())
        flagDeclCache[key]
    } catch (_: Exception) {
        null
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
