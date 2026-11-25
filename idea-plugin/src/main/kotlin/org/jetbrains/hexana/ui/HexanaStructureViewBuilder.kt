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
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon


class HexanaStructureViewBuilderProvider : StructureViewBuilderProvider {
    override fun getStructureViewBuilder(
        fileType: com.intellij.openapi.fileTypes.FileType,
        file: VirtualFile,
        project: Project,
    ): StructureViewBuilder? {
        val editors = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getEditors(file)
        val hexEditor = editors.filterIsInstance<HexanaFileEditor>().firstOrNull() ?: return null
        return hexanaStructureViewBuilder(hexEditor)
    }
}

/**
 * The structure view for [editor]'s binary, or `null` when the file has not been parsed
 * into a provider yet.
 *
 * Wasm, Wasm components and ELF have hand-written trees built from their parsed model.
 * Every other format falls back to its [BinaryProvider.structureRanges] — the same
 * regions the hex view tints — so the tool window becomes a region navigator instead
 * of sitting empty.
 *
 * Whether a format has anything to show is deliberately NOT decided here. Answering it
 * means reading the regions, which forces the provider's lazy parse: measured at 5 ms
 * for a 50k-entry archive and 24 ms for an 8 MiB protobuf message, and worse for larger
 * files. The platform may ask for a builder from the UI thread, so the question is left
 * to [HexanaTreeStructureViewBuilder]'s model, which resolves children on a pooled
 * thread. A format with no regions therefore shows a tree holding only the file node.
 */
internal fun hexanaStructureViewBuilder(editor: HexanaFileEditor): StructureViewBuilder? {
    val provider = editor.binaryProvider ?: return null
    return HexanaTreeStructureViewBuilder(editor, provider)
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
        // Install a tree popup action for running exports directly from the tree node. The tree is
        // located through the Swing hierarchy rather than via `StructureViewComponent.tree`: that
        // class is an implementation module not visible to the plugin in every IDE (e.g. Rider).
        val tree = com.intellij.util.ui.UIUtil.findComponentOfType(structureView.component, javax.swing.JTree::class.java)
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

    private val modelListeners = mutableListOf<ModelListener>()

    @Volatile
    private var root = buildRoot(binaryProvider)

    /**
     * A parser chosen for the file replaces the editor's provider under an open Structure
     * view; rebuilding the root from the new one keeps the tree in step with the editor.
     */
    private val onProviderChanged: () -> Unit = {
        editor.binaryProvider?.let { provider ->
            root = buildRoot(provider)
            notifyModelChanged()
        }
    }

    init {
        editor.addProviderChangeListener(onProviderChanged)
    }

    private fun notifyModelChanged() {
        synchronized(modelListeners) {
            modelListeners.forEach { it.onModelChanged() }
        }
    }

    private fun buildRoot(binaryProvider: BinaryProvider): HexanaStructureElement {
        val rootElement = HexanaStructureElement(
            name = editor.file?.name ?: "Binary",
            range = IntRange.EMPTY,
            icon = AllIcons.FileTypes.Any_type,
            editor = editor,
            hasChildren = true,
        )
        // Compute children on a pooled thread so the EDT is never blocked
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val children = when (binaryProvider) {
                is WasmBinaryProvider -> buildWasmElements(binaryProvider, editor)
                is WasmComponentBinaryProvider -> buildWasmComponentElements(binaryProvider, editor)
                is ElfBinaryProvider -> buildElfElements(binaryProvider, editor)
                else -> buildStructureRangeElements(binaryProvider, editor)
            }
            rootElement.setResolvedChildren(children + listOfNotNull(scriptRegionsGroup(editor)))
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                // A root replaced while this one resolved is no longer on screen.
                if (root === rootElement) notifyModelChanged()
            }
        }
        return rootElement
    }

    override fun getRoot(): StructureViewTreeElement = root

    override fun getFilters() = emptyArray<com.intellij.ide.util.treeView.smartTree.Filter>()
    override fun getSorters() = emptyArray<com.intellij.ide.util.treeView.smartTree.Sorter>()
    override fun getGroupers() = emptyArray<com.intellij.ide.util.treeView.smartTree.Grouper>()

    override fun shouldEnterElement(element: Any?): Boolean = element is HexanaStructureElement && element.hasChildren
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement?): Boolean =
        element is HexanaStructureElement && element.hasChildren
    override fun isAlwaysLeaf(element: StructureViewTreeElement?): Boolean =
        element is HexanaStructureElement && !element.hasChildren

    override fun addModelListener(modelListener: ModelListener) {
        synchronized(modelListeners) { modelListeners.add(modelListener) }
    }
    override fun removeModelListener(modelListener: ModelListener) {
        synchronized(modelListeners) { modelListeners.remove(modelListener) }
    }
    override fun addEditorPositionListener(listener: com.intellij.ide.structureView.FileEditorPositionListener) {}
    override fun removeEditorPositionListener(listener: com.intellij.ide.structureView.FileEditorPositionListener) {}
    override fun dispose() {
        editor.removeProviderChangeListener(onProviderChanged)
    }
    override fun getCurrentEditorElement(): Any? = null
}


