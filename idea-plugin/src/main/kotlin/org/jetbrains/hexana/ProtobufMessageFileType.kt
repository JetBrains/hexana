package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

class ProtobufMessageFileType : FileType {
    companion object {
        @JvmField val INSTANCE = ProtobufMessageFileType()
    }
    override fun getName() = "Protobuf Message"
    override fun getDescription() = HexanaBundle.message("hexana.protobufMessage.fileType.description")
    override fun getDefaultExtension(): @NlsSafe String = "pb"
    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData
    override fun isBinary() = true
}
