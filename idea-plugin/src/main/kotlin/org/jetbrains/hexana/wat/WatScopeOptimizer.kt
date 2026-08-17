package org.jetbrains.hexana.wat

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.ScopeOptimizer
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.hexana.wat.psi.WatFuncElement
import org.jetbrains.hexana.wat.psi.WatFunctionLocalElement
import org.jetbrains.hexana.wat.psi.WatPsiElement

/**
 * Restricts where WAT declarations are searched for, which is what makes find-usages work at all.
 *
 * A reference search over a [com.intellij.psi.search.GlobalSearchScope] locates candidate files
 * through the word index before it looks at any PSI, and for WAT that search can never hit:
 * the index stores `f1` for a declaration named `$f1` unless a WAT-aware words scanner is in play,
 * and the WAT view's documents are in-memory [LightWatFile]s that are not indexed at all. A local
 * scope walks the PSI text directly and sidesteps both.
 *
 * This is sound rather than a workaround. WAT ids never cross a file: one file holds one module or
 * component, so `$f1` written in one file can never be named from another.
 */
class WatScopeOptimizer : ScopeOptimizer {

    override fun getRestrictedUseScope(element: PsiElement): SearchScope? {
        if (element !is WatPsiElement) {
            return null
        }
        // Locals, parameters and labels cannot even be named outside their own function.
        if (element is WatFunctionLocalElement) {
            val func = PsiTreeUtil.getParentOfType(element, WatFuncElement::class.java) ?: return null
            return LocalSearchScope(func)
        }
        val file = element.containingFile ?: return null
        return LocalSearchScope(searchedFiles(file))
    }

    /**
     * [file], plus its siblings when it is one chunk of a [WatFileset]. A module too large for a
     * single document is split across several, and a usage may well sit in another chunk -- missing
     * those silently would be worse than the search taking longer.
     */
    private fun searchedFiles(file: PsiFile): Array<PsiElement> {
        val fileset = file.virtualFile?.getUserData(WatFileset.KEY) ?: return arrayOf(file)
        val psiManager = PsiManager.getInstance(file.project)
        val files = fileset.files.mapNotNull { psiManager.findFile(it) }
        return if (files.isEmpty()) arrayOf(file) else files.toTypedArray()
    }
}
