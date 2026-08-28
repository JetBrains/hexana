package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexanaBinaryVirtualFile
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * The [contentSize] bytes at [contentOffset], or null when that range does not
 * fit in this buffer.
 *
 * Section sizes come from the binary itself, so a truncated component can
 * declare a core module or nested component larger than the file that holds
 * it; slicing such a range throws. The bound is compared as
 * `offset > limit - size` so a hostile size cannot overflow the sum.
 */
internal fun CommonByteBuffer.submoduleSlice(contentOffset: Int, contentSize: Int): CommonByteBuffer? {
    if (contentOffset < 0 || contentSize <= 0 || contentOffset > limit() - contentSize) return null
    return slice(contentOffset, contentSize)
}

/**
 * Opens an embedded core module or nested component of a component-model
 * binary in its own editor tab.
 *
 * The content bytes are sliced out of [bytes] in memory. Offsets accumulate
 * across nesting levels so a module opened from an already-nested view is
 * still labelled with its absolute file offset. A range the binary cannot
 * actually hold is logged and ignored rather than opened.
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
    val buffer = bytes.submoduleSlice(contentOffset, contentSize)
    if (buffer == null) {
        HexanaLog.LOG.warn(
            "Not opening ${if (isComponent) "nested component" else "core module"} $index of ${file.name}: " +
                "declared range $contentOffset+$contentSize does not fit in ${bytes.limit()} bytes."
        )
        return
    }
    val fileBase = file.getUserData(HexanaBinaryVirtualFile.BASE_OFFSET_KEY) ?: 0
    val absoluteOffset = fileBase + contentOffset
    val infix = if (isComponent) "_component_" else "_"
    val fileName = "${file.nameWithoutExtension}$infix$index@${absoluteOffset.toHexString()}.${file.extension}"
    val virtualFile = HexanaBinaryVirtualFile(fileName, buffer, baseOffset = absoluteOffset, parentFile = file)
    FileEditorManager.getInstance(project).openFile(virtualFile, true)
}
