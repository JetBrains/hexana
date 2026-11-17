package org.jetbrains.hexana.wat

import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.psi.PsiFile

class WatProblemHighlightFilter: ProblemHighlightFilter() {
    override fun shouldHighlight(psiFile: PsiFile): Boolean {
        if (psiFile.fileType == WatFileType.INSTANCE) {
            return false
        }
        return true
    }
}