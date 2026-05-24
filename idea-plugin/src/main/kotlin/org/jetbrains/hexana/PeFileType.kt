package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import org.jetbrains.annotations.NonNls
import javax.swing.Icon

class PeFileType : NativeBinaryFileType() {

    companion object {
        @JvmField
        val INSTANCE = PeFileType()
    }

    override fun getName(): @NonNls String = "PE"

    override fun getDescription(): @NlsContexts.Label String =
        HexanaBundle.message("hexana.peFileType.description")

    override fun getDefaultExtension(): @NlsSafe String = "exe"

    override fun getIcon(): Icon = AllIcons.FileTypes.Custom
}
