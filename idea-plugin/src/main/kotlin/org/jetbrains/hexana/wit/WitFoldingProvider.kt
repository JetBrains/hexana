package org.jetbrains.hexana.wit

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Couple
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import org.jetbrains.hexana.wit.psi.WitElementTypes
import org.jetbrains.hexana.wit.psi.WitElements

class WitFoldingBuilder : FoldingBuilder, DumbAware {
    override fun buildFoldRegions(node: ASTNode, document: Document): Array<FoldingDescriptor> {
        val descriptors: MutableList<FoldingDescriptor> = ArrayList()
        collectDescriptorsRecursively(node, document, descriptors)
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String? {
        val type = node.elementType
        if (type === WitElementTypes.LINE_COMMENT) {
            return "//..."
        }
        else if (type === WitElementTypes.BLOCK_COMMENT) {
            return "/*...*/"
        }
        return "..."
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean {
        return false
    }

    companion object {
        private val COLLAPSIBLES = TokenSet.create(
            WitElementTypes.INTERFACE_DEFINITION,
            WitElementTypes.ENUM_DEFINITION,
            WitElementTypes.WORLD_DEFINITION,
            WitElementTypes.NESTED_PACKAGE_DEFINITION,
            WitElementTypes.FLAGS_DEFINITION,
            WitElementTypes.RECORD_DEFINITION,
            WitElementTypes.VARIANT_DEFINITION,
        )

        private fun collectDescriptorsRecursively(
            node: ASTNode,
            document: Document,
            descriptors: MutableList<FoldingDescriptor>
        ) {
            val type = node.elementType
            if ((COLLAPSIBLES.contains(type)) && spanMultipleLines(
                    node,
                    document
                )
            ) {
                val lb = node.findChildByType(TokenSet.create(WitElementTypes.OPEN_BRACE))
                val rb = node.findChildByType(TokenSet.create(WitElementTypes.CLOSE_BRACE))
                val range = TextRange( lb?.textRange?.endOffset ?: node.textRange.startOffset,
                    rb?.textRange?.startOffset ?: node.textRange.endOffset)
                descriptors.add(FoldingDescriptor(node, range))
            } else if (type === WitElementTypes.BLOCK_COMMENT) {
                descriptors.add(FoldingDescriptor(node, node.textRange))
            } else if (type === WitElementTypes.LINE_COMMENT) {
                val commentRange = expandLineCommentsRange(node.psi)
                val startOffset = commentRange.getFirst().textRange.startOffset
                val endOffset = commentRange.getSecond().textRange.endOffset
                if (document.getLineNumber(startOffset) != document.getLineNumber(endOffset)) {
                    descriptors.add(FoldingDescriptor(node, TextRange(startOffset, endOffset)))
                }
            }

            for (child in node.getChildren(null)) {
                collectDescriptorsRecursively(child, document, descriptors)
            }
        }

        fun expandLineCommentsRange(anchor: PsiElement): Couple<PsiElement> {
            return Couple.of(findFurthestSiblingOfSameType(anchor, false), findFurthestSiblingOfSameType(anchor, true))
        }

        private fun findFurthestSiblingOfSameType(anchor: PsiElement, after: Boolean): PsiElement {
            var node = anchor.node
            // Compare by node type to distinguish between different types of comments
            val expectedType = node!!.elementType
            var lastSeen = node
            while (node != null) {
                val elementType = node.elementType
                if (elementType === expectedType) {
                    lastSeen = node
                }
                else if (elementType === TokenType.WHITE_SPACE) {
                    if (expectedType === WitElementTypes.LINE_COMMENT && node.text.indexOf('\n', 1) != -1) {
                        break
                    }
                }
                else if (!WitElements.COMMENTS_SET.contains(elementType) || WitElements.COMMENTS_SET.contains(expectedType)) {
                    break
                }
                node = if (after) node.treeNext else node.treePrev
            }
            return lastSeen!!.psi
        }

        private fun spanMultipleLines(node: ASTNode, document: Document): Boolean {
            val range = node.textRange
            val endOffset = range.endOffset
            return (document.getLineNumber(range.startOffset)
                    < (if (endOffset < document.textLength) document.getLineNumber(endOffset) else document.lineCount - 1))
        }
    }
}