package org.jetbrains.hexana.wit

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.childrenOfType
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.WasmIndexId
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.wit.psi.WitExportDefinition
import org.jetbrains.hexana.wit.psi.WitPackageDefinition

class WitGoToRelatedSymbolsProvider: RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        if (element is WitExportDefinition) {
            element.usePath?.text?.let { usePathText ->
                val pathName = if (usePathText.contains('/')) usePathText
                    else element.containingFile.childrenOfType<WitPackageDefinition>().singleOrNull()?.let { packageDefinition ->
                        packageDefinition.packageName.text.substringBeforeLast('@') + "/" + usePathText
                    } ?: return
                processIndexEntries(WasmIndexId, pathName, GlobalSearchScope.everythingScope(element.project)) {
                        file, decls ->
                    val decl = decls.singleOrNull() ?: return@processIndexEntries true
                    result.add(RelatedItemLineMarkerInfo(
                        element.usePath!!.firstChild,
                        element.textRange,
                        AllIcons.Gutter.ImplementedMethod,
                        {"Navigate to WASM definition in ${file.name}"}, { _, i ->
                            val editor = FileEditorManager.getInstance(i.project).openFile(file, true)
                            (editor.singleOrNull() as? HexanaFileEditor)?.stateHolder?.scrollHexViewTo(decl.offset,
                                decl.endOffset - decl.offset)
                        },
                        GutterIconRenderer.Alignment.RIGHT,
                        {emptyList()}))
                    return@processIndexEntries true
                }
            }
        }
    }
}