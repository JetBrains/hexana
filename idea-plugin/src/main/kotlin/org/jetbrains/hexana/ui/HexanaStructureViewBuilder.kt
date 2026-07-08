package org.jetbrains.hexana.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.*
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.providers.elf.*
import org.jetbrains.hexana.wasm.intRange
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.shared.isInternalName
import org.jetbrains.hexana.ui.panel.runWasmExportedFunction
import org.jetbrains.hexana.wasm.components.WasmComponentExportSection
import org.jetbrains.hexana.wasm.components.WasmComponentImportSection
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import javax.swing.Icon


class HexanaStructureViewBuilderProvider : StructureViewBuilderProvider {
    override fun getStructureViewBuilder(
        fileType: com.intellij.openapi.fileTypes.FileType,
        file: VirtualFile,
        project: Project,
    ): StructureViewBuilder? {
        val editors = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getEditors(file)
        val hexEditor = editors.filterIsInstance<HexanaFileEditor>().firstOrNull() ?: return null
        val provider = hexEditor.binaryProvider ?: return null
        if (provider !is WasmBinaryProvider && provider !is WasmComponentBinaryProvider && provider !is ElfBinaryProvider) return null
        return HexanaTreeStructureViewBuilder(hexEditor, provider)
    }
}


internal class HexanaTreeStructureViewBuilder(
    private val editor: HexanaFileEditor,
    private val binaryProvider: BinaryProvider,
) : TreeBasedStructureViewBuilder() {

    override fun createStructureViewModel(editor: Editor?): StructureViewModel {
        return HexanaStructureViewModel(this.editor, binaryProvider)
    }

    override fun createStructureView(fileEditor: FileEditor?, project: Project): StructureView {
        val structureView = super.createStructureView(fileEditor, project)
        if (structureView !is com.intellij.ide.structureView.newStructureView.StructureViewComponent) return structureView
        // Install a tree popup action for running exports directly from the tree node
        val tree = structureView.tree
        if (tree != null) {
            tree.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) = handlePopup(e)
                override fun mouseReleased(e: java.awt.event.MouseEvent) = handlePopup(e)
                private fun handlePopup(e: java.awt.event.MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent
                    val element = when {
                        node is HexanaStructureElement -> node
                        node is AbstractTreeNode<*> && node.value is HexanaStructureElement -> node.value as HexanaStructureElement
                        else -> {
                            // Swing tree wraps in DefaultMutableTreeNode
                            val userObj = (node as? javax.swing.tree.DefaultMutableTreeNode)?.userObject
                            when {
                                userObj is HexanaStructureElement -> userObj
                                userObj is AbstractTreeNode<*> && userObj.value is HexanaStructureElement -> userObj.value as HexanaStructureElement
                                else -> null
                            }
                        }
                    }
                    val runAction = element?.runAction ?: return
                    val popup = javax.swing.JPopupMenu()
                    val runItem = javax.swing.JMenuItem("Run Export", AllIcons.RunConfigurations.TestState.Run)
                    runItem.addActionListener { runAction() }
                    popup.add(runItem)
                    popup.show(tree, e.x, e.y)
                    e.consume()
                }
            })
        }
        return structureView
    }
}


