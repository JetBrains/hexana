package org.jetbrains.hexana

import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * In-memory virtual file representing a *comparison* of two WASM modules.
 *
 * It carries both modules' bytes and display names. Because its [fileType] is
 * [WasmFileType], the existing [HexanaFileEditorProvider] accepts it with no
 * change — [HexanaFileEditor.initPanel] branches on this type and renders the
 * size-diff panel instead of the single-file binary editor.
 *
 * Read-only: there is no hex/edit surface, just the computed diff.
 */
class HexanaWasmDiffVirtualFile(
    name: String,
    val leftBuffer: CommonByteBuffer,
    val rightBuffer: CommonByteBuffer,
    val leftName: String,
    val rightName: String,
) : LightVirtualFile(name) {
    init {
        fileType = WasmFileType.INSTANCE
    }
}
