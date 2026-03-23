package org.jetbrains.hexana.ui.panel.components

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.parentOfType
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.WasmIndexId
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.wit.WitIndexId
import org.jetbrains.hexana.wit.processIndexEntries
import org.jetbrains.hexana.wit.psi.WitResolver

fun navigateToWasmDefinition(pathName: String, project: Project) {
    val (name, _) = WitResolver.splitNameAndVersion(pathName)
    processIndexEntries(WasmIndexId, name, GlobalSearchScope.everythingScope(project)) {
            file, decls ->
        val decl = decls.singleOrNull() ?: return@processIndexEntries true
        val editor = FileEditorManager.getInstance(project).openFile(file, true)
        (editor.singleOrNull() as? HexanaFileEditor)?.stateHolder?.scrollHexViewTo(decl.offset,
            decl.endOffset - decl.offset)
        return@processIndexEntries true
    }
}

fun navigateToWitDefinition(pathName: String, project: Project) {
    val (name, _) = WitResolver.splitNameAndVersion(pathName)
    processIndexEntries(WitIndexId, name, GlobalSearchScope.everythingScope(project)) {
            file, decls ->
        FileEditorManager.getInstance(project).openFile(file, true)
        if (decls.size == 1) {
            file.findPsiFile(project)?.let { psiFile ->
                psiFile.findElementAt(decls.single().offset)?.parentOfType<NavigatablePsiElement>()?.let {
                    it.navigate(true)
                    return@processIndexEntries false
                }
            }
        }
        return@processIndexEntries true
    }
}