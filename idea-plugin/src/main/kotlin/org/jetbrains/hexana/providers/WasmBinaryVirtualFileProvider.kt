package org.jetbrains.hexana.providers

import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.toCommonByteBuffer

interface BinaryVirtualFileProvider {
    val file: VirtualFile
    suspend fun createTextEditor(file: VirtualFile): TextEditor
}

internal abstract class WasmBinaryVirtualFileProvider(override val file: VirtualFile) : WasmBinaryProvider(file.toCommonByteBuffer()!!), BinaryVirtualFileProvider {
    abstract override suspend fun createTextEditor(file: VirtualFile): TextEditor
}

internal abstract class WasmComponentBinaryVirtualFileProvider(override val file: VirtualFile) : WasmComponentBinaryProvider(file.toCommonByteBuffer()!!), BinaryVirtualFileProvider {
    abstract override suspend fun createTextEditor(file: VirtualFile): TextEditor
}

/**
 * A [GenericBinaryProvider] that retains a reference to the backing [VirtualFile].
 * Used when a `.wasm` (or other known-extension) file cannot be parsed by the
 * format-specific provider (e.g. the magic bytes were corrupted by a previous
 * overwrite). Keeping the file reference ensures the edit toolbar's Overwrite /
 * Save-a-copy buttons remain functional even for damaged binaries.
 */
internal class GenericBinaryVirtualFileProvider(
    override val file: VirtualFile,
    /** The bytes shown for [file]: all of it, or the head of a file too large to load. */
    buffer: CommonByteBuffer = file.toCommonByteBuffer()!!,
) : GenericBinaryProvider(buffer), BinaryVirtualFileProvider {
    override suspend fun createTextEditor(file: VirtualFile): TextEditor {
        throw UnsupportedOperationException("GenericBinaryVirtualFileProvider does not support text editors")
    }
}