private class HexanaStructureViewModel(
    private val editor: HexanaFileEditor,
    private val binaryProvider: BinaryProvider,
) : StructureViewModel.ElementInfoProvider, StructureViewModel {

    private val root = buildRoot()

    private fun buildRoot(): HexanaStructureElement {
        val children = when (binaryProvider) {
            is WasmBinaryProvider -> buildWasmElements(binaryProvider, editor)
            is WasmComponentBinaryProvider -> buildWasmComponentElements(binaryProvider, editor)
            is ElfBinaryProvider -> buildElfElements(binaryProvider, editor)
            else -> emptyList()
        }
        return HexanaStructureElement(
            name = editor.file?.name ?: "Binary",
            range = IntRange.EMPTY,
            icon = AllIcons.FileTypes.Any_type,
            children = children,
            editor = editor,
        )
    }

    override fun getRoot(): StructureViewTreeElement = root

    override fun getFilters() = emptyArray<com.intellij.ide.util.treeView.smartTree.Filter>()
    override fun getSorters() = emptyArray<com.intellij.ide.util.treeView.smartTree.Sorter>()
    override fun getGroupers() = emptyArray<com.intellij.ide.util.treeView.smartTree.Grouper>()

    override fun shouldEnterElement(element: Any?): Boolean = element is HexanaStructureElement && element.children.isNotEmpty()
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement?): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement?): Boolean =
        element is HexanaStructureElement && element.children.isEmpty()

    override fun addModelListener(modelListener: ModelListener) {}
    override fun removeModelListener(modelListener: ModelListener) {}
    override fun addEditorPositionListener(listener: com.intellij.ide.structureView.FileEditorPositionListener) {}
    override fun removeEditorPositionListener(listener: com.intellij.ide.structureView.FileEditorPositionListener) {}
    override fun dispose() {}
    override fun getCurrentEditorElement(): Any? = null
}


internal class HexanaStructureElement(
    private val name: String,
    val range: IntRange,
    private val icon: Icon?,
    val children: List<HexanaStructureElement> = emptyList(),
    private val editor: HexanaFileEditor? = null,
    val runAction: (() -> Unit)? = null,
) : StructureViewTreeElement {

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = name
        override fun getIcon(unused: Boolean): Icon? = icon
    }

    override fun getChildren(): Array<TreeElement> = children.toTypedArray()

    override fun getValue(): Any = this

    override fun navigate(requestFocus: Boolean) {
        val ed = editor ?: return
        if (range.isEmpty()) return
        ed.stateHolder.scrollHexViewTo(range.first.toLong())
        ed.stateHolder.setSelection(range.first.toLong()..range.last.toLong())
    }

    override fun canNavigate(): Boolean = editor != null && !range.isEmpty()
    override fun canNavigateToSource(): Boolean = canNavigate()
}


private fun getNameMapForSection(sectionName: String, nameSection: org.jetbrains.hexana.wasm.sections.WasmNameSection?): Map<Int, String>? {
    if (nameSection == null) return null
    return when (sectionName) {
        "Types" -> nameSection.typesSection?.namemap
        "Tables" -> nameSection.tablesNameSection?.namemap
        "Memory" -> nameSection.memoriesNameSection?.namemap
        "Globals" -> nameSection.globalsNameSection?.namemap
        "Elements" -> nameSection.elemsNameSection?.namemap
        "Data" -> nameSection.dataSegmentsNameSection?.namemap
        else -> null
    }
}

private fun enrichRecordName(originalName: String, index: Int, nameMap: Map<Int, String>?): String {
    val resolvedName = nameMap?.get(index) ?: return originalName
    return "$originalName ($resolvedName)"
}

