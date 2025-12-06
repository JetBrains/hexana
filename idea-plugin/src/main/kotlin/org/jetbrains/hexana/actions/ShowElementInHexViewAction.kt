package org.jetbrains.hexana.actions

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wat.psi.WatDataElement
import org.jetbrains.hexana.wat.psi.WatFuncElement
import org.jetbrains.hexana.wat.psi.WatModuleElement
import org.jetbrains.hexana.wat.psi.WatNameIdentifierOwner

class ShowElementInHexViewAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val hexanaEditor = e.getData(HexanaFileEditor.KEY)
        val wasmBinaryProvider = hexanaEditor?.binaryProvider as? WasmBinaryProvider
        if (wasmBinaryProvider == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val element = findNamedElement(e)
        if (element == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isVisible = true
        e.presentation.isEnabled = getOffsetProvider(wasmBinaryProvider, element) != null
        if (e.place == ActionPlaces.EDITOR_POPUP) {
            e.presentation.text = HexanaBundle.message("action.hexana.showElementInHexView.editorText")
        }
    }


    override fun actionPerformed(e: AnActionEvent) {
        val hexanaEditor = e.getData(HexanaFileEditor.KEY) ?: return
        val wasmBinaryProvider = hexanaEditor.binaryProvider as? WasmBinaryProvider ?: return
        val element = findNamedElement(e) ?: return
        val range = getOffsetProvider(wasmBinaryProvider, element)?.getOffset() ?: return
        if (hexanaEditor.stateHolder.topFraction < 0.1) {
            hexanaEditor.stateHolder.setTopFraction(0.4f)
        }
        hexanaEditor.stateHolder.setSelection(range)
        hexanaEditor.stateHolder.scrollHexViewTo(range.first)
    }

    fun findNamedElement(e: AnActionEvent): WatNameIdentifierOwner? {
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val element = file.findElementAt(editor.caretModel.offset)
        return PsiTreeUtil.getParentOfType(element, WatNameIdentifierOwner::class.java, false)
    }

    interface ElementOffsetProvider {
        fun getOffset(): LongRange?
    }

    fun getOffsetProvider(wasmBinaryProvider: WasmBinaryProvider, e: WatNameIdentifierOwner): ElementOffsetProvider? {
        return when (e) {
            is WatDataElement -> object : ElementOffsetProvider {
                override fun getOffset(): LongRange? {
                    return getDataOffsetInHex(wasmBinaryProvider, e)
                }
            }
            is WatFuncElement -> object : ElementOffsetProvider {
                override fun getOffset(): LongRange? {
                    return getFuncOffsetInHex(wasmBinaryProvider, e)
                }
            }
            else -> null
        }
    }

    fun getDataOffsetInHex(wasmBinaryProvider: WasmBinaryProvider, e: WatDataElement): LongRange? {
        val module = e.parent as? WatModuleElement ?: return null
        var index = 0
        for (child in module.children) {
            if (child == e) {
                break
            }
            if (child is WatDataElement) {
                index++
            }
        }
        val record = wasmBinaryProvider.data.getOrNull(index) ?: return null
        return LongRange(record.offset, record.offset + record.recordLength - 1)
    }

    fun getFuncOffsetInHex(wasmBinaryProvider: WasmBinaryProvider, e: WatFuncElement): LongRange? {
        val module = e.parent as? WatModuleElement ?: return null
        var index = 0
        for (child in module.children) {
            if (child == e) {
                break
            }
            if (child is WatFuncElement) {
                index++
            }
        }
        val record = wasmBinaryProvider.getCodeRecordByIndex(index) ?: return null
        return LongRange(record.offset, record.offset + record.size.toInt() - 1)
    }
}
