package org.jetbrains.hexana.wat.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement

class WatPsiElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiElement