package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

class TarFileType : NativeBinaryFileType() {
    companion object {
        @JvmField val INSTANCE = TarFileType()
    }
    override fun getName() = "tar"
    override fun getDescription() = HexanaBundle.message("hexana.tar.fileType.description")
    override fun getDefaultExtension(): @NlsSafe String = "tar"
    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData
}
