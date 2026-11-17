package org.jetbrains.hexana.actions

import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import org.jetbrains.hexana.ui.HexanaStructureElement

class RunWasmExportAction : AnAction("Run Export", "Run this exported Wasm function", com.intellij.icons.AllIcons.RunConfigurations.TestState.Run) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val element = getSelectedElement(e)
        e.presentation.isEnabledAndVisible = element?.runAction != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val element = getSelectedElement(e) ?: return
        element.runAction?.invoke()
    }

    private fun getSelectedElement(e: AnActionEvent): HexanaStructureElement? {
        // NAVIGATABLE in structure view is the AbstractTreeNode wrapper
        val navigatable = e.getData(CommonDataKeys.NAVIGATABLE)
        if (navigatable is HexanaStructureElement) return navigatable
        if (navigatable is AbstractTreeNode<*>) {
            (navigatable.value as? HexanaStructureElement)?.let { return it }
        }

        // SELECTED_ITEM (singular) — some structure view implementations provide this
        val selectedItem = e.getData(PlatformCoreDataKeys.SELECTED_ITEM)
        if (selectedItem is HexanaStructureElement) return selectedItem
        if (selectedItem is AbstractTreeNode<*>) {
            (selectedItem.value as? HexanaStructureElement)?.let { return it }
        }

        // SELECTED_ITEMS (plural) fallback
        val selected = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS)
        if (selected != null) {
            for (item in selected) {
                if (item is HexanaStructureElement) return item
                if (item is AbstractTreeNode<*>) {
                    (item.value as? HexanaStructureElement)?.let { return it }
                }
            }
        }

        // NAVIGATABLE_ARRAY fallback
        val navigatables = e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)
        if (navigatables != null) {
            for (nav in navigatables) {
                if (nav is HexanaStructureElement) return nav
                if (nav is AbstractTreeNode<*>) {
                    (nav.value as? HexanaStructureElement)?.let { return it }
                }
            }
        }

        return null
    }
}
