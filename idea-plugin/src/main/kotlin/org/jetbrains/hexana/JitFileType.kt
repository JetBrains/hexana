package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

class JitFileType : FileType {
    companion object {
        @JvmField val INSTANCE = JitFileType()
    }
    override fun getName() = "jit"
    override fun getDescription() = HexanaBundle.message("hexana.jit.fileType.description")
    override fun getDefaultExtension(): @NlsSafe String = "jit"
    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData
    override fun isBinary() = true
}