internal class HexanaStructureElement(
    private val name: String,
    val range: IntRange,
    private val icon: Icon?,
    val children: List<HexanaStructureElement> = emptyList(),
    private val editor: HexanaFileEditor? = null,
    val runAction: (() -> Unit)? = null,
    private val childrenProvider: (() -> List<HexanaStructureElement>)? = null,
    val hasChildren: Boolean = children.isNotEmpty() || childrenProvider != null,
) : StructureViewTreeElement {

    // Async-resolved children set from background thread; takes priority over lazy provider
    private val asyncChildren: AtomicReference<List<HexanaStructureElement>?> = AtomicReference(null)

    private val resolvedChildren: List<HexanaStructureElement> by lazy {
        childrenProvider?.invoke() ?: children
    }

    fun setResolvedChildren(value: List<HexanaStructureElement>) {
        asyncChildren.set(value)
    }

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = name
        override fun getIcon(unused: Boolean): Icon? = icon
    }

    override fun getChildren(): Array<TreeElement> {
        val async = asyncChildren.get()
        return (async ?: resolvedChildren).toTypedArray()
    }

    override fun getValue(): Any = this

    override fun navigate(requestFocus: Boolean) {
        val ed = editor ?: return
        if (range.isEmpty()) return
        ed.stateHolder.scrollHexViewTo(range.first.toLong())
        ed.stateHolder.setSelection(range.first.toLong()..range.last.toLong())
        ed.requestWasmByteOffsetNav(range.first)
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

    // Build merged "Functions" folder lazily so structure view opens fast for large modules
    // Use codeSection range directly to avoid triggering expensive codeOffsets parse
    val codeSection = wasmModule.codeSection
    val functionsFolder = if (codeSection != null) {
        HexanaStructureElement(
            name = "Functions",
            range = codeSection.sectionRange,
            icon = AllIcons.Nodes.Folder,
            editor = editor,
            childrenProvider = {
                val codeOffsets = provider.codeOffsets
                val nameMap = provider.nameSection?.functionSubsection?.namemap ?: emptyMap()
                val importFuncCount = provider.importFuncCount
                codeOffsets.mapIndexed { codeIndex, entry ->
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
            },
        )
    } else null
    var functionsInserted = false

    val file = (provider as? WasmBinaryVirtualFileProvider)?.file
    val project = editor.myProject

    for (section in wasmModule.sections) {
        if (section.name in mergedSectionNames) continue
        val isExportsSection = section.name == "Exports"
        val capturedSection = section
        result.add(
            HexanaStructureElement(
                name = section.name,
                range = section.sectionRange,
                icon = AllIcons.Nodes.Folder,
                editor = editor,
                childrenProvider = {
                    val records = provider.getRecords(capturedSection)
                    val sectionNameMap = getNameMapForSection(capturedSection.name, provider.nameSection)
                    // Compute runnable exports lazily only for the Exports section
                    val runnableExports = if (isExportsSection) {
                        provider.exports
                            .filterIsInstance<WasmFunctionExportRecordEntry>()
                            .filter { !isInternalName(it.name) }
                            .associateBy { it.offset }
                    } else null
                    records.mapIndexed { index, record ->
                        val exportEntry = runnableExports?.get(record.range.offset)
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
                    }
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

    // Group sections by name; collect section ranges and section instances for lazy children building
    data class SectionInfo(val section: Any, val sectionRange: IntRange)
    val categoryOrder = mutableListOf<String>()
    val categorySectionInfos = mutableMapOf<String, MutableList<SectionInfo>>()

    for (section in wasmComponent.sections) {
        if (section.name !in categorySectionInfos) {
            categoryOrder.add(section.name)
            categorySectionInfos[section.name] = mutableListOf()
        }
        categorySectionInfos[section.name]!!.add(SectionInfo(section, section.sectionRange))
    }

    return categoryOrder.map { name ->
        val sectionInfos = categorySectionInfos[name]!!
        val folderRange = sectionInfos.map { it.sectionRange }.structureCoveringRange()
        HexanaStructureElement(
            name = name,
            range = folderRange,
            icon = AllIcons.Nodes.Folder,
            editor = editor,
            childrenProvider = {
                val result = mutableListOf<HexanaStructureElement>()
                var exportBase = 0
                var importBase = 0
                for ((idx, info) in sectionInfos.withIndex()) {
                    val section = info.section
                    when {
                        section is WasmComponentExportSection -> {
                            val parsed = try { with(section) { provider.bytes.scan() } } catch (_: Throwable) { emptyList() }
                            val baseIndex = exportBase
                            exportBase += parsed.size
                            parsed.mapIndexedTo(result) { i, exp ->
                                HexanaStructureElement(
                                    name = "${exp.exportName} (ComponentExport ${baseIndex + i})",
                                    range = IntRange(exp.offset, exp.offset + exp.size - 1),
                                    icon = AllIcons.Nodes.Property,
                                    editor = editor,
                                )
                            }
                        }
                        section is WasmComponentImportSection -> {
                            val parsed = try { with(section) { provider.bytes.scan() } } catch (_: Throwable) { emptyList() }
                            val baseIndex = importBase
                            importBase += parsed.size
                            parsed.mapIndexedTo(result) { i, imp ->
                                HexanaStructureElement(
                                    name = "${imp.importName} (ComponentImport ${baseIndex + i})",
                                    range = IntRange(imp.offset, imp.offset + imp.size - 1),
                                    icon = AllIcons.Nodes.Property,
                                    editor = editor,
                                )
                            }
                        }
                        section is org.jetbrains.hexana.wasm.components.WasmComponentSection -> {
                            val records = try { section.getRecordRanges(provider.bytes) } catch (_: Throwable) { emptyList() }
                            val recordChildren = records.mapIndexed { i, record ->
                                HexanaStructureElement(
                                    name = "${record.name}",
                                    range = record.range.intRange,
                                    icon = AllIcons.Nodes.Property,
                                    editor = editor,
                                )
                            }
                            result.add(
                                HexanaStructureElement(
                                    name = "${name} $idx",
                                    range = info.sectionRange,
                                    icon = AllIcons.Nodes.Property,
                                    editor = editor,
                                    children = recordChildren,
                                )
                            )
                        }
                        else -> {
                            result.add(
                                HexanaStructureElement(
                                    name = "${name} $idx",
                                    range = info.sectionRange,
                                    icon = AllIcons.Nodes.Property,
                                    editor = editor,
                                )
                            )
                        }
                    }
                }
                result
            },
        )
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


/**
 * Cap on fallback nodes. A region list is as long as the format makes it — one per ZIP
 * entry, per capture packet, per top-level protobuf field — and the counts run into six
 * figures on large files. The hex view can afford that because it binary-searches the
 * list; a tree that materializes a node per region cannot.
 */
private const val MAX_STRUCTURE_RANGE_ELEMENTS = 10_000

/**
 * The regions a scripted provider's `parse()` reported, as one group under the root, named
 * after the provider. A group rather than leaves mixed into the format's own tree: the base
 * parser's tree (an ELF's sections, a Wasm module's functions) stays as it always reads, and
 * the script's view of the same bytes sits beside it. `null` when no script added regions.
 */
private fun scriptRegionsGroup(editor: HexanaFileEditor): HexanaStructureElement? {
    val overlay = editor.scriptOverlay ?: return null
    if (overlay.ranges.isEmpty()) return null
    val leaves = overlay.ranges.sortedBy { it.offset }.take(MAX_STRUCTURE_RANGE_ELEMENTS).map { range ->
        val start = range.offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val end = (range.offset + range.size - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        HexanaStructureElement(name = range.label, range = start..end, icon = AllIcons.Nodes.Property, editor = editor)
    }
    return HexanaStructureElement(
        name = overlay.provider.name,
        range = IntRange.EMPTY,
        icon = AllIcons.Nodes.Folder,
        children = leaves,
        editor = editor,
    )
}

/**
 * Structure elements for a format with no hand-written tree: one leaf per region, in
 * file order, navigating to the region's bytes.
 */
internal fun buildStructureRangeElements(
    provider: BinaryProvider,
    editor: HexanaFileEditor?,
): List<HexanaStructureElement> {
    val ranges = provider.structureRanges.sortedBy { it.offset }
    val elements = ranges.asSequence()
        .take(MAX_STRUCTURE_RANGE_ELEMENTS)
        .mapNotNull { range ->
            val intRange = range.toInclusiveIntRange() ?: return@mapNotNull null
            HexanaStructureElement(
                name = range.label,
                range = intRange,
                icon = AllIcons.Nodes.Property,
                editor = editor,
            )
        }
        .toList()
    val omitted = ranges.size - MAX_STRUCTURE_RANGE_ELEMENTS
    if (omitted <= 0) return elements
    return elements + HexanaStructureElement(
        name = "... $omitted more regions",
        range = IntRange.EMPTY,
        icon = null,
    )
}

/**
 * A region as an inclusive range of byte indices, or `null` when it falls outside the
 * Int addressing the hex view uses.
 */
private fun StructureRange.toInclusiveIntRange(): IntRange? {
    if (size <= 0L || offset < 0L || offset + size > Int.MAX_VALUE.toLong()) return null
    return IntRange(offset.toInt(), (offset + size - 1).toInt())
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
