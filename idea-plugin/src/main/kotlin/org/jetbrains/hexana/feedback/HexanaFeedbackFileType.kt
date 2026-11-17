package org.jetbrains.hexana.feedback

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

internal class HexanaFeedbackFileType private constructor() : FileType {
    companion object {
        val INSTANCE = HexanaFeedbackFileType()
    }

    override fun getName(): String = "Hexana feedback"

    override fun getDescription(): String = "Hexana feedback note"

    override fun getDefaultExtension(): String = "hexana-feedback"

    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData

    override fun isBinary(): Boolean = false
}
