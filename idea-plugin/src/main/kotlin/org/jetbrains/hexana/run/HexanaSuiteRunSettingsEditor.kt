package org.jetbrains.hexana.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaSuiteRunner
import org.jetbrains.hexana.shared.scripting.SuiteArtifactDeclaration
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.table.AbstractTableModel

private val suiteDeclarationsCache = CollectionPassCache<List<SuiteArtifactDeclaration>>()

/**
 * Runs a collection pass for the suite file and caches the result by SHA-256.
 * Returns null on any error or when [suiteFilePath] is a directory.
 * Called off the EDT only.
 */
internal fun collectSuiteDeclarations(suiteFilePath: String): List<SuiteArtifactDeclaration>? {
    val file = File(suiteFilePath)
    return suiteDeclarationsCache.compute(file) { _ ->
        val runner = HexanaSuiteRunner(
            limits = HexanaScriptLimits(),
            allowedFilePolicy = AllowedFilePolicy.allowAll,
        )
        runCatching { runner.collectSuiteDeclarations(suiteFilePath).artifactDeclarations }
            .getOrNull()
    }
}

/**
 * Returns the cached declarations for [suiteFilePath] without reading the file's
 * full contents; uses (lastModified, length) as a fast-path key.
 * Returns null on any cache miss or I/O error.
 */
internal fun tryGetCachedSuiteDeclarations(suiteFilePath: String): List<SuiteArtifactDeclaration>? {
    return suiteDeclarationsCache.tryGet(File(suiteFilePath))
}

/**
 * Settings editor for the Hexana Suite run configuration.
 *
 * When the suite path is a single file (not a directory), a collection pass
 * runs off the EDT to discover `hexana.artifact(role, options)` declarations.
 * On success the artifact table lists Role / Formats / Optional / Path columns
 * and path cells are editable with a file-browse button. On failure or for
 * directory paths the editor falls back to the free-form text area.
 */
