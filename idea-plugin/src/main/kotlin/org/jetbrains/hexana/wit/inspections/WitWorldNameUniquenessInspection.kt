package org.jetbrains.hexana.wit.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.wit.psi.*

class WitWorldNameUniquenessInspection : LocalInspectionTool() {
    override fun getDisplayName(): String {
        return HexanaBundle.message("wit.inspection.world.duplicate.name.display.name")
    }

    override fun getStaticDescription(): String {
        return HexanaBundle.message("wit.inspection.world.duplicate.name.display.name")
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : WitElementVisitor() {
            override fun visitWorldDefinition(world: WitWorldDefinition) {
                val imports = mutableMapOf<String, MutableList<PsiElement>>()
                val exports = mutableMapOf<String, MutableList<PsiElement>>()

                // Collect imports
                world.importDefinitionList.forEach { collectName(it.identifier, imports) }
                world.useDeclarationList.forEach { useDecl ->
                    useDecl.useNamesList?.useNamesItemList?.forEach { item ->
                        val nameIdentifier = item.nameIdentifier
                        if (nameIdentifier != null) {
                            val name = nameIdentifier.text.lowercase()
                            imports.computeIfAbsent(name) { mutableListOf() }.add(nameIdentifier)
                        }
                    }
                }
                world.typeDeclarationList.forEach { typeDecl ->
                    val nameIdentifier = (typeDecl as? PsiNameIdentifierOwner)?.nameIdentifier
                    if (nameIdentifier != null) {
                        val name = nameIdentifier.text.lowercase()
                        imports.computeIfAbsent(name) { mutableListOf() }.add(nameIdentifier)
                    }
                }
                world.includeDeclarationList.forEach { includeDecl ->
                    includeDecl.includeNamesList?.includeNamesItemList?.forEach { item ->
                        val nameIdentifier = item.nameIdentifier
                        if (nameIdentifier != null) {
                            val name = nameIdentifier.text.lowercase()
                            imports.computeIfAbsent(name) { mutableListOf() }.add(nameIdentifier)
                        }
                    }
                }

                // Collect exports
                world.exportDefinitionList.forEach { collectName(it.identifier, exports) }

                reportDuplicates(holder, imports, "imported")
                reportDuplicates(holder, exports, "exported")
            }
        }
    }

    private fun collectName(
        identifier: PsiElement?,
        names: MutableMap<String, MutableList<PsiElement>>
    ) {
        val nameElement = identifier
        if (nameElement != null) {
            val name = nameElement.text.lowercase()
            names.computeIfAbsent(name) { mutableListOf() }.add(nameElement)
        }
    }

    private fun reportDuplicates(
        holder: ProblemsHolder,
        names: Map<String, List<PsiElement>>,
        type: String
    ) {
        names.forEach { (_, elements) ->
            if (elements.size > 1) {
                elements.forEach { element ->
                    holder.registerProblem(
                        element,
                        HexanaBundle.message("wit.inspection.world.duplicate.name", type, element.text)
                    )
                }
            }
        }
    }
}
