package org.jetbrains.hexana

import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.format.WasmSymbolMap

/**
 * In-memory virtual file representing a *comparison* of two Component Model WASM
 * binaries.
 *
 * Like [HexanaWasmDiffVirtualFile], its [fileType] is [WasmFileType] so the existing
 * [HexanaFileEditorProvider] accepts it unchanged — [HexanaFileEditor.initPanel]
 * branches on this type and renders the component submodule-diff panel. Clicking a
 * differing submodule slices the two ranges and opens a child diff tab (a core
 * [HexanaWasmDiffVirtualFile], or — when the submodule is itself a nested component —
 * another [HexanaComponentDiffVirtualFile], i.e. recursion).
 *
 * [leftSymbolMap]/[rightSymbolMap] are optional `--emit-symbol-map` sidecars; they are
 * passed through to the core sub-diffs so a minified embedded module still name-anchors.
 *
 * Read-only: there is no hex/edit surface, just the computed submodule diff.
 */
class HexanaComponentDiffVirtualFile(
    name: String,
    val leftBuffer: CommonByteBuffer,
    val rightBuffer: CommonByteBuffer,
    val leftName: String,
    val rightName: String,
    val leftSymbolMap: WasmSymbolMap? = null,
    val rightSymbolMap: WasmSymbolMap? = null,
) : LightVirtualFile(name) {
    init {
        fileType = WasmFileType.INSTANCE
    }
}
