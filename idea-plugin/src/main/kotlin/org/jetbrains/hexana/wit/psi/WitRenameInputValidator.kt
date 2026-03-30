package org.jetbrains.hexana.wit.psi

import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.util.ProcessingContext

class WitRenameInputValidator : RenameInputValidator {
    override fun getPattern(): ElementPattern<out PsiElement?> {
        return object: PsiElementPattern.Capture<WitPsiElement>(WitPsiElement::class.java) {}
    }

    override fun isInputValid(
        newName: String,
        element: PsiElement,
        context: ProcessingContext
    ): Boolean {
        return true // todo
    }

}
