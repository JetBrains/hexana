package org.jetbrains.hexana

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

/**
 * Additional view for a binary file
 */
interface HexanaFileView : Disposable {

    val tabName: @Nls(capitalization = Nls.Capitalization.Title) String

    val component: JComponent

}


interface HexanaFileViewProvider {
    companion object {
        @JvmField
        val EP_NAME: ExtensionPointName<HexanaFileViewProvider> =
            ExtensionPointName.create("org.jetbrains.hexana.hexanaFileViewProvider")

        fun getAllViews(project: Project, file: VirtualFile): List<HexanaFileView> {
            return EP_NAME.extensionList.flatMap { it.getViews(project, file) }
        }
    }

    /**
     * Returns additional views for the given file.
     * Called on EDT.
     */
    fun getViews(project: Project, file: VirtualFile): List<HexanaFileView>
}
