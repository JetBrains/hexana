package org.jetbrains.hexana.wat.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

fun getModule(file: PsiFile?): PsiElement? {
    return file?.children?.firstOrNull { it is WatModuleElement }
}

fun getDefinedFunctionByIndex(file: PsiFile?, index: Int): WatFuncElement? {
    val module = getModule(file) ?: return null
    return module.children.asSequence().filterIsInstance<WatFuncElement>().elementAtOrNull(index)
}

fun getDataByIndex(file: PsiFile?, index: Int): WatDataElement? {
    val module = getModule(file) ?: return null
    return module.children.asSequence().filterIsInstance<WatDataElement>().elementAtOrNull(index)
}
