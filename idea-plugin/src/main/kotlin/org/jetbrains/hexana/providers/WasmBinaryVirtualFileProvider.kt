package org.jetbrains.hexana.providers

import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.VirtualFile

internal abstract class WasmBinaryVirtualFileProvider(val file: VirtualFile) : WasmBinaryProvider(file.contentsToByteArray()) {
    abstract suspend fun createTextEditor(file: VirtualFile): TextEditor
}