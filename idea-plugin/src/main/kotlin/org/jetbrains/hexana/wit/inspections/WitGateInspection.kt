package org.jetbrains.hexana.wit.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.wit.psi.*

class WitGateInspection : LocalInspectionTool() {
    override fun getDisplayName(): String = HexanaBundle.message("wit.inspection.gate.display.name")
    override fun getStaticDescription(): String = HexanaBundle.message("wit.inspection.gate.display.name")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : WitElementVisitor() {
            override fun visitSinceGate(o: WitSinceGate) = checkXor(o, holder)
            override fun visitUnstableGate(o: WitUnstableGate) = checkXor(o, holder)

            override fun visitFuncItem(o: WitFuncItem) {
                if (PsiTreeUtil.getParentOfType(o, WitResourceMethod::class.java) != null) return
                checkCompatibility(o, holder)
            }
            override fun visitTypeDeclaration(o: WitTypeDeclaration) {
                // To avoid duplicate reports, we only check compatibility here if it's NOT a resource.
                if (o is WitResourceDefinition) {
                   // return // Skip, as we want to check resource methods specifically, and resource itself here.
                }
                checkCompatibility(o, holder)
            }
            override fun visitResourceMethod(o: WitResourceMethod) = checkCompatibility(o, holder)
            override fun visitStaticFuncItem(o: WitStaticFuncItem) {
                if (PsiTreeUtil.getParentOfType(o, WitResourceMethod::class.java) != null) return
                checkCompatibility(o, holder)
            }
            override fun visitRecordField(o: WitRecordField) = checkCompatibility(o, holder)
            override fun visitEnumCase(o: WitEnumCase) = checkCompatibility(o, holder)
            override fun visitVariantCase(o: WitVariantCase) = checkCompatibility(o, holder)

            // Specifically avoid visiting more specific type declarations if we handle them in visitTypeDeclaration
            override fun visitEnumDefinition(o: WitEnumDefinition) {}
            override fun visitRecordDefinition(o: WitRecordDefinition) {}
            override fun visitVariantDefinition(o: WitVariantDefinition) {}
            override fun visitFlagsDefinition(o: WitFlagsDefinition) {}
            override fun visitTypeDefinition(o: WitTypeDefinition) {}
            override fun visitResourceDefinition(o: WitResourceDefinition) {}
        }
    }

    private fun checkXor(o: WitGateItem, holder: ProblemsHolder) {
        // Only report on the first gate item to avoid duplicate reports
        var prev = PsiTreeUtil.prevVisibleLeaf(o)
        if (prev != null && PsiTreeUtil.getParentOfType(prev, WitGateItem::class.java) != null) return

        var hasSince = false
        var hasUnstable = false
        
        var current: PsiElement? = o
        while (current is WitGateItem) {
            if (current is WitSinceGate) hasSince = true
            if (current is WitUnstableGate) hasUnstable = true
            
            val next = PsiTreeUtil.nextVisibleLeaf(current)
            if (next != null && PsiTreeUtil.getParentOfType(next, WitGateItem::class.java) != null) {
                current = PsiTreeUtil.getParentOfType(next, WitGateItem::class.java)
            } else {
                current = null
            }
        }

        if (hasSince && hasUnstable) {
            val owner = findGateOwner(o) ?: return
            val target = getIdentifier(owner)
            holder.registerProblem(target, HexanaBundle.message("wit.inspection.gate.xor"))
        }
    }

    private fun findGateOwner(gateItem: WitGateItem): PsiElement? {
        var current: PsiElement? = gateItem
        while (current != null) {
            var next = PsiTreeUtil.nextVisibleLeaf(current)
            while (next != null) {
                if (next.parent is WitGateItem) {
                    next = PsiTreeUtil.nextVisibleLeaf(next)
                    continue
                }
                val p = next.parent
                if (p is WitInterfaceDefinition || p is WitWorldDefinition || p is WitTypeDeclaration || p is WitFuncItem || p is WitResourceMethod) return p
                if (next.text == "}" || next.text == "{") break
                next = PsiTreeUtil.nextVisibleLeaf(next)
            }
            current = current.parent
        }
        return null
    }

    private fun checkCompatibility(item: PsiElement, holder: ProblemsHolder) {
        val container = findGatedContainer(item) ?: return
        val containerGate = getGateItems(container)
        val containerSince = containerGate.filterIsInstance<WitSinceGate>().firstOrNull() ?: return
        val containerVersion = getVersion(containerSince) ?: return

        val itemGate = getGateItems(item)
        if (itemGate.isEmpty()) {
            val target = getIdentifier(item)
            holder.registerProblem(target, HexanaBundle.message("wit.inspection.gate.missing"))
            return
        }

        val itemSince = itemGate.filterIsInstance<WitSinceGate>().firstOrNull()
        if (itemSince != null) {
            val itemVersion = getVersion(itemSince) ?: return
            if (compareVersions(itemVersion, containerVersion) < 0) {
                val target = getIdentifier(item)
                holder.registerProblem(target, HexanaBundle.message("wit.inspection.gate.incompatible", itemVersion, containerVersion))
            }
        }
    }

    private fun findGatedContainer(item: PsiElement): PsiElement? {
        var current = item.parent
        while (current != null) {
            if (getGateItems(current).isNotEmpty()) return current
            current = current.parent
        }
        return null
    }

    private fun getGateItems(element: PsiElement): List<WitGateItem> {
        if (element is WitInterfaceDefinition) return element.gateItemList
        if (element is WitWorldDefinition) return element.gateItemList
        if (element is WitResourceMethod) return element.gateItemList

        val result = mutableListOf<WitGateItem>()
        var prev = PsiTreeUtil.prevVisibleLeaf(element)
        while (prev != null) {
            val gateItem = PsiTreeUtil.getParentOfType(prev, WitGateItem::class.java)
            if (gateItem != null) {
                result.add(gateItem)
            } else {
                val text = prev.text
                if (text != ";" && text != "}" && text != "{") {
                   // keep looking?
                } else {
                    break
                }
            }
            
            val p = prev.parent
            if (p is WitFuncItem || p is WitTypeDeclaration || p is WitRecordField || p is WitEnumCase || p is WitVariantCase) {
                if (p != element) break
            }
            
            prev = PsiTreeUtil.prevVisibleLeaf(prev)
        }
        
        return result
    }

    private fun getIdentifier(element: PsiElement): PsiElement {
        return when (element) {
            is WitTypeDeclaration -> element.nameIdentifier ?: element
            is WitFuncItem -> element.nameIdentifier ?: element
            is WitRecordField -> element.identifier
            is WitEnumCase -> element.identifier
            is WitVariantCase -> element.identifier
            is WitResourceMethod -> {
                element.funcItem?.nameIdentifier ?: 
                element.staticFuncItem?.nameIdentifier ?: 
                element.firstChild // Fallback
            }
            else -> element
        }
    }

    private fun getVersion(since: WitSinceGate): String? {
        return since.versionField.semver.text
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split('.').mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until minOf(parts1.size, parts2.size)) {
            if (parts1[i] != parts2[i]) return parts1[i].compareTo(parts2[i])
        }
        return parts1.size.compareTo(parts2.size)
    }
}
