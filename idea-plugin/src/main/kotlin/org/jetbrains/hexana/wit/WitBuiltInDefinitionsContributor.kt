package org.jetbrains.hexana.wit

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore.iterateChildrenRecursively
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import com.intellij.util.indexing.IndexableSetContributor
import org.jetbrains.annotations.Unmodifiable

class WitBuiltInDefinitionsContributor: IndexableSetContributor() {
    override fun getAdditionalRootsToIndex(): @Unmodifiable Set<VirtualFile?> {
        val folder = this::class.java.getResource("/builtin") ?: return emptySet()
        val result = mutableSetOf<VirtualFile>()

        VfsUtil.findFileByURL(folder)?.let {
            iterateChildrenRecursively(it, null) { file ->
                if (file.isFile) result.add(file)
                true
            }
        }

        return result
    }
}