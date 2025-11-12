package org.jetbrains.hexana.wat.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import org.jetbrains.hexana.wat.WatFileType
import org.jetbrains.hexana.wat.WatLanguage

class WatPsiFile(fileViewProvider: FileViewProvider) : PsiFileBase(fileViewProvider, WatLanguage) {
    override fun getFileType(): FileType {
        return WatFileType.INSTANCE
    }
}