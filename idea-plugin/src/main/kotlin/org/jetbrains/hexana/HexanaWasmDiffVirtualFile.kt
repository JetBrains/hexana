package org.jetbrains.hexana

import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.format.WasmSymbolMap

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
    // Parsed `--emit-symbol-map` sidecars (`<name>.symbols`) per side, when present, used to
    // restore original names on a minified module so the diff can name-anchor. Null when absent.
    val leftSymbolMap: WasmSymbolMap? = null,
    val rightSymbolMap: WasmSymbolMap? = null,
) : LightVirtualFile(name) {
    init {
        fileType = WasmFileType.INSTANCE
    }
}
