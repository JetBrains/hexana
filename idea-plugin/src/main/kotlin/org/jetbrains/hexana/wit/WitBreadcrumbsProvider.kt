package org.jetbrains.hexana.wit

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import org.jetbrains.hexana.wit.psi.WitElementTypes
import org.jetbrains.hexana.wit.psi.WitEnumItem
import org.jetbrains.hexana.wit.psi.WitFlagsItems
import org.jetbrains.hexana.wit.psi.WitInterfaceItem
import org.jetbrains.hexana.wit.psi.WitRecordItem
import org.jetbrains.hexana.wit.psi.WitVariantItems
import org.jetbrains.hexana.wit.psi.WitWorldItem

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
    WitElementTypes.INTERFACE_ITEM, WitElementTypes.RECORD_ITEM,
    WitElementTypes.VARIANT_ITEMS, WitElementTypes.WORLD_ITEM,
    WitElementTypes.FLAGS_ITEMS, WitElementTypes.ENUM_ITEM
)