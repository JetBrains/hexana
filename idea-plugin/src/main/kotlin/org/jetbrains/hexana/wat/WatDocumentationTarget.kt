package org.jetbrains.hexana.wat

import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import org.jetbrains.hexana.wat.psi.WatPsiElement

class WatDocumentationTargetProvider : PsiDocumentationTargetProvider {

    override fun documentationTarget(
        element: PsiElement,
        originalElement: PsiElement?
    ): DocumentationTarget? {
        if (element is WatPsiElement) {
            return WatDocumentationTarget()
        }
        return  null
    }
}

class WatDocumentationTarget : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        return Pointer.hardPointer(this)
    }

    override fun computePresentation(): TargetPresentation {
        return TargetPresentation.builder("").presentation()
    }
}