private fun buildWasmElements(provider: WasmBinaryProvider, editor: HexanaFileEditor): List<HexanaStructureElement> {
    val wasmModule = provider.wasmModule ?: return emptyList()
    val mergedSectionNames = setOf("Functions", "Code")
    val result = mutableListOf<HexanaStructureElement>()
    val nameSection = provider.nameSection

    // Build merged "Functions" folder eagerly so we can insert it after Exports
    val functionsFolder = run {
        val codeOffsets = provider.codeOffsets
        if (codeOffsets.isEmpty()) return@run null
        val nameMap = provider.nameSection?.functionSubsection?.namemap ?: emptyMap()
        val importFuncCount = provider.importFuncCount
        val children = codeOffsets.mapIndexed { codeIndex, entry ->
            val globalIndex = importFuncCount + codeIndex
            val name = nameMap[globalIndex]?.let { "$it (func $globalIndex)" } ?: "func $globalIndex"
            val range = IntRange(entry.offset, entry.offset + entry.size - 1)
            HexanaStructureElement(
                name = name,
                range = range,
                icon = AllIcons.Nodes.Function,
                editor = editor,
            )
        }
        val codeSection = wasmModule.codeSection
        val folderRange = codeSection?.sectionRange ?: children.map { it.range }.structureCoveringRange()
        HexanaStructureElement(
            name = "Functions",
            range = folderRange,
            icon = AllIcons.Nodes.Folder,
            editor = editor,
            children = children,
        )
    }
    var functionsInserted = false

    // Pre-compute runnable export lookup for the Exports section
    val exports = provider.exports
    val runnableExports = exports
        .filterIsInstance<WasmFunctionExportRecordEntry>()
        .filter { !isInternalName(it.name) }
        .associateBy { it.offset }
    val file = (provider as? WasmBinaryVirtualFileProvider)?.file
    val project = editor.myProject

    for (section in wasmModule.sections) {
        if (section.name in mergedSectionNames) continue
        val records = provider.getRecords(section)
        val isExportsSection = section.name == "Exports"
        val sectionNameMap = getNameMapForSection(section.name, nameSection)
        result.add(
            HexanaStructureElement(
                name = section.name,
                range = section.sectionRange,
                icon = AllIcons.Nodes.Folder,
                editor = editor,
                children = records.mapIndexed { index, record ->
                    val exportEntry = if (isExportsSection) runnableExports[record.range.offset] else null
                    val runAction = if (exportEntry != null && file != null) {
                        { runWasmExportedFunction(provider, file, project, exportEntry) }
                    } else null
                    HexanaStructureElement(
                        name = enrichRecordName(record.name, index, sectionNameMap),
                        range = record.range.intRange,
                        icon = if (exportEntry != null) AllIcons.RunConfigurations.TestState.Run else AllIcons.Nodes.Property,
                        editor = editor,
                        runAction = runAction,
                    )
                },
            )
        )
        // Insert Functions right after Exports
        if (section.name == "Exports" && functionsFolder != null) {
            result.add(functionsFolder)
            functionsInserted = true
        }
    }

    // Fallback: if no Export section was found, append at the end
    if (!functionsInserted && functionsFolder != null) {
        result.add(functionsFolder)
    }

    return result
}


