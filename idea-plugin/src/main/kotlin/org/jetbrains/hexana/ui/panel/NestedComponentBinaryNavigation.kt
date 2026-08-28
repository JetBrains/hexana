package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexanaBinaryVirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Opens an embedded core module or nested component of a component-model
 * binary in its own editor tab.
 *
 * The content bytes are sliced out of [bytes] in memory. Offsets accumulate
 * across nesting levels so a module opened from an already-nested view is
 * still labelled with its absolute file offset.
 *
 * Shared by the Modules / Components table tabs and the Dependencies graph so
 * the same submodule always opens under the same tab name.
 */
internal fun openNestedComponentBinary(
    project: Project,
    file: VirtualFile,
    bytes: CommonByteBuffer,
    index: Int,
    isComponent: Boolean,
    contentOffset: Int,
    contentSize: Int,
) {
    val buffer = bytes.slice(contentOffset, contentSize)
    val fileBase = file.getUserData(HexanaBinaryVirtualFile.BASE_OFFSET_KEY) ?: 0
    val absoluteOffset = fileBase + contentOffset
    val infix = if (isComponent) "_component_" else "_"
    val fileName = "${file.nameWithoutExtension}$infix$index@${absoluteOffset.toHexString()}.${file.extension}"
    val virtualFile = HexanaBinaryVirtualFile(fileName, buffer, baseOffset = absoluteOffset, parentFile = file)
    FileEditorManager.getInstance(project).openFile(virtualFile, true)
}
