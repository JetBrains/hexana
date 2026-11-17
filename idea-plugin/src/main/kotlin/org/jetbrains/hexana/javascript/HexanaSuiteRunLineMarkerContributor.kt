package org.jetbrains.hexana.javascript

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement

/**
 * Gutter run icons for `.test.js` suite files.
 *
 * Matches exactly:
 * - Bare `test(...)` or `describe(...)` calls: the leaf identifier with no qualifier
 *   (refExpr.qualifier == null).
 * - `test.skip(...)`: the `skip` identifier where the qualifier is the plain identifier
 *   `test` (refExpr.qualifier is a JSReferenceExpression with referenceName "test").
 *
 * Does NOT match `obj.test(...)` or any other qualified form where the qualifier is
 * not the plain identifier `test`.
 *
 * Uses JS PSI because this class lives in the `javascript` package which is already
 * gated on the JavaScript plugin (see org.jetbrains.hexana.javascript.xml).
 * No JS PSI imports are permitted in the `run/` package.
 */
class HexanaSuiteRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        if (element !is LeafPsiElement) return null

        val containingFile = element.containingFile ?: return null
        if (!containingFile.name.endsWith(".test.js")) return null

        val text = element.text

        // Walk up: leaf -> JSReferenceExpression (the callee) -> JSCallExpression
        val refExpr = element.parent as? JSReferenceExpression ?: return null
        val callExpr = refExpr.parent as? JSCallExpression ?: return null
        if (callExpr.methodExpression != refExpr) return null

        val qualifier = refExpr.qualifier
        val matches = when {
            // Bare test(...) or describe(...) with no qualifier
            (text == "test" || text == "describe") && qualifier == null -> true
            // test.skip(...): leaf is "skip", qualifier is the plain "test" identifier
            text == "skip" &&
                (qualifier as? JSReferenceExpression)?.let {
                    it.qualifier == null && it.referenceName == "test"
                } == true -> true
            else -> false
        }
        if (!matches) return null

        return Info(AllIcons.Actions.Execute, { "Run suite" }, *ExecutorAction.getActions(0))
    }
}
