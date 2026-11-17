package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

class DexFileType : FileType {
    companion object {
        @JvmField val INSTANCE = DexFileType()
    }
    override fun getName() = "dex"
    override fun getDescription() = HexanaBundle.message("hexana.dex.fileType.description")
    override fun getDefaultExtension(): @NlsSafe String = "dex"
    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData
    override fun isBinary() = true
}
