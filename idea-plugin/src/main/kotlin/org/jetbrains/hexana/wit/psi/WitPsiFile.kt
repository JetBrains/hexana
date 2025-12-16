package org.jetbrains.hexana.wit.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import org.jetbrains.hexana.wit.WitFileType
import org.jetbrains.hexana.wit.WitLanguage

class WitPsiFile(fileViewProvider: FileViewProvider) : PsiFileBase(fileViewProvider, WitLanguage) {
    override fun getFileType(): FileType {
        return WitFileType.INSTANCE
    }
}
