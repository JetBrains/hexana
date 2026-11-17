package org.jetbrains.hexana

import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.hexana.shared.diff.BinaryDiffInput

/**
 * In-memory virtual file representing a *comparison* of two binaries of any format.
 *
 * It carries both binaries' bytes and display names as a [BinaryDiffInput]. Because its
 * [fileType] is [BinaryFileType], the existing [HexanaFileEditorProvider] accepts it —
 * [HexanaFileEditor.initPanel] branches on this type and renders the diff view for
 * whichever [org.jetbrains.hexana.shared.diff.BinaryDiffProvider] accepts the pair
 * (core Wasm, Component Model, native), instead of the single-file binary editor.
 *
 * Read-only: there is no hex/edit surface, just the computed diff.
 */
class HexanaDiffVirtualFile(
    name: String,
    val input: BinaryDiffInput,
) : LightVirtualFile(name) {
    init {
        fileType = BinaryFileType.INSTANCE
    }
}
