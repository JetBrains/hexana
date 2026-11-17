package org.jetbrains.hexana

import com.intellij.openapi.fileTypes.FileType

sealed class NativeBinaryFileType : FileType {

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true
}
