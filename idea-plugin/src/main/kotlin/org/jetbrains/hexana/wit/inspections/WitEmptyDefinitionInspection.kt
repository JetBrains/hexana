package org.jetbrains.hexana.wit.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.wit.psi.*

class WitEmptyDefinitionInspection : LocalInspectionTool() {
    override fun getStaticDescription(): String {
        return HexanaBundle.message("wit.inspection.empty.definition", "Definition")
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : WitElementVisitor() {
            override fun visitEnumDefinition(o: WitEnumDefinition) {
                if (o.enumCaseList.isEmpty()) {
                    registerProblem(holder, o, "enum")
                }
            }

            override fun visitRecordDefinition(o: WitRecordDefinition) {
                if (o.recordFieldList.isEmpty()) {
                    registerProblem(holder, o, "record")
                }
            }

            override fun visitVariantDefinition(o: WitVariantDefinition) {
                if (o.variantCaseList.isEmpty()) {
                    registerProblem(holder, o, "variant")
                }
            }

            override fun visitResourceDefinition(o: WitResourceDefinition) {
                // Check if it's the brace version and it's empty
                val lastChild = o.lastChild
                if (lastChild.text == "}" && o.resourceMethodList.isEmpty()) {
                    registerProblem(holder, o, "resource")
                }
            }
        }
    }

    private fun registerProblem(holder: ProblemsHolder, o: WitTypeDeclaration, type: String) {
        val identifier = o.nameIdentifier ?: return
        holder.registerProblem(
            identifier,
            HexanaBundle.message("wit.inspection.empty.definition", type)
        )
    }
}
