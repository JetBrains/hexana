package org.jetbrains.hexana.providers

import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.VirtualFile
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
