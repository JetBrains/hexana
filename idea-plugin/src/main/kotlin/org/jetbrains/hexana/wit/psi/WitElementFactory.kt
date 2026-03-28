package org.jetbrains.hexana.wit.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.hexana.wit.WitLanguage

class WitElementFactory(private val project: Project) {
    fun createSemicolon(): PsiElement {
        val file = createFile("interface dummy { type t = u32; }")
        return PsiTreeUtil.findChildOfType(file, WitTypeDefinition::class.java)!!
            .node.findChildByType(WitElementTypes.SEMI)!!.psi.copy()
    }

    private fun createFile(content: String): WitPsiFile {
        return PsiFileFactory.getInstance(project)
            .createFileFromText("dummy.wit", WitLanguage, content) as WitPsiFile
    }
}
