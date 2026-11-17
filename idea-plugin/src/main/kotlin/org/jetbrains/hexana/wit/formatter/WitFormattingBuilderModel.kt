package org.jetbrains.hexana.wit.formatter

import com.intellij.formatting.ASTBlock
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.SpacingBuilder
import com.intellij.formatting.Wrap
import com.intellij.formatting.WrapType
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.jetbrains.hexana.wit.WitLanguage
import org.jetbrains.hexana.wit.psi.WitElementTypes
import org.jetbrains.hexana.wit.psi.WitElements
import kotlin.collections.orEmpty

class WitFormattingBuilderModel  : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val settings = formattingContext.codeStyleSettings
        val spacingBuilder = createSpacingBuilder(settings)
        val block =
            WitBlock(
                formattingContext.node, null, Indent.getSmartIndent(Indent.Type.CONTINUATION), null,
                spacingBuilder)
        return FormattingModelProvider.createFormattingModelForPsiFile(formattingContext.containingFile, block, settings)
    }

}

class WitBlock(
    private val myNode: ASTNode,
               private val myAlignment: Alignment?,
               private val myIndent: Indent,
               private val myWrap: Wrap?,
               private val mySpacingBuilder: SpacingBuilder) : ASTBlock {

    // lazy initialized on first call to #getSubBlocks()
    private var mySubBlocks: MutableList<Block>? = null

    override fun getNode(): ASTNode {
        return myNode
    }

    override fun getTextRange(): TextRange {
        return myNode.textRange
    }

    override fun getSubBlocks(): List<Block> {
        if (mySubBlocks == null) {
            val children = myNode.getChildren(null)
            mySubBlocks = ArrayList(children.size)
            for (child in children) {
                if (isWhitespaceOrEmpty(child)) continue
                mySubBlocks!!.add(makeSubBlock(child))
            }
        }
        return mySubBlocks.orEmpty()
    }

    private fun hasElementType(node: ASTNode, set: TokenSet): Boolean {
        return set.contains(node.elementType)
    }

    private fun hasElementType(node: ASTNode, vararg types: IElementType?): Boolean {
        return hasElementType(node, TokenSet.create(*types))
    }

    private fun makeSubBlock(childNode: ASTNode): Block {
        var indent = Indent.getNoneIndent()
        var wrap: Wrap? = null

        if (hasElementType(myNode, WitElements.CONTAINERS)) {
            if (hasElementType(childNode, WitElementTypes.COMMA)) {
                wrap = Wrap.createWrap(WrapType.NONE, true)
            }
            else if (!hasElementType(childNode, ALL_BRACES)
                && (!WitElements.CONTAINER_KEYWORDS.contains(skipGatesAndWs(childNode)?.elementType) || !WitElements.GATES.contains(childNode.elementType))
                && !WitElements.CONTAINER_KEYWORDS.contains(childNode.elementType)) {
                indent = Indent.getNormalIndent()
            }
        }
        return WitBlock(childNode, null, indent, wrap, mySpacingBuilder)
    }

    private fun skipGatesAndWs(childNode: ASTNode): ASTNode? {
        var node = childNode
        while (node.elementType == TokenType.WHITE_SPACE
            || node.elementType == WitElementTypes.LINE_COMMENT
            || node.elementType == WitElementTypes.BLOCK_COMMENT
            || WitElements.GATES.contains(node.elementType)) {
            node = node.treeNext
        }
        return node
    }

    override fun getWrap(): Wrap? {
        return myWrap
    }

    override fun getIndent(): Indent {
        return myIndent
    }

    override fun getAlignment(): Alignment? {
        return myAlignment
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        return mySpacingBuilder.getSpacing(this, child1, child2)
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        if (hasElementType(myNode, WitElements.CONTAINERS)) {
            // WEB-13675: For some reason, including alignment in child attributes causes
            // indents to consist solely of spaces when both USE_TABS and SMART_TAB
            // options are enabled.
            return ChildAttributes(Indent.getNormalIndent(), null)
        }
        else if (myNode.psi is PsiFile) {
            return ChildAttributes(Indent.getNoneIndent(), null)
        }
        // Will use continuation indent for cases like { "foo"<caret>  }
        return ChildAttributes(null, null)
    }

    override fun isIncomplete(): Boolean {
        val lastChildNode = myNode.lastChildNode
        if (WitElements.CONTAINERS.contains(myNode.elementType)) {
            return lastChildNode != null && lastChildNode.elementType !== WitElementTypes.CLOSE_BRACE
        }
        return false
    }

    override fun isLeaf(): Boolean {
        return myNode.firstChildNode == null
    }

    companion object {
        private val OPEN_BRACES = TokenSet.create(WitElementTypes.OPEN_BRACE)
        private val CLOSE_BRACES = TokenSet.create(WitElementTypes.CLOSE_BRACE)
        private val ALL_BRACES = TokenSet.orSet(OPEN_BRACES, CLOSE_BRACES)

        private fun isWhitespaceOrEmpty(node: ASTNode): Boolean {
            return node.elementType === TokenType.WHITE_SPACE || node.textLength == 0
        }
    }
}

private fun createSpacingBuilder(settings: CodeStyleSettings): SpacingBuilder {
    val commonSettings = settings.getCommonSettings(WitLanguage)

    val spacesBeforeComma = if (commonSettings.SPACE_BEFORE_COMMA) 1 else 0

    return SpacingBuilder(settings, WitLanguage)
        .withinPair(WitElementTypes.OPEN_BRACE, WitElementTypes.CLOSE_BRACE).spaceIf(commonSettings.SPACE_WITHIN_BRACES, true)
        .withinPair(WitElementTypes.OPEN_PAREN, WitElementTypes.CLOSE_PAREN).spaceIf(commonSettings.SPACE_WITHIN_PARENTHESES, true)
        .before(WitElementTypes.COMMA).spacing(spacesBeforeComma, spacesBeforeComma, 0, false, 0)
        .after(WitElementTypes.COMMA).spaceIf(commonSettings.SPACE_AFTER_COMMA)
}