class HexanaSuiteRunSettingsEditor(
    private val project: Project,
) : SettingsEditor<HexanaSuiteRunConfiguration>() {

    private val suiteField = TextFieldWithBrowseButton()
    private val libRootField = TextFieldWithBrowseButton()
    private val seedField = JTextField()
    private val timeoutField = JTextField()
    private val copyCliButton = JButton("Copy CLI Command")

    /** Artifact table; null = degraded (free-form) mode. */
    private var artifactTableModel: ArtifactTableModel? = null
    private val artifactTable = JTable()
    private val freeFormArea = JBTextArea(4, 40)
    private val artifactStatusLabel = JLabel()

    /** Last suite path for which a collection pass was triggered. */
    private var lastCollectedSuite = ""

    init {
        suiteField.addBrowseFolderListener(
            project,
            FileChooserDescriptor(true, true, false, false, false, false)
                .withFileFilter { it.isDirectory || it.name.endsWith(".test.js") || it.extension == "js" },
        )
        libRootField.addBrowseFolderListener(
            project,
            FileChooserDescriptor(false, true, false, false, false, false),
        )
        suiteField.textField.addActionListener { onSuiteChanged() }
        suiteField.childComponent.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) { onSuiteChanged() }
        })
        copyCliButton.addActionListener { copyCliToClipboard() }
    }

    private fun onSuiteChanged() {
        val path = suiteField.text.trim()
        if (path == lastCollectedSuite || path.isEmpty()) return
        lastCollectedSuite = path
        triggerDeclarationsCollection(path)
    }

    private fun triggerDeclarationsCollection(suitePath: String) {
        // Directory suite paths: skip collection, use free-form only.
        if (File(suitePath).isDirectory) {
            ApplicationManager.getApplication().invokeLater({ applyDeclarations(null) }, ModalityState.any())
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val decls = collectSuiteDeclarations(suitePath)
            ApplicationManager.getApplication().invokeLater({ applyDeclarations(decls) }, ModalityState.any())
        }
    }

    private fun applyDeclarations(declarations: List<SuiteArtifactDeclaration>?) {
        if (declarations == null || declarations.isEmpty()) {
            artifactTableModel = null
            updateArtifactVisibility()
            artifactStatusLabel.text = if (declarations == null) "Declarations unavailable" else "No artifact declarations"
        } else {
            val currentPaths = storedValuesToMap(currentTableOrFreeFormValues())
            val rows = declarationsToRows(declarations, currentPaths).toMutableList()
            val model = ArtifactTableModel(rows)
            artifactTableModel = model
            artifactTable.model = model
            updateArtifactVisibility()
            artifactStatusLabel.text = "${declarations.size} artifact declaration(s)"
        }
    }

    /** Returns "role=path" strings from the current table or free-form area. */
    private fun currentTableOrFreeFormValues(): List<String> {
        val model = artifactTableModel
        return if (model != null) {
            rowsToArtifactValues(model.rows, includeEmpty = true)
        } else {
            freeFormArea.text.lines().filter { it.trim().isNotEmpty() }
        }
    }

    private fun updateArtifactVisibility() {
        val hasTable = artifactTableModel != null
        artifactTable.isVisible = hasTable
        freeFormArea.isVisible = !hasTable
    }

    private fun copyCliToClipboard() {
        val model = artifactTableModel
        val artifacts = if (model != null) {
            model.rows.filter { it.path.isNotEmpty() }.map { it.role to it.path }
        } else {
            parseArtifactEntries(emptyList(), freeFormArea.text)
        }
        val cmd = buildSuiteCliCommand(
            suitePath = suiteField.text.trim(),
            artifacts = artifacts,
            libRoot = libRootField.text.trim().ifEmpty { null },
            seed = seedField.text.trim().ifEmpty { null },
            testTimeoutMillis = timeoutField.text.trim().toLongOrNull(),
        )
        CopyPasteManager.getInstance().setContents(StringSelection(cmd.joinToString(" ")))
    }

    override fun resetEditorFrom(s: HexanaSuiteRunConfiguration) {
        suiteField.text = s.suitePath
        libRootField.text = s.libRoot
        seedField.text = s.seed
        timeoutField.text = s.testTimeoutMillis

        // Populate free-form area from both stored table entries and freeFormArtifacts.
        val lines = s.artifactValues + s.freeFormArtifacts.lines().filter { it.isNotBlank() }
        freeFormArea.text = lines.joinToString("\n")

        val path = s.suitePath
        if (path.isNotEmpty()) {
            val cached = tryGetCachedSuiteDeclarations(path)
            if (cached != null) {
                val storedMap = storedValuesToMap(s.artifactValues)
                val rows = declarationsToRows(cached, storedMap).toMutableList()
                val model = ArtifactTableModel(rows)
                artifactTableModel = model
                artifactTable.model = model
            }
            updateArtifactVisibility()
            if (path != lastCollectedSuite) {
                lastCollectedSuite = path
                triggerDeclarationsCollection(path)
            }
        }
    }

    override fun applyEditorTo(s: HexanaSuiteRunConfiguration) {
        s.suitePath = suiteField.text.trim()
        s.libRoot = libRootField.text.trim()
        s.seed = seedField.text.trim()
        s.testTimeoutMillis = timeoutField.text.trim()

        val model = artifactTableModel
        if (model != null) {
            s.artifactValues = rowsToArtifactValues(model.rows)
            s.freeFormArtifacts = ""
        } else {
            s.artifactValues = emptyList()
            s.freeFormArtifacts = freeFormArea.text.trim()
        }
    }

    override fun createEditor(): JComponent {
        val tableScrollPane = JBScrollPane(artifactTable)
        val freeFormPanel = JBScrollPane(freeFormArea)
        updateArtifactVisibility()

        return panel {
            row("Suite file or directory:") {
                cell(suiteField).align(AlignX.FILL)
            }
            row("Artifacts:") {
                cell(artifactStatusLabel)
            }
            row {
                cell(tableScrollPane).align(AlignX.FILL)
                cell(freeFormPanel).align(AlignX.FILL)
            }
            row("Library root:") {
                cell(libRootField).align(AlignX.FILL)
            }
            row("Seed (hex):") {
                cell(seedField).align(AlignX.FILL)
            }
            row("Test timeout (ms):") {
                cell(timeoutField).align(AlignX.FILL)
            }
            row {
                cell(copyCliButton)
            }
        }
    }
}

/** Table model for the artifact declarations table (Role / Formats / Optional / Path). */
private class ArtifactTableModel(val rows: MutableList<ArtifactRow>) : AbstractTableModel() {
    private val columns = listOf("Role", "Formats", "Optional", "Path")

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 3

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val row = rows.getOrNull(rowIndex) ?: return null
        return when (columnIndex) {
            0 -> row.role
            1 -> row.formats.joinToString(", ").ifEmpty { "(any)" }
            2 -> if (row.optional) "yes" else "no"
            3 -> row.path
            else -> null
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex != 3) return
        rows.getOrNull(rowIndex)?.path = aValue?.toString() ?: ""
        fireTableCellUpdated(rowIndex, columnIndex)
    }
}
