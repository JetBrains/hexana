package org.jetbrains.hexana

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import javax.swing.JPanel

class WasmFileViewProvider : HexanaFileViewProvider {
    override fun getViews(file: VirtualFile): List<HexanaFileView> {
        if (file.fileType != WasmFileType.INSTANCE) {
            return emptyList()
        }

        val result = arrayListOf<HexanaFileView>()
        result.add(HexanaFileView(
            HexanaBundle.message("hexana.wasm.watFileView"),
            JPanel(BorderLayout()).apply {
                add(JBLabel("WAT code here"))
            }
        ))
        return result
    }
}