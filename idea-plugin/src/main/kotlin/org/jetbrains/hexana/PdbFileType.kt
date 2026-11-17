package org.jetbrains.hexana

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

/**
 * Windows and Portable PDB debug symbols. Registered without extensions: `.pdb` is also the
 * Protein Data Bank text format, so these files are typed by content in [NativeBinaryFileTypeDetector].
 */
class PdbFileType : NativeBinaryFileType() {
    companion object {
        @JvmField val INSTANCE = PdbFileType()
    }
    override fun getName() = "PDB Debug Symbols"
    override fun getDescription() = HexanaBundle.message("hexana.pdb.fileType.description")
    override fun getDefaultExtension(): @NlsSafe String = "pdb"
    override fun getIcon(): Icon = AllIcons.FileTypes.BinaryData
}
