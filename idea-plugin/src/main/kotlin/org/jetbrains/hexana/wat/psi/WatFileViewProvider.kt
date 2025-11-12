package org.jetbrains.hexana.wat.psi

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.impl.source.SourceTreeToPsiMap
import org.jetbrains.hexana.wat.WatFileType
import java.util.concurrent.atomic.AtomicReference


class WatFileViewProviderFactory : FileViewProviderFactory {
    override fun createFileViewProvider(
        file: VirtualFile,
        language: Language?,
        manager: PsiManager,
        eventSystemEnabled: Boolean
    ): FileViewProvider {
        return WatFileViewProvider(manager, file, eventSystemEnabled)
    }

}

class WatFileViewProvider(
    manager: PsiManager,
    file: VirtualFile,
    eventSystemEnabled: Boolean
) : SingleRootFileViewProvider(manager, file, eventSystemEnabled, WatFileType.INSTANCE) {

    val nodeCache = AtomicReference<CachedNode>()

    override fun findElementAt(offset: Int, language: Language): PsiElement? {
        val psiFile = getPsi(language)
        val result = findElementAt(psiFile, offset)
        return result
    }

    override fun findElementAt(offset: Int): PsiElement? {
        val psiFile = getPsi(baseLanguage)
        val result = findElementAt(psiFile, offset)
        return result
    }

    fun findElementAt(psiFile: PsiFile?, offset: Int): PsiElement? {
        val fileNode = psiFile?.node ?: return null
        val fileModificationStamp = psiFile.modificationStamp
        val cachedNode = nodeCache.get()
        if (cachedNode != null && cachedNode.textRange.contains(offset)) {
            val relativeOffset = offset - cachedNode.textRange.startOffset
            val leaf = cachedNode.node.findLeafElementAt(relativeOffset)
            if (leaf != null && cachedNode.modificationStamp == psiFile.modificationStamp) {
                return SourceTreeToPsiMap.treeElementToPsi(leaf)
            }
        }

        val leaf = fileNode.findLeafElementAt(offset)
        if (leaf != null) {
            val nodeToCache = getNodeToCache(leaf, fileNode)
            nodeCache.set(CachedNode(nodeToCache, nodeToCache.textRange, fileModificationStamp))
        }
        return SourceTreeToPsiMap.treeElementToPsi(leaf)
    }

    private fun getNodeToCache(leaf: ASTNode, fileNode: ASTNode): ASTNode {
        var result = leaf
        var parent = leaf.treeParent
        var depth = 0
        while (depth < 5) {
            if (parent == fileNode || parent.elementType == WatElementType.MODULE) {
                break
            }
            result = parent
            parent = result.treeParent
            depth++
        }
        return result
    }
}

data class CachedNode(val node: ASTNode, val textRange: TextRange, val modificationStamp: Long)
