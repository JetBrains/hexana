package org.jetbrains.hexana.wat

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordOccurrence
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.tree.TokenSet
import com.intellij.util.Processor
import org.jetbrains.hexana.wat.psi.*

/**
 * Without this the platform falls back to `EmptyFindUsagesProvider`, which leaves every entry in
 * the usage view without a type or a name, and leaves the word index to a scanner that does not
 * understand WAT ids.
 */
class WatFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner = WatWordsScanner()

    override fun canFindUsagesFor(element: PsiElement): Boolean = element is WatNameIdentifierOwner

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = when (element) {
        is WatFuncElement -> "function"
        is WatTypeElement -> "type"
        is WatGlobalElement -> "global"
        is WatMemoryElement -> "memory"
        is WatTableElement -> "table"
        is WatTagElement -> "tag"
        is WatDataElement -> "data segment"
        is WatModuleElement -> "module"
        is WatComponentElement -> "component"
        is WatInstanceElement -> "instance"
        is WatValueElement -> "value"
        is WatExportElement -> "export"
        is WatParamElement -> "parameter"
        is WatLocalElement -> "local"
        // Must come after the concrete owners above: blocks, loops, ifs and trys are all label
        // owners, and none of them has a more specific name to offer.
        is WatLabelOwner -> "label"
        else -> ""
    }

    override fun getDescriptiveName(element: PsiElement): String = (element as? PsiNamedElement)?.name ?: ""

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String {
        val type = getType(element)
        val name = getDescriptiveName(element)
        return listOf(type, name).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/**
 * Indexes a WAT id as one word, `$` included. The default scanner treats `$` as a delimiter, so it
 * stores `f1` for `$f1` -- and a search by the declaration's own name then finds nothing.
 *
 * A fresh [DefaultWordsScanner] is built per file because it holds a single lexer, which is not
 * safe to share across the threads indexing runs on.
 */
private class WatWordsScanner : WordsScanner {
    override fun processWords(fileText: CharSequence, processor: Processor<in WordOccurrence>) {
        DefaultWordsScanner(
            WatLexer(),
            TokenSet.create(WatToken.IDENTIFIER),
            WatToken.COMMENTS_SET,
            TokenSet.create(WatToken.STRING),
        ).processWords(fileText, processor)
    }
}
