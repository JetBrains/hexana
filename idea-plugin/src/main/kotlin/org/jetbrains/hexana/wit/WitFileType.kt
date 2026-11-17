package org.jetbrains.hexana.wit

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.HexanaBundle
import javax.swing.Icon

class WitFileType : LanguageFileType(WitLanguage) {
    companion object {
        @JvmField
        val INSTANCE = WitFileType()
    }

    override fun getName(): @NonNls String {
        return "wit"
    }

    override fun getDescription(): @NlsContexts.Label String {
        return HexanaBundle.message("wit.language.description")
    }

    override fun getDefaultExtension(): @NlsSafe String {
        return "wit"
    }

    override fun getIcon(): Icon? {
        return AllIcons.FileTypes.BinaryData
    }
}
