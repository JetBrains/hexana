package org.jetbrains.hexana

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class HexanaTestAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        Messages.showInfoMessage(HexanaBundle.message("hexana.test.action"), HexanaBundle.message("hexana.test.action"))
    }
}