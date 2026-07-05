package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

class ArrowFileType : FileType {
    companion object {
        @JvmField val INSTANCE = ArrowFileType()
    }
    override fun getName() = "arrow"
    override fun getDescription() = HexanaBundle.message("hexana.arrow.fileType.description")
    override fun getDefaultExtension(): @NlsSafe String = "arrow"
    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData
    override fun isBinary() = true
}