private fun buildWasmComponentElements(provider: WasmComponentBinaryProvider, editor: HexanaFileEditor): List<HexanaStructureElement> {
    val wasmComponent = provider.wasmModule ?: return emptyList()

    // Group sections by name; each section instance becomes a child node within its category folder
    val categoryOrder = mutableListOf<String>()
    val categorySections = mutableMapOf<String, MutableList<HexanaStructureElement>>()

    // Pre-scan export/import names keyed by section offset for enrichment
    val exportsBySection = mutableMapOf<Int, List<HexanaStructureElement>>()
    var exportCounter = 0
    for (section in wasmComponent.sections) {
        if (section is WasmComponentExportSection) {
            val parsed = try { with(section) { provider.bytes.scan() } } catch (_: Throwable) { emptyList() }
            val baseIndex = exportCounter
            exportsBySection[section.sectionOffset] = parsed.mapIndexed { i, exp ->
                HexanaStructureElement(
                    name = "${exp.exportName} (ComponentExport ${baseIndex + i})",
                    range = IntRange(exp.offset, exp.offset + exp.size - 1),
                    icon = AllIcons.Nodes.Property,
                    editor = editor,
                )
            }
            exportCounter += parsed.size
        }
    }
    val importsBySection = mutableMapOf<Int, List<HexanaStructureElement>>()
    var importCounter = 0
    for (section in wasmComponent.sections) {
        if (section is WasmComponentImportSection) {
            val parsed = try { with(section) { provider.bytes.scan() } } catch (_: Throwable) { emptyList() }
            val baseIndex = importCounter
            importsBySection[section.sectionOffset] = parsed.mapIndexed { i, imp ->
                HexanaStructureElement(
                    name = "${imp.importName} (ComponentImport ${baseIndex + i})",
                    range = IntRange(imp.offset, imp.offset + imp.size - 1),
                    icon = AllIcons.Nodes.Property,
                    editor = editor,
                )
            }
            importCounter += parsed.size
        }
    }

    for (section in wasmComponent.sections) {
        // Use pre-scanned export/import children when available
        val namedChildren = exportsBySection[section.sectionOffset]
            ?: importsBySection[section.sectionOffset]
        val records = if (namedChildren != null) {
            null
        } else {
            try { section.getRecordRanges(provider.bytes) } catch (_: Throwable) { emptyList() }
        }
        if (section.name !in categorySections) {
            categoryOrder.add(section.name)
            categorySections[section.name] = mutableListOf()
        }
        // For named children (exports/imports), add them directly to the category
        // instead of wrapping in an intermediate section-instance node
        if (namedChildren != null) {
            categorySections[section.name]!!.addAll(namedChildren)
        } else {
            val sectionIndex = categorySections[section.name]!!.size
            val recordChildren = records!!.mapIndexed { i, record ->
                HexanaStructureElement(
                    name = "${record.name}",
                    range = record.range.intRange,
                    icon = AllIcons.Nodes.Property,
                    editor = editor,
                )
            }
            categorySections[section.name]!!.add(
                HexanaStructureElement(
                    name = "${section.name} $sectionIndex",
                    range = section.sectionRange,
                    icon = AllIcons.Nodes.Property,
                    editor = editor,
                    children = recordChildren,
                )
            )
        }
    }

    return categoryOrder.map { name ->
        val children = categorySections[name]!!
        // If there's only one section instance, flatten: use its children directly
        if (children.size == 1 && children[0].children.isNotEmpty()) {
            HexanaStructureElement(
                name = name,
                range = children[0].range,
                icon = AllIcons.Nodes.Folder,
                editor = editor,
                children = children[0].children,
            )
        } else {
            val folderRange = children.map { it.range }.structureCoveringRange()
            HexanaStructureElement(
                name = name,
                range = folderRange,
                icon = AllIcons.Nodes.Folder,
                editor = editor,
                children = children,
            )
        }
    }
}


