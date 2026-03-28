package org.jetbrains.hexana.wit.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.wit.psi.WitElementVisitor
import org.jetbrains.hexana.wit.psi.WitUseDeclaration

class WitUseDeclarationInspection : LocalInspectionTool() {
    override fun getStaticDescription(): String {
        return HexanaBundle.message("wit.inspection.use.declaration.missing.names")
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : WitElementVisitor() {
            override fun visitUseDeclaration(o: WitUseDeclaration) {
                if (o.useNamesList == null) {
                    holder.registerProblem(
                        o,
                        HexanaBundle.message("wit.inspection.use.declaration.missing.names")
                    )
                }
            }
        }
    }
}
