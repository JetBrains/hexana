package org.jetbrains.hexana.wit

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.annotations.Unmodifiable
import org.jetbrains.hexana.wit.psi.WitPsiElement

class WitFindUsagesHandlerFactory: FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean {
        return element is WitPsiElement && element is PsiNamedElement
    }

    override fun createFindUsagesHandler(
        element: PsiElement,
        forHighlightUsages: Boolean
    ): FindUsagesHandler {
        return object : FindUsagesHandler(element) {}
    }
}