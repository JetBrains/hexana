package org.jetbrains.hexana.wit.formatter

import com.intellij.openapi.editor.DefaultLineWrapPositionStrategy
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import org.jetbrains.hexana.wit.psi.WitElementTypes
import kotlin.math.max

class WitLineWrapPositionStrategy : DefaultLineWrapPositionStrategy() {
    override fun calculateWrapPosition(document: Document,
                                       project: Project?,
                                       startOffset: Int,
                                       endOffset: Int,
                                       maxPreferredOffset: Int,
                                       allowToBeyondMaxPreferredOffset: Boolean,
                                       isSoftWrap: Boolean): Int {
        if (isSoftWrap) {
            return super.calculateWrapPosition(document, project, startOffset, endOffset, maxPreferredOffset, allowToBeyondMaxPreferredOffset,
                true)
        }
        if (project == null) return -1
        val wrapPosition = getMinWrapPosition(document, project, maxPreferredOffset)
        if (wrapPosition == SKIP_WRAPPING) return -1
        val minWrapPosition = max(startOffset, wrapPosition)
        return super
            .calculateWrapPosition(document, project, minWrapPosition, endOffset, maxPreferredOffset, allowToBeyondMaxPreferredOffset, isSoftWrap)
    }
}

private const val SKIP_WRAPPING = -2
private fun getMinWrapPosition(document: Document, project: Project, offset: Int): Int {
    val manager = PsiDocumentManager.getInstance(project)
    if (manager.isUncommited(document)) manager.commitDocument(document)
    val psiFile = manager.getPsiFile(document)
    if (psiFile != null) {
        val currElement = psiFile.findElementAt(offset)
        val elementType = PsiUtilCore.getElementType(currElement)
        when {
            elementType === WitElementTypes.SEMVER
                    || elementType === WitElementTypes.NUMBER
                    || elementType === WitElementTypes.IDENTIFIER
                    || elementType === WitElementTypes.BLOB
                    || elementType === WitElementTypes.UINT -> {
                return currElement!!.textRange.endOffset
            }
            elementType === WitElementTypes.ARROW || elementType === WitElementTypes.AT -> {
                return SKIP_WRAPPING
            }
            currElement != null -> {
                if (currElement is PsiComment ||
                    PsiUtilCore.getElementType(PsiTreeUtil.skipWhitespacesForward(currElement)) === WitElementTypes.COMMA) {
                    return SKIP_WRAPPING
                }
            }
        }
    }
    return -1
}