private fun buildElfElements(provider: ElfBinaryProvider, editor: HexanaFileEditor): List<HexanaStructureElement> {
    val result = mutableListOf<HexanaStructureElement>()
    val header = provider.header ?: return result

    result.add(
        HexanaStructureElement(
            name = "Headers",
            range = header.headerRange.toStructureIntRange(),
            icon = AllIcons.Nodes.Folder,
            children = listOf(
                HexanaStructureElement("ELF identification", header.identRange.toStructureIntRange(), AllIcons.Nodes.Property, editor = editor),
                HexanaStructureElement("ELF header", header.headerRange.toStructureIntRange(), AllIcons.Nodes.Property, editor = editor),
            ),
            editor = editor,
        )
    )

    val sections = provider.sections.orEmpty()
    if (sections.isNotEmpty()) {
        result.add(
            HexanaStructureElement(
                name = "Sections",
                range = sections.map { it.headerRange.toStructureIntRange() }.structureCoveringRange(),
                icon = AllIcons.Nodes.Folder,
                children = sections.map { section ->
                    val name = section.name.ifBlank { "<unnamed>" }
                    val range = section.contentRange?.toStructureIntRangeOrNull() ?: section.headerRange.toStructureIntRange()
                    HexanaStructureElement("Section ${section.index} $name", range, AllIcons.Nodes.Property, editor = editor)
                },
                editor = editor,
            )
        )
    }

    val segments = provider.programHeaders.orEmpty()
    if (segments.isNotEmpty()) {
        result.add(
            HexanaStructureElement(
                name = "Segments",
                range = segments.map { it.headerRange.toStructureIntRange() }.structureCoveringRange(),
                icon = AllIcons.Nodes.Folder,
                children = segments.map { segment ->
                    val range = segment.fileRange?.toStructureIntRangeOrNull() ?: segment.headerRange.toStructureIntRange()
                    HexanaStructureElement("Segment ${segment.index} type=0x${segment.type.toString(16)}", range, AllIcons.Nodes.Property, editor = editor)
                },
                editor = editor,
            )
        )
    }

    val symbols = provider.symbols.orEmpty()
    if (symbols.isNotEmpty()) {
        result.add(
            HexanaStructureElement(
                name = "Symbols",
                range = symbols.map { it.recordRange.toStructureIntRange() }.structureCoveringRange(),
                icon = AllIcons.Nodes.Folder,
                children = symbols.map { symbol ->
                    val name = symbol.name.ifBlank { "<unnamed>" }
                    HexanaStructureElement("Symbol ${symbol.index} $name", symbol.recordRange.toStructureIntRange(), AllIcons.Nodes.Property, editor = editor)
                },
                editor = editor,
            )
        )
    }

    val relocations = provider.relocations.orEmpty()
    if (relocations.isNotEmpty()) {
        result.add(
            HexanaStructureElement(
                name = "Relocations",
                range = relocations.map { it.recordRange.toStructureIntRange() }.structureCoveringRange(),
                icon = AllIcons.Nodes.Folder,
                children = relocations.map { relocation ->
                    val symbolName = relocation.symbol?.name?.takeIf { it.isNotBlank() }
                    val suffix = symbolName?.let { " -> $it" }.orEmpty()
                    HexanaStructureElement(
                        "Relocation ${relocation.index} type=0x${relocation.type.toString(16)}$suffix",
                        relocation.recordRange.toStructureIntRange(),
                        AllIcons.Nodes.Property,
                        editor = editor,
                    )
                },
                editor = editor,
            )
        )
    }

    val dynamicEntries = provider.dynamicEntries.orEmpty()
    if (dynamicEntries.isNotEmpty()) {
        result.add(
            HexanaStructureElement(
                name = "Dynamic",
                range = dynamicEntries.map { it.recordRange.toStructureIntRange() }.structureCoveringRange(),
                icon = AllIcons.Nodes.Folder,
                children = dynamicEntries.map { entry ->
                    val suffix = entry.neededLibrary?.let { " $it" }.orEmpty()
                    HexanaStructureElement(
                        "Dynamic ${entry.index} tag=${entry.tag}$suffix",
                        entry.recordRange.toStructureIntRange(),
                        AllIcons.Nodes.Property,
                        editor = editor,
                    )
                },
                editor = editor,
            )
        )
    }

    val strings = provider.strings
    if (strings.isNotEmpty()) {
        result.add(
            HexanaStructureElement(
                name = "Strings",
                range = strings.map { IntRange(it.offset, it.offset + it.size - 1) }.structureCoveringRange(),
                icon = AllIcons.Nodes.Folder,
                children = strings.map { string ->
                    val preview = if (string.value.length <= 48) "\"${string.value}\"" else "\"${string.value.take(45)}...\""
                    HexanaStructureElement(
                        "String $preview",
                        IntRange(string.offset, string.offset + string.size - 1),
                        AllIcons.Nodes.Property,
                        editor = editor,
                    )
                },
                editor = editor,
            )
        )
    }

    return result
}


private fun NativeByteRange.toStructureIntRange(): IntRange {
    val endInclusive = if (size == 0) offset else offset + size - 1
    return IntRange(offset, endInclusive)
}


private fun NativeByteRange.toStructureIntRangeOrNull(): IntRange? =
    if (size == 0) null else toStructureIntRange()


private fun Iterable<IntRange>.structureCoveringRange(): IntRange {
    val ranges = toList()
    return IntRange(ranges.minOf { it.first }, ranges.maxOf { it.last })
}
