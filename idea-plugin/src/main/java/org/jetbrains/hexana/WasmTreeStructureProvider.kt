package org.jetbrains.hexana

import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.jetbrains.annotations.Unmodifiable

class WasmTreeStructureProvider : TreeStructureProvider {
    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?
    ): @Unmodifiable Collection<AbstractTreeNode<*>?> {
        val result = ArrayList<AbstractTreeNode<*>>()
        for (child in children) {
            result.add(modify(child, settings))
        }
        return result
    }
}


fun modify(node: AbstractTreeNode<*>, settings: ViewSettings?): AbstractTreeNode<*> {
    val psiFileNode = node as? PsiFileNode ?: return node
    val virtualFile = psiFileNode.virtualFile
    if (virtualFile?.name?.endsWith(".wasm") != true) {
        return node
    }
    val wasmRoot = VirtualFileManager.getInstance().findFileByUrl(WasmFsUtil.getUrl(virtualFile.path))
    return if (wasmRoot != null) {
        WasmFileNode(node.project, node.value, settings, wasmRoot)
    } else {
        node
    }
}


class WasmFileNode(
    project: Project?,
    psiFile: PsiFile,
    settings: ViewSettings?,
    val wasmFile: VirtualFile,
) : PsiFileNode(project, psiFile, settings) {
    override fun getChildrenImpl(): Collection<AbstractTreeNode<*>> {
        return getChildNodes(project, wasmFile, settings)
    }
}


class WasmDirNode(
    project: Project?,
    psiDir: PsiDirectory,
    settings: ViewSettings?,
    val wasmDir: VirtualFile,
) : PsiDirectoryNode(project, psiDir, settings) {
    override fun getChildrenImpl(): Collection<AbstractTreeNode<*>> {
        return getChildNodes(project, wasmDir, settings)
    }
}


fun getChildNodes(project: Project?, wasmFile: VirtualFile, settings: ViewSettings?): Collection<AbstractTreeNode<*>> {
    project ?: return emptyList()
    val psiManager = PsiManager.getInstance(project)
    val children = wasmFile.children
    val nodes = children.mapNotNull {
        if (it.isDirectory) {
            val psiDir = psiManager.findDirectory(it)
            psiDir?.let { dir ->
                WasmDirNode(project, dir, settings, dir.virtualFile)
            }
        } else {
            val psiFile = psiManager.findFile(it)
            psiFile?.let { file ->
                WasmFileNode(project, file, settings, psiFile.virtualFile)
            }
        }
    }
    return nodes
}
