package org.jetbrains.hexana.wat

import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.ScopeOptimizer
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.hexana.wat.psi.WatFuncElement
import org.jetbrains.hexana.wat.psi.WatFunctionLocalElement

class WatScopeOptimizer : ScopeOptimizer {

    override fun getRestrictedUseScope(element: PsiElement): SearchScope? {
        if (element is WatFunctionLocalElement) {
            val func = PsiTreeUtil.getParentOfType(element, WatFuncElement::class.java)
            return if (func != null) LocalSearchScope(func) else null
        }
        return null
    }
}