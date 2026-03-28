package org.jetbrains.hexana.wit.inspections

import com.intellij.codeInspection.*
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.smartPointers.SmartPsiElementPointerImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.xml.util.PsiElementPointer
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.wit.psi.*

class WitMissingSemicolonInspection : LocalInspectionTool() {
    override fun getDisplayName(): String = HexanaBundle.message("wit.inspection.missing.semicolon.display.name")

    override fun getStaticDescription(): String = HexanaBundle.message("wit.inspection.missing.semicolon")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : WitElementVisitor() {
            override fun visitToplevelUseDeclaration(o: WitToplevelUseDeclaration) {
                checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
            }

            override fun visitExportDefinition(o: WitExportDefinition) {
                // export-definition ::= EXPORT IDENTIFIER ':' extern-type
                //                     | EXPORT use-path ';'?
                if (o.externType == null) {
                    checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
                }
            }

            override fun visitImportDefinition(o: WitImportDefinition) {
                // import-definition ::= IMPORT IDENTIFIER ':' extern-type
                //                     | IMPORT use-path ';'?
                if (o.externType == null) {
                    checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
                }
            }

            override fun visitExternType(o: WitExternType) {
                // extern-type ::= func-type ';'? | extern-interface
                // We only check if it's the func-type branch
                if (o.funcType != null) {
                    checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
                }
            }

            override fun visitIncludeDeclaration(o: WitIncludeDeclaration) {
                // include-declaration ::= INCLUDE use-path 'with' '{' include-names-list '}'
                //                        | INCLUDE use-path ';'?
                // We check the second branch
                if (o.includeNamesList == null) {
                    checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
                }
            }

            override fun visitFuncItem(o: WitFuncItem) {
                checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
            }

            override fun visitUseDeclaration(o: WitUseDeclaration) {
                if (o.useNamesList != null) { // avoid noise of two errors if names list is missing
                    checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
                }
            }

            override fun visitTypeDefinition(o: WitTypeDefinition) {
                checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
            }

            override fun visitResourceDefinition(o: WitResourceDefinition) {
                // resource-definition ::= RESOURCE IDENTIFIER '{' resource-items? '}'
                //                         | RESOURCE IDENTIFIER ';'?
                // We check the second branch
                if (o.node.findChildByType(WitElementTypes.OPEN_BRACE) == null) {
                    checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
                }
            }

            override fun visitStaticFuncItem(o: WitStaticFuncItem) {
                checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
            }

            override fun visitResourceMethod(o: WitResourceMethod) {
                // resource-method ::= gate? (func-item
                //                           | static-func-item
                //                           | CONSTRUCTOR param-list ';'?)
                // func-item and static-func-item handle their own semicolons.
                // We only need to check CONSTRUCTOR.
                if (o.node.findChildByType(WitElementTypes.CONSTRUCTOR) != null) {
                    checkSemicolon(o, o.node.findChildByType(WitElementTypes.SEMI)?.psi)
                }
            }

            private fun checkSemicolon(o: PsiElement, semi: PsiElement?) {
                if (semi == null) {
                    val lastChild = PsiTreeUtil.getDeepestVisibleLast(o)
                    if (lastChild != null) {
                        val nextLeaf = PsiTreeUtil.nextLeaf(lastChild)
                        if (nextLeaf is PsiWhiteSpace && !nextLeaf.textContains('\n')) {
                            holder.registerProblem(
                                nextLeaf,
                                HexanaBundle.message("wit.inspection.missing.semicolon"),
                                ProblemHighlightType.GENERIC_ERROR,
                                AddSemicolonFix(o)
                            )
                        } else {
                            holder.registerProblem(
                                lastChild,
                                HexanaBundle.message("wit.inspection.missing.semicolon"),
                                ProblemHighlightType.GENERIC_ERROR,
                                AddSemicolonFix(o)
                            )
                        }
                    }
                }
            }
        }
    }

    private class AddSemicolonFix(element: PsiElement) : LocalQuickFix {
        private val pointer = SmartPointerManager.createPointer(element)
        override fun getFamilyName(): String = HexanaBundle.message("wit.inspection.missing.semicolon.fix")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = pointer.element ?: return
            val factory = WitElementFactory(project)
            val semi = factory.createSemicolon()
            val leaf = if (element.lastChild is PsiWhiteSpace) {
                PsiTreeUtil.prevLeaf(element.lastChild)
            } else {
                element.lastChild
            }
            if (leaf != null) {
                element.addAfter(semi, leaf)
            } else {
                element.add(semi)
            }
        }
    }
}
