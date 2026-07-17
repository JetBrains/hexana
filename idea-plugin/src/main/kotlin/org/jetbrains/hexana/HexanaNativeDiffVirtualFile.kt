package org.jetbrains.hexana

import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * In-memory virtual file representing a *comparison* of two native binaries
 * (ELF, Mach-O, PE, etc.).
 *
 * It carries both binaries' bytes and display names. Because its [fileType] is
 * [BinaryFileType], the existing [HexanaFileEditorProvider] accepts it —
 * [HexanaFileEditor.initPanel] branches on this type and renders the
 * native size-diff panel instead of the single-file binary editor.
 *
 * Read-only: there is no hex/edit surface, just the computed diff.
 */
class HexanaNativeDiffVirtualFile(
    name: String,
    val leftBuffer: CommonByteBuffer,
    val rightBuffer: CommonByteBuffer,
    val leftName: String,
    val rightName: String,
) : LightVirtualFile(name) {
    init {
        fileType = BinaryFileType.INSTANCE
    }
}
