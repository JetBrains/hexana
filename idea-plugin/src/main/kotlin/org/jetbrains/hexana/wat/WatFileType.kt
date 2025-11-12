package org.jetbrains.hexana.wat

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.HexanaBundle
import javax.swing.Icon

class WatFileType : LanguageFileType(WatLanguage) {
    companion object {
        @JvmField
        val INSTANCE = WatFileType()
    }

    override fun getName(): @NonNls String {
        return "wat"
    }

    override fun getDescription(): @NlsContexts.Label String {
        return HexanaBundle.message("wat.language.description")
    }

    override fun getDefaultExtension(): @NlsSafe String {
        return "wat"
    }

    override fun getIcon(): Icon {
        return AllIcons.FileTypes.BinaryData
    }
}
