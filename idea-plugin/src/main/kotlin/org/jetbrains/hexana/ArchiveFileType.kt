package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import org.jetbrains.annotations.NonNls
import javax.swing.Icon

/**
 * Static-library archive (`!<arch>\n`): Unix `ar` archives (`.a`, members are ELF/Mach-O
 * objects) and Windows COFF import libraries (`.lib`). Recognised by content magic via
 * [NativeBinaryFileTypeDetector], NOT by extension — `.lib` in particular may be a non-archive
 * MSVC import library, and binding the extension would force the wrong type onto it. Opens in
 * the binary editor's archive member list ([org.jetbrains.hexana.ui.panel.ArchiveMembersPanel]).
 */
class ArchiveFileType : NativeBinaryFileType() {

    companion object {
        @JvmField
        val INSTANCE = ArchiveFileType()
    }

    override fun getName(): @NonNls String = "Archive"

    override fun getDescription(): @NlsContexts.Label String =
        HexanaBundle.message("hexana.archiveFileType.description")

    override fun getDefaultExtension(): @NlsSafe String = "a"

    override fun getIcon(): Icon = AllIcons.FileTypes.Archive
}
