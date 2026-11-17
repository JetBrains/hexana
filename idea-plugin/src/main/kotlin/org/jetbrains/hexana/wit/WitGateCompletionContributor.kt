package org.jetbrains.hexana.wit

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.prevLeaf
import com.intellij.util.ProcessingContext
import org.jetbrains.hexana.wit.psi.WitElementTypes
import org.jetbrains.hexana.wit.psi.WitPackageName
import org.jetbrains.hexana.wit.psi.WitPsiFile
import org.jetbrains.hexana.wit.psi.WitUsePath

class WitGateCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            psiElement().afterLeaf(psiElement(WitElementTypes.AT)),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    if (parameters.position.containingFile !is WitPsiFile) return
                    val position = parameters.position
                    val parent = position.parent
                    val effectiveParent = if (parent is PsiErrorElement) parent.parent else parent

                    if (effectiveParent is WitUsePath || effectiveParent is WitPackageName ||
                        position.parentOfType<WitUsePath>() != null || position.parentOfType<WitPackageName>() != null) return

                    // it can be a package version or an imported versioned use path
                    val prevLeaf = position.prevLeaf(false)
                    if (prevLeaf != null && prevLeaf.elementType == WitElementTypes.IDENTIFIER) {
                        return
                    }

                    val prevLeafNoWs = position.prevLeaf { it !is PsiWhiteSpace }
                    if (prevLeafNoWs != null && (prevLeafNoWs.text == ":" || prevLeafNoWs.text == "/")) return

                    // Text-based fallback for broken parsing
                    val document = parameters.editor.document
                    val offset = parameters.offset
                    if (offset > 0) {
                        val text = document.charsSequence
                        var i = offset - 1
                        if (text[i] == '@') {
                            i--
                        }
                        if (i >= 0 && !text[i].isWhitespace()) {
                            return
                        }
                    }

                    result.addElement(createGateLookupItem("unstable", "(feature = )"))
                    result.addElement(createGateLookupItem("since", "(version = )"))
                    result.addElement(createGateLookupItem("deprecated", "(version = )"))
                }
            }
        )
    }

    override fun invokeAutoPopup(position: com.intellij.psi.PsiElement, type: Char): Boolean {
        return type == '@'
    }

    private fun createGateLookupItem(name: String, tail: String) =
        LookupElementBuilder.create(name)
            .withPresentableText("@$name")
            .withTailText(tail, true)
            .withInsertHandler { context, _ ->
                val editor = context.editor
                val document = editor.document
                val tailOffset = context.tailOffset
                document.insertString(tailOffset, tail)
                editor.caretModel.moveToOffset(tailOffset + tail.length - 1)
            }
}
