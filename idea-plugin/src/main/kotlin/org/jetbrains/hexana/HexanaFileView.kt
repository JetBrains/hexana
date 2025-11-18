package org.jetbrains.hexana

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

/**
 * Additional view for a binary file
 */
data class HexanaFileView(
    val tabName: @Nls(capitalization = Nls.Capitalization.Title) String,
    val component: JComponent
)


interface HexanaFileViewProvider {
    companion object {
        @JvmField
        val EP_NAME: ExtensionPointName<HexanaFileViewProvider> =
            ExtensionPointName.create("org.jetbrains.hexana.hexanaFileViewProvider")

        fun getAllViews(file: VirtualFile): List<HexanaFileView> {
            return EP_NAME.extensionList.flatMap { it.getViews(file) }
        }
    }

    fun getViews(file: VirtualFile): List<HexanaFileView>
}
