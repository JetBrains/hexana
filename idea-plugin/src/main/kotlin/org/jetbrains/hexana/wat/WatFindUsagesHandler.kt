package org.jetbrains.hexana.wat

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.lexer.Lexer
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.annotations.Unmodifiable
import org.jetbrains.hexana.wat.psi.WatGlobalElement
import org.jetbrains.hexana.wat.psi.WatLexer
import org.jetbrains.hexana.wat.psi.WatPsiElement
import org.jetbrains.hexana.wat.psi.WatToken

class WatFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean {
        return element is WatPsiElement
    }

    override fun createFindUsagesHandler(
        target: PsiElement,
        forHighlightUsages: Boolean
    ): FindUsagesHandler {
        return WatFindUsagesHandler(target)
    }
}

class WatFindUsagesHandler(target: PsiElement) : FindUsagesHandler(target) {
    override fun findReferencesToHighlight(
        target: PsiElement,
        searchScope: SearchScope
    ): @Unmodifiable Collection<PsiReference?> {
        val file = target.containingFile
        if (file != null && target is WatGlobalElement) {
            val globalIndex = target.getIndex()
            if (globalIndex != null) {
                val globalName = target.name
                val globalRefs = getGlobalRefs(file)
                return globalRefs.filter {
                    it.globalIndex == globalIndex || it.globalName == globalName
                }
            }
        }
        return ReferencesSearch.search(target, searchScope).findAll()
    }
}

fun getGlobalRefs(file: PsiFile): List<WatGlobalRef> {
    return CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(computeGlobalRefs(file), file)
    }
}

/**
 * Computes global references without expanding function bodies.
 */
private fun computeGlobalRefs(file: PsiFile): List<WatGlobalRef> {
    val refs = arrayListOf<WatGlobalRef>()
    val lexer = WatLexer()
    lexer.start(file.text)
    while (true) {
        var t = lexer.tokenType
        if (t == WatToken.OPEN_PAREN) {
            lexer.advance()
            skipSpacesAndComments(lexer)
            t = lexer.tokenType
            if (t == WatToken.GLOBAL_GET || t == WatToken.GLOBAL_SET) {
                lexer.advance()
                skipSpacesAndComments(lexer)
                t = lexer.tokenType
                if (t == WatToken.IDENTIFIER) {
                    refs.add(WatGlobalRef(file, lexer.tokenStart, lexer.tokenEnd, lexer.tokenSequence.toString(), null))
                    lexer.advance()
                }
                else if (t == WatToken.NUMBER) {
                    runCatching {
                        val index = Integer.parseInt(lexer.tokenSequence.toString())
                        refs.add(WatGlobalRef(file, lexer.tokenStart, lexer.tokenEnd, "", index))
                    }
                    lexer.advance()
                }
            }
        } else if (t == null) {
            break
        }
        lexer.advance()
    }
    return refs
}

fun skipSpacesAndComments(lexer: Lexer) {
    while (true) {
        val token = lexer.tokenType ?: break
        if (WatToken.WHITESPACES_SET.contains(token) || WatToken.COMMENTS_SET.contains(token)) {
            lexer.advance()
        } else {
            break
        }
    }
}

class WatGlobalRef(
    val file: PsiFile,
    val tokenStart: Int,
    val tokenEnd: Int,
    val globalName: String,
    val globalIndex: Int?,
) : PsiReference {
    override fun getElement(): PsiElement {
        return file
    }

    override fun getRangeInElement(): TextRange {
        return TextRange.create(tokenStart, tokenEnd)
    }

    override fun resolve(): PsiElement? {
        return null
    }

    override fun getCanonicalText(): @NlsSafe String {
        return ""
    }

    override fun handleElementRename(newElementName: String): PsiElement? {
        return null
    }

    override fun bindToElement(element: PsiElement): PsiElement? {
        return null
    }

    override fun isReferenceTo(element: PsiElement): Boolean {
        if (element is WatGlobalElement) {
            return globalIndex == element.getIndex() ||
                    element.nameIdentifier?.textMatches(globalName) == true
        }
        return false
    }

    override fun isSoft(): Boolean {
        return false
    }
}