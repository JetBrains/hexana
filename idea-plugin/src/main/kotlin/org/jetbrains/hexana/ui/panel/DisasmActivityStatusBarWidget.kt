package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.awt.event.MouseEvent

/**
 * IDE status bar widget that surfaces in-flight disassembly work. Reads
 * the project-level [DisasmActivityTracker] and renders the count as
 * `Disassembling: N` when N > 0; hidden when the count is zero.
 *
 * Hidden-when-idle is the intended UX: a quiet status bar by default,
 * with the widget appearing only when the user opens the disassembly
 * view and decode work begins.
 */
internal class DisasmActivityStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "Hexana: Disassembly Activity"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget =
        DisasmActivityStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val WIDGET_ID: String = "Hexana.Disasm.Activity"
    }
}

internal class DisasmActivityStatusBarWidget(
    private val project: Project,
) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null

    /**
     * Trampolines tracker notifications onto the EDT so [StatusBar.updateWidget]
     * is always called from the EDT (the platform contract). The listener
     * itself fires from whichever thread incremented the counter — typically
     * a background coroutine inside `AsyncFunctionBody`.
     */
    private val listener: (Int) -> Unit = { _ ->
        ApplicationManager.getApplication().invokeLater(
            { statusBar?.updateWidget(ID()) },
            project.disposed,
        )
    }

    override fun ID(): String = DisasmActivityStatusBarWidgetFactory.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        DisasmActivityTracker.getInstance(project).addListener(listener)
    }

    override fun dispose() {
        DisasmActivityTracker.getInstance(project).removeListener(listener)
        statusBar = null
    }

    // -- TextPresentation --

    override fun getText(): String {
        val count = DisasmActivityTracker.getInstance(project).snapshot()
        return if (count > 0) "Disassembling: $count" else ""
    }

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String =
        "Hexana: in-flight disassembly decode / render operations."

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent>? = null
}