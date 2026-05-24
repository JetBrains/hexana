package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import org.jetbrains.annotations.NonNls
import javax.swing.Icon

class MachOFileType : NativeBinaryFileType() {

    companion object {
        @JvmField
        val INSTANCE = MachOFileType()
    }

    override fun getName(): @NonNls String = "Mach-O"

    override fun getDescription(): @NlsContexts.Label String =
        HexanaBundle.message("hexana.machOFileType.description")

    override fun getDefaultExtension(): @NlsSafe String = "dylib"

    override fun getIcon(): Icon = AllIcons.FileTypes.Custom
}
