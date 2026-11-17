package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import org.jetbrains.annotations.NonNls
import javax.swing.Icon

class WasmFileType : FileType {

    companion object {
        @JvmField
        val INSTANCE = WasmFileType()
    }

    override fun getName(): @NonNls String {
        return "wasm"
    }

    override fun getDescription(): @NlsContexts.Label String {
        return HexanaBundle.message("hexana.wasm.fileType.description")
    }

    override fun getDefaultExtension(): @NlsSafe String {
        return "wasm"
    }

    override fun getIcon(): Icon {
        return AllIcons.FileTypes.BinaryData
    }

    override fun isBinary(): Boolean {
        return true
    }
}
