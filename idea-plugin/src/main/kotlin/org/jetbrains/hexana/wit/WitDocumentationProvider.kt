package org.jetbrains.hexana.wit

import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.model.Pointer
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import com.intellij.platform.backend.documentation.DocumentationContent
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.createSmartPointer
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.elementType
import org.jetbrains.hexana.wit.psi.WitFuncItem
import org.jetbrains.hexana.wit.psi.WitGateItem
import org.jetbrains.hexana.wit.psi.WitInterfaceDefinition
import org.jetbrains.hexana.wit.psi.WitStaticFuncItem
import java.util.Locale
import java.util.Locale.getDefault

class WitDocumentationProvider: PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? {
        return WitDocumentationTarget(element)
    }
}

class WitDocumentationTarget(private val element: PsiElement): DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        return Pointer.delegatingPointer(element.createSmartPointer(), {
            WitDocumentationTarget(element)
        })
    }

    override fun computePresentation(): TargetPresentation {
        return TargetPresentation.builder(
            createPresentation(element)
        ).presentation()
    }

    override fun computeDocumentation(): DocumentationResult {
        return DocumentationResult.documentation(DocumentationContent.content(
            convertMarkdownToHtml(doc().replace("\n", "<br>"))
        ))
    }

    override fun computeDocumentationHint(): @NlsContexts.HintText String {
        return doc(true)
    }

    private fun doc(justFirstLine: Boolean = false): String = createPresentation(element) + ".\n" + fetchDocumentation(element, justFirstLine)

    private fun fetchDocumentation(element: PsiElement, justFirstLine: Boolean = false): String {
        var prevSibling = element.prevSibling
        while (prevSibling is PsiWhiteSpace || prevSibling is WitGateItem) {
            prevSibling = prevSibling.prevSibling
        }

        val commentText = StringBuilder()
        while (prevSibling is PsiComment || prevSibling is PsiWhiteSpace) {
            if (prevSibling is PsiComment) {
                val text = prevSibling.text.trimStart(' ', '/', '*').trimEnd(' ', '/', '*', '\r', '\n')
                if (text.isEmpty()) commentText.insert(0, "\n\n")
                else commentText.insert(0, "$text ")
            }
            prevSibling = prevSibling.prevSibling
        }
        if (commentText.isEmpty() && (element is WitFuncItem || element is WitStaticFuncItem)) {
            return fetchDocumentation(element.parent, justFirstLine)
        }
        if (justFirstLine) {
            return commentText.toString().split("\n").first()
        }
        return commentText.toString()
    }

    private fun createPresentation(element: PsiElement): @NlsSafe String {
        return when (element) {
            is PsiNamedElement -> element.elementType.presentableName() + " `" + element.name + "`"
            else -> element.toString()
        }
    }

    private fun IElementType?.presentableName(): String {
        if (this == null) return ""
        return this.toString().lowercase(getDefault())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() }.split("_").joinToString(" ")
    }

}