package org.jetbrains.hexana.wit.psi

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.childrenOfType
import com.intellij.psi.util.parentOfType
import com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.hexana.WasmIndexId
import org.jetbrains.hexana.wit.WitIndexId

open class WitUsePathMixin(node: ASTNode): WitPsiElement(node), WitUsePath {
    override fun getSemver(): WitSemver? {
        return findChildByClass(WitSemver::class.java)
    }

    override fun getReference(): PsiReference? {
        return if (identifier != null) WitPathReference(this) else null
    }

    override fun getIdentifier(): PsiElement? {
        return findChildByType(WitElementTypes.IDENTIFIER)
    }
}

class WitPathReference(element: WitUsePath): PsiReferenceBase<WitUsePath>(element) {
    override fun resolve(): PsiElement? {
       return WitResolver.resolveWitPath(element, element.text)
    }

    override fun getVariants(): Array<out Any> {
        return (getLocalInterfaceNames(element) +
                FileBasedIndex.getInstance().getAllKeys(WitIndexId, element.project).filter { it.contains('/') } +
                FileBasedIndex.getInstance().getAllKeys(WasmIndexId, element.project).filter { it.contains('/') })
            .toSet().map { toLookupElement(it, element) }.toTypedArray()
    }
}

private fun getLocalInterfaceNames(element: WitUsePath): List<String> {
    return element.containingFile.children.filterIsInstance<WitInterfaceDefinition>().mapNotNull {
        it.nameIdentifier?.text
    }
}

private fun toLookupElement(string: String, context: WitUsePath): LookupElementBuilder =
    LookupElementBuilder.create(adjustNameInContext(string, context)).withInsertHandler { context, _ ->
        var element = context.file.findElementAt(context.selectionEndOffset)
        if (element is PsiWhiteSpace) {
            element = element.prevSibling
        }
        element ?: return@withInsertHandler
        (element.parentOfType<WitExportDefinition>(true)
            ?: element.parentOfType<WitImportDefinition>(true)
            ?: element.parentOfType<WitToplevelUseDeclaration>(true))?.let {
            if (context.document.charsSequence[context.selectionEndOffset - 1] != ';') {
                context.document.insertString(context.selectionEndOffset, ";")
            }
        }
        element.parentOfType<WitUseDeclaration>(true)?.let {
            if (context.document.charsSequence[context.selectionEndOffset - 1] != '.') {
                context.editor.caretModel.moveToOffset(context.selectionEndOffset)
                context.document.insertString(context.selectionEndOffset, ".{};")
                context.commitDocument()
                context.editor.caretModel.moveToOffset(context.editor.caretModel.offset + 2)
            }
        }
    }

private fun adjustNameInContext(string: String, context: WitUsePath): String {
    if (!string.contains('/')) {
        return string
    }
    val packageName = string.substringBeforeLast('/')
    val packageDef = context.containingFile.childrenOfType<WitPackageDefinition>()
    if (packageDef.size != 1) return string
    if (packageDef.first().packageName.text.substringBeforeLast('@') == packageName) {
        return string.substringAfterLast('/')
    }
    return string
}
