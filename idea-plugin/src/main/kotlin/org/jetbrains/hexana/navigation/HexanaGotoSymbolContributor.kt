package org.jetbrains.hexana.navigation

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.WasmExportIndexId
import org.jetbrains.hexana.WasmExportSymbolEntry
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.WasmIndexId
import org.jetbrains.hexana.wit.WitDeclaration
import org.jetbrains.hexana.wit.WitDeclarationType
import org.jetbrains.hexana.wit.WitFileType
import org.jetbrains.hexana.wit.WitIndexId
import javax.swing.Icon

/**
 * Surfaces names from three Hexana indexes in Goto Symbol (Cmd+Alt+O) and
 * Search Everywhere → Symbols:
 *  - [WitIndexId]         — declarations in .wit files (text editor offsets)
 *  - [WasmIndexId]        — component-model exports inside .wasm (hex editor byte offsets)
 *  - [WasmExportIndexId]  — regular .wasm module exports (hex editor byte offsets,
 *                            additionally selects the "Exports" tab on navigate)
 */
class HexanaGotoSymbolContributor : ChooseByNameContributorEx {
    override fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?
    ) {
        val index = FileBasedIndex.getInstance()
        if (!index.processAllKeys(WitIndexId, processor, scope, filter)) return
        if (!index.processAllKeys(WasmIndexId, processor, scope, filter)) return
        index.processAllKeys(WasmExportIndexId, processor, scope, filter)
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters
    ) {
        val project = parameters.project
        val scope = parameters.searchScope
        val index = FileBasedIndex.getInstance()

        var keepGoing = index.processValues(WitIndexId, name, null, { vfile, decls ->
            forwardDeclarations(project, vfile, decls, isBinary = false, processor)
        }, scope)
        if (!keepGoing) return

        keepGoing = index.processValues(WasmIndexId, name, null, { vfile, decls ->
            forwardDeclarations(project, vfile, decls, isBinary = true, processor)
        }, scope)
        if (!keepGoing) return

        index.processValues(WasmExportIndexId, name, null, { vfile, entries ->
            forwardExports(project, vfile, entries, processor)
        }, scope)
    }

    private fun forwardDeclarations(
        project: Project,
        vfile: VirtualFile,
        decls: Collection<WitDeclaration>,
        isBinary: Boolean,
        processor: Processor<in NavigationItem>
    ): Boolean {
        for (decl in decls) {
            ProgressManager.checkCanceled()
            if (!processor.process(HexanaSymbolNavigationItem(project, vfile, decl, isBinary))) {
                return false
            }
        }
        return true
    }

    private fun forwardExports(
        project: Project,
        vfile: VirtualFile,
        entries: Collection<WasmExportSymbolEntry>,
        processor: Processor<in NavigationItem>
    ): Boolean {
        for (entry in entries) {
            ProgressManager.checkCanceled()
            if (!processor.process(HexanaWasmExportNavigationItem(project, vfile, entry))) {
                return false
            }
        }
        return true
    }
}

private class HexanaSymbolNavigationItem(
    private val project: Project,
    private val virtualFile: VirtualFile,
    private val declaration: WitDeclaration,
    private val isBinary: Boolean
) : NavigationItem {

    override fun getName(): String = declaration.name

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String {
            val version = declaration.version
            return if (version.isNullOrEmpty()) declaration.name else "${declaration.name} @$version"
        }

        override fun getLocationString(): String {
            val kind = when (declaration.type) {
                WitDeclarationType.PACKAGE -> "package"
                WitDeclarationType.EXPORT -> "export"
            }
            return "$kind · ${virtualFile.presentableName}"
        }

        override fun getIcon(unused: Boolean): Icon? =
            if (isBinary) WasmFileType.INSTANCE.icon else WitFileType.INSTANCE.icon
    }

    override fun navigate(requestFocus: Boolean) {
        if (isBinary) {
            navigateInsideBinary(requestFocus)
        } else {
            OpenFileDescriptor(project, virtualFile, declaration.offset).navigate(requestFocus)
        }
    }

    private fun navigateInsideBinary(requestFocus: Boolean) {
        val editors = FileEditorManager.getInstance(project).openFile(virtualFile, requestFocus)
        val hexanaEditor = editors.filterIsInstance<HexanaFileEditor>().firstOrNull() ?: return
        val start = declaration.offset.toLong()
        val end = declaration.endOffset.toLong()
        hexanaEditor.stateHolder.scrollHexViewTo(start)
        hexanaEditor.stateHolder.setSelection(start..end)
    }

    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true
}

private class HexanaWasmExportNavigationItem(
    private val project: Project,
    private val virtualFile: VirtualFile,
    private val entry: WasmExportSymbolEntry,
) : NavigationItem {

    override fun getName(): String = entry.name

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = entry.name

        override fun getLocationString(): String =
            "${kindLabel(entry.externType)} export · ${virtualFile.presentableName}"

        override fun getIcon(unused: Boolean): Icon = WasmFileType.INSTANCE.icon
    }

    override fun navigate(requestFocus: Boolean) {
        val editors = FileEditorManager.getInstance(project).openFile(virtualFile, requestFocus)
        val hexanaEditor = editors.filterIsInstance<HexanaFileEditor>().firstOrNull() ?: return
        val state = hexanaEditor.stateHolder
        state.requestSelectTabByDescription(EXPORTS_TAB_DESCRIPTION)
        val start = entry.offset.toLong()
        val end = (entry.offset + entry.size).toLong()
        state.scrollHexViewTo(start)
        state.setSelection(start..end)
    }

    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true

    private companion object {
        const val EXPORTS_TAB_DESCRIPTION = "Exports"

        fun kindLabel(externType: Byte): String = when (externType.toInt()) {
            0 -> "function"
            1 -> "table"
            2 -> "memory"
            3 -> "global"
            4 -> "tag"
            else -> "wasm"
        }
    }
}
