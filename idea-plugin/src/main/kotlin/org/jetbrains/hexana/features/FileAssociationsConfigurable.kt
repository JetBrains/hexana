package org.jetbrains.hexana.features

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.DslConfigurableBase
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.shared.features.FILE_ASSOCIATIONS_PATH
import org.jetbrains.hexana.shared.features.FileAssociations
import org.jetbrains.hexana.shared.features.ParserRef
import org.jetbrains.hexana.shared.features.ref
import org.jetbrains.hexana.shared.features.normalizeFileAssociationKey
import java.awt.Component
import javax.swing.DefaultCellEditor
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Settings page listing the parsers chosen by hand for individual files, shown under
 * **Tools | Hexana | File Parser Associations**.
 *
 * A *project* configurable for the reason [WasmRuntimeCompatibilityConfigurable] is one: the
 * list is `.hexana/file_associations.json`, which the VS Code extension shares. The usual
 * way to add an entry is the format badge in the editor; this page is for reviewing,
 * changing, and removing them in one place.
 */
class FileAssociationsConfigurable(private val project: Project) :
    DslConfigurableBase(), SearchableConfigurable {

    /** One editable row; [path] is project-relative, as stored. */
    private class Row(var path: String, var parser: ParserRef)

    private val model = ListTableModel<Row>(PathColumn(), ParserColumn(::parserChoices))

    override fun createPanel(): DialogPanel {
        val table = TableView(model).apply {
            setShowGrid(false)
            emptyText.text = HexanaBundle.message("file.associations.empty")
        }
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { addFile() }
            .setRemoveAction {
                table.stopEditing()
                val selected = table.selectedObjects.toSet()
                model.items = model.items.filterNot { it in selected }
            }
            .disableUpDownActions()
            .createPanel()

        return panel {
            row {
                comment(HexanaBundle.message("file.associations.description", FILE_ASSOCIATIONS_PATH))
            }
            row {
                cell(decorated).align(Align.FILL)
            }.resizableRow()

            onReset { model.items = currentRows() }
            onApply {
                table.stopEditing()
                FileAssociationsService.getInstance(project).save(collect())
            }
            onIsModified { collect() != FileAssociationsService.getInstance(project).associations }
        }
    }

    /** Picks a file inside the project and adds it, defaulting to the generic parser. */
    private fun addFile() {
        val service = FileAssociationsService.getInstance(project)
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle(HexanaBundle.message("file.associations.add.title"))
        project.guessProjectDir()?.let { descriptor.withRoots(it) }
        val file = FileChooser.chooseFile(descriptor, project, project.guessProjectDir()) ?: return
        val key = service.keyFor(file)
        if (key == null) {
            Messages.showErrorDialog(
                project,
                HexanaBundle.message("file.associations.add.outside"),
                HexanaBundle.message("file.associations.add.title"),
            )
            return
        }
        if (model.items.none { it.path == key }) {
            model.addRow(Row(key, BinaryParser.GENERIC.ref()))
        }
    }

    private fun currentRows(): List<Row> =
        FileAssociationsService.getInstance(project).associations.associations
            .map { (path, parser) -> Row(path, parser) }

    /** The table as it would be saved: paths normalized, unusable ones dropped. */
    private fun collect(): FileAssociations {
        val entries = linkedMapOf<String, ParserRef>()
        for (row in model.items) {
            val key = normalizeFileAssociationKey(row.path) ?: continue
            entries.getOrPut(key) { row.parser }
        }
        return FileAssociations(entries)
    }

    private class PathColumn : ColumnInfo<Row, String>(HexanaBundle.message("file.associations.column.file")) {
        override fun valueOf(item: Row): String = item.path
        override fun isCellEditable(item: Row): Boolean = true
        override fun setValue(item: Row, value: String) {
            item.path = value
        }
    }

    /**
     * What the parser column offers: every built-in parser, every provider the project's
     * provider scripts define, and any `user:` id already in the table whose script is not
     * loaded — so opening the page never silently rewrites such an entry.
     */
    private fun parserChoices(): List<ParserRef> {
        val scripted = ProviderScriptsService.getInstance(project).catalog.value.providers
        val known = BinaryParser.entries.map { it.ref() } + scripted.map { ParserRef.Scripted(it.id) }
        val unknown = model.items.map { it.parser }.filter { it !in known }.distinct()
        return known + unknown
    }

    /** Display text for [ref]: the parser's or provider's name, or the bare id when unloaded. */
    private fun labelFor(ref: ParserRef): String = when (ref) {
        is ParserRef.Builtin -> ref.parser.displayName
        is ParserRef.Scripted -> ProviderScriptsService.getInstance(project).catalog.value.provider(ref.id)
            ?.second?.let { "${it.name} (${ref.id})" }
            ?: "${ref.id} (not loaded)"
    }

    private inner class ParserColumn(private val choices: () -> List<ParserRef>) :
        ColumnInfo<Row, ParserRef>(HexanaBundle.message("file.associations.column.parser")) {
        override fun valueOf(item: Row): ParserRef = item.parser
        override fun isCellEditable(item: Row): Boolean = true
        override fun setValue(item: Row, value: ParserRef) {
            item.parser = value
        }

        override fun getEditor(item: Row): TableCellEditor {
            val combo = ComboBox(choices().toTypedArray()).apply {
                renderer = SimpleListCellRenderer.create("") { labelFor(it) }
            }
            return DefaultCellEditor(combo)
        }

        override fun getRenderer(item: Row): TableCellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component = super.getTableCellRendererComponent(
                table, (value as? ParserRef)?.let(::labelFor) ?: value, isSelected, hasFocus, row, column,
            )
        }

        override fun getWidth(table: JTable): Int = table.getFontMetrics(table.font).stringWidth("Protobuf descriptor set") + 48
    }

    override fun getId(): @NonNls String = ID

    override fun getDisplayName(): @NlsContexts.ConfigurableName String =
        HexanaBundle.message("file.associations.displayName")

    companion object {
        const val ID: @NonNls String = "hexana.file.associations"
    }
}
