package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

class PcapFileType : NativeBinaryFileType() {
    companion object {
        @JvmField val INSTANCE = PcapFileType()
    }
    override fun getName() = "pcap"
    override fun getDescription() = HexanaBundle.message("hexana.pcap.fileType.description")
    override fun getDefaultExtension(): @NlsSafe String = "pcap"
    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData
}
