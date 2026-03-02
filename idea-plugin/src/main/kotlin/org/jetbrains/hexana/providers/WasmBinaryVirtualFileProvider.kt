package org.jetbrains.hexana.providers

import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.toMappedBuffer

interface BinaryVirtualFileProvider {
    val file: VirtualFile
    suspend fun createTextEditor(file: VirtualFile): TextEditor
}

internal abstract class WasmBinaryVirtualFileProvider(override val file: VirtualFile) : WasmBinaryProvider(file.toMappedBuffer()!!.toCommonByteBuffer()), BinaryVirtualFileProvider {
    abstract override suspend fun createTextEditor(file: VirtualFile): TextEditor
}

internal abstract class WasmComponentBinaryVirtualFileProvider(override val file: VirtualFile) : WasmComponentBinaryProvider(file.toMappedBuffer()!!.toCommonByteBuffer()), BinaryVirtualFileProvider {
    abstract override suspend fun createTextEditor(file: VirtualFile): TextEditor
}
