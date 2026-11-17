package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

class ProtobufFileType : FileType {
    companion object {
        @JvmField val INSTANCE = ProtobufFileType()
    }
    override fun getName() = "Protobuf Descriptor Set"
    override fun getDescription() = HexanaBundle.message("hexana.protobuf.fileType.description")
    override fun getDefaultExtension(): @NlsSafe String = "desc"
    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData
    override fun isBinary() = true
}
