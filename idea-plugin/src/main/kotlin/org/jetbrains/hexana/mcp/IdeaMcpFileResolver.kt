package org.jetbrains.hexana.mcp

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

internal fun resolveWasmVirtualFile(path: String): VirtualFile? {
    return resolveVirtualFile(path, searchByFilename = true)
}

internal fun resolveProjectVirtualFile(path: String): VirtualFile? {
    return resolveVirtualFile(path, searchByFilename = false)
}

private fun resolveVirtualFile(path: String, searchByFilename: Boolean): VirtualFile? {
    val lfs = LocalFileSystem.getInstance()
    lfs.refreshAndFindFileByPath(path)?.let { return it }

    val hasSeparator = path.contains('/') || path.contains('\\')
    val projects = ProjectManager.getInstance().openProjects

    for (project in projects) {
        val base = project.basePath
        if (!base.isNullOrBlank()) {
            lfs.refreshAndFindFileByPath("$base/$path")?.let { return it }
        }
    }

    if (searchByFilename && !hasSeparator) {
        for (project in projects) {
            val scope = GlobalSearchScope.projectScope(project)
            val matches = FilenameIndex.getVirtualFilesByName(project, path, scope)
            if (matches.isNotEmpty()) {
                return matches.first()
            }
        }
    }

    return null
}
