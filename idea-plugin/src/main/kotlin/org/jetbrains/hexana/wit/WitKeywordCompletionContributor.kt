package org.jetbrains.hexana.wit

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.Language
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtilBase
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import com.intellij.psi.util.prevLeaf
import org.jetbrains.hexana.wit.psi.WitElementTypes
import org.jetbrains.hexana.wit.psi.WitPsiFile

private val KEYWORDS_NO_COMPLETION_AFTER = TokenSet.create(
    WitElementTypes.IMPORT, WitElementTypes.EXPORT, WitElementTypes.WORLD,
    WitElementTypes.INTERFACE, WitElementTypes.PACKAGE, WitElementTypes.USE,
    WitElementTypes.ENUM, WitElementTypes.RECORD, WitElementTypes.VARIANT, WitElementTypes.FLAGS,
)

class WitKeywordCompletionContributor: CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.position.containingFile !is WitPsiFile) return

        val position = parameters.position
        if (position is PsiComment) return
        if (KEYWORDS_NO_COMPLETION_AFTER.contains(position.prevLeaf { it !is PsiWhiteSpace }?.elementType)) return
        if (parameters.completionType == CompletionType.BASIC) {
            val parent = position.parent?.let { (it as? PsiErrorElement)?.parent ?: it }
            val suggestedKeywords = suggestKeywords(parameters.position)
                .map { StringUtil.toLowerCase(it).replace(" identifier", "").trim() }
                .toMutableSet()
            if (parent !is WitPsiFile) {
                // those keywords leak due to relaxed parsing
                // remove them manually from where they don't belong
                suggestedKeywords.remove("world")
                suggestedKeywords.remove("interface")
                suggestedKeywords.remove("package")
            }
            suggestedKeywords.remove("identifier")

            for (keyword in suggestedKeywords) {
                result.addElement(createKeywordLookupItem(keyword))
            }
        }
    }

    fun createKeywordLookupItem(keyword: String): LookupElement =
        LookupElementBuilder.create(keyword).withCaseSensitivity(false).bold()
            .withInsertHandler { context, _ ->
                context.editor.document.insertString(context.tailOffset, " ")
                context.editor.caretModel.moveToOffset(context.editor.caretModel.offset + 1)
            }

    fun suggestKeywords(position: PsiElement): List<String> {
        val sqlFile = position.containingFile as WitPsiFile
        val language: Language = sqlFile.language
        val fragment = InjectedLanguageUtilBase.getUnescapedText(sqlFile, null, position)
        val empty = StringUtil.isEmptyOrSpaces(fragment)
        val text = if (empty) CompletionInitializationContext.DUMMY_IDENTIFIER else fragment
        val completionOffset = if (empty) 0 else fragment.length
        val file =
            PsiFileFactory.getInstance(sqlFile.project).createFileFromText("a.wit", language, text, true, false)
        val state: GeneratedParserUtilBase.CompletionState =
            object : GeneratedParserUtilBase.CompletionState(completionOffset) {
                override fun convertItem(o: Any?): String? {
                    if (o is Array<*> && o.isArrayOf<IElementType>()) return super.convertItem(o)
                    val text = StringUtil.toLowerCase(o.toString().split(' ')[0])
                    return if (text.length > 1 && StringUtil.isJavaIdentifierStart(text[0])) text else null
                }
            }
        file.putUserData(GeneratedParserUtilBase.COMPLETION_STATE_KEY, state)
        TreeUtil.ensureParsed(file.node)
        return state.items.filterNotNull()
    }
}