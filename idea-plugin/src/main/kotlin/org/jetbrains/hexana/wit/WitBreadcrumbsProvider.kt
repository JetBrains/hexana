package org.jetbrains.hexana.wit

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import org.jetbrains.hexana.wit.psi.WitElementTypes

class WitBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> {
        return LANGUAGES
    }

    override fun acceptElement(e: PsiElement): Boolean {
        return e.elementType in BREADCRUMBABLE
    }

    override fun getElementInfo(e: PsiElement): String {
        return e.node.findChildByType(WitElementTypes.IDENTIFIER)?.text ?: "[unnamed]"
    }

    override fun getElementTooltip(e: PsiElement): String? {
        return null
    }
}

private val LANGUAGES = arrayOf<Language>(WitLanguage)
private val BREADCRUMBABLE = TokenSet.create(
    WitElementTypes.INTERFACE_DEFINITION, WitElementTypes.RECORD_DEFINITION,
    WitElementTypes.VARIANT_DEFINITION, WitElementTypes.WORLD_DEFINITION,
    WitElementTypes.FLAGS_DEFINITION, WitElementTypes.ENUM_DEFINITION
)