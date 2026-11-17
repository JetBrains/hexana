package org.jetbrains.hexana.wit.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement

open class WitPsiElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiElement