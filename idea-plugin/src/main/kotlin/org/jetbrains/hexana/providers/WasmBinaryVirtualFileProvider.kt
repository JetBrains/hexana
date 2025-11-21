package org.jetbrains.hexana.providers

import com.intellij.openapi.vfs.VirtualFile
import javax.swing.JComponent

internal abstract class WasmBinaryVirtualFileProvider(val file: VirtualFile) : WasmBinaryProvider(file.contentsToByteArray()) {
    abstract suspend fun createTextEditor(file: VirtualFile): JComponent
}