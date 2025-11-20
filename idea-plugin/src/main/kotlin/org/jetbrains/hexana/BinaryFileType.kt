package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import org.jetbrains.annotations.NonNls
import javax.swing.Icon

class BinaryFileType : FileType {

    companion object {
        @JvmField
        val INSTANCE = BinaryFileType()
    }

    override fun getName(): @NonNls String {
        return "binary"
    }

    override fun getDescription(): @NlsContexts.Label String {
        return HexanaBundle.message("hexana.binaryFileType.description")
    }

    override fun getDefaultExtension(): @NlsSafe String {
        return ""
    }

    override fun getIcon(): Icon {
        return AllIcons.FileTypes.BinaryData
    }

    override fun isBinary(): Boolean {
        return true
    }
}