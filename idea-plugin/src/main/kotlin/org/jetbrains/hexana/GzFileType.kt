package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

class GzFileType : NativeBinaryFileType() {
    companion object {
        @JvmField val INSTANCE = GzFileType()
    }
    override fun getName() = "gz"
    override fun getDescription() = HexanaBundle.message("hexana.gz.fileType.description")
    override fun getDefaultExtension(): @NlsSafe String = "gz"
    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData
}
