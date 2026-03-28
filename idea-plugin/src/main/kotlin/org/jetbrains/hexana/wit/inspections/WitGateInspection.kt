package org.jetbrains.hexana.wit.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.wit.psi.*


//World, export, import, use, type, include, interface, funcitem, staticfuncitem, constructor
//Resource, variant, record, flags, enum, type

import java.util.ArrayDeque
import java.util.Deque

class WitGateInspection : LocalInspectionTool() {
    override fun getDisplayName(): String = HexanaBundle.message("wit.inspection.gate.display.name")
    override fun getStaticDescription(): String = HexanaBundle.message("wit.inspection.gate.display.name")

    private data class GateInfo(val since: String?, val unstable: Boolean)

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : WitElementVisitor() {
            private val gateStack: Deque<GateInfo> = ArrayDeque()

            private fun checkItem(element: PsiElement) {
                val gateItems = getGateItemsForOwner(element)
                
                // XOR check: @since and @unstable together
                val currentSince = gateItems.filterIsInstance<WitSinceGate>().firstOrNull()?.let { getVersion(it) }
                val currentUnstable = gateItems.any { it is WitUnstableGate }
                
                if (currentSince != null && currentUnstable && element !is WitInterfaceDefinition && element !is WitWorldDefinition) {
                    val target = getIdentifier(element)
                    holder.registerProblem(target, HexanaBundle.message("wit.inspection.gate.xor"))
                }

                val parentGate = gateStack.peek()
                if (parentGate != null && parentGate.since != null) {
                    if (currentSince == null && !currentUnstable) {
                        val target = getIdentifier(element)
                        holder.registerProblem(target, HexanaBundle.message("wit.inspection.gate.missing"))
                    } else if (currentSince != null && compareVersions(currentSince, parentGate.since) < 0) {
                        val target = getIdentifier(element)
                        holder.registerProblem(target, HexanaBundle.message("wit.inspection.gate.incompatible", currentSince, parentGate.since))
                    }
                }
                
                val effectiveSince = currentSince ?: parentGate?.since
                val effectiveUnstable = currentUnstable || (parentGate?.unstable ?: false)
                
                gateStack.push(GateInfo(effectiveSince, effectiveUnstable))
            }

            private fun afterVisit() {
                if (gateStack.isNotEmpty()) {
                    gateStack.pop()
                }
            }

            override fun visitWorldDefinition(o: WitWorldDefinition) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitInterfaceDefinition(o: WitInterfaceDefinition) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitTypeDeclaration(o: WitTypeDeclaration) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitFuncItem(o: WitFuncItem) {
                if (PsiTreeUtil.getParentOfType(o, WitResourceMethod::class.java, true) != null) {
                    return
                }
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitStaticFuncItem(o: WitStaticFuncItem) {
                if (PsiTreeUtil.getParentOfType(o, WitResourceMethod::class.java, true) != null) {
                    return
                }
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitResourceMethod(o: WitResourceMethod) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitRecordField(o: WitRecordField) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitEnumCase(o: WitEnumCase) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitVariantCase(o: WitVariantCase) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitExportDefinition(o: WitExportDefinition) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitImportDefinition(o: WitImportDefinition) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitUseDeclaration(o: WitUseDeclaration) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            override fun visitIncludeDeclaration(o: WitIncludeDeclaration) {
                checkItem(o)
                o.acceptChildren(this)
                afterVisit()
            }

            private fun getGateItemsForOwner(element: PsiElement): List<WitGateItem> {
                return when (element) {
                    is WitWorldDefinition -> element.gateItemList
                    is WitInterfaceDefinition -> element.gateItemList
                    is WitResourceMethod -> element.gateItemList
                    else -> getInlineGateItems(element)
                }
            }
        }.let { visitor ->
            object : PsiElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is WitPsiFile) {
                        element.acceptChildren(visitor)
                    }
                }
            }
        }
    }

    private fun getInlineGateItems(element: PsiElement): List<WitGateItem> {
        val result = mutableListOf<WitGateItem>()
        var prev = element.prevSibling
        while (prev != null) {
            if (prev is WitGateItem) {
                result.add(prev)
            } else if (prev is PsiWhiteSpace || prev is PsiComment) {
                // skip
            } else {
                break
            }
            prev = prev.prevSibling
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
            is WitWorldDefinition -> element.nameIdentifier ?: element
            is WitInterfaceDefinition -> element.nameIdentifier ?: element
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
