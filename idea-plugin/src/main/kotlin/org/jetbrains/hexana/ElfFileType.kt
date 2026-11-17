package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import org.jetbrains.annotations.NonNls
import javax.swing.Icon

class ElfFileType : NativeBinaryFileType() {

    companion object {
        @JvmField
        val INSTANCE = ElfFileType()
    }

    override fun getName(): @NonNls String = "ELF"

    override fun getDescription(): @NlsContexts.Label String =
        HexanaBundle.message("hexana.elfFileType.description")

    override fun getDefaultExtension(): @NlsSafe String = "elf"

    override fun getIcon(): Icon = AllIcons.FileTypes.Custom
}
