package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

class ParquetFileType : FileType {
    companion object {
        @JvmField val INSTANCE = ParquetFileType()
    }
    override fun getName() = "parquet"
    override fun getDescription() = HexanaBundle.message("hexana.parquet.fileType.description")
    override fun getDefaultExtension(): @NlsSafe String = "parquet"
    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData
    override fun isBinary() = true
}
