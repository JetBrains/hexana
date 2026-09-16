package org.jetbrains.hexana.run

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Transport abstraction used by [buildDebuggerConsolePanel].
 *
 * Implementations bridge the console UI to a concrete debug adapter
 * (e.g. an [LldbCommunicator]).
 */
interface DebuggerConsoleTransport {
    /** Session kind used to classify commands via [classifyConsoleCommand]. */
    val sessionKind: ConsoleSessionKind

    /**
     * Sends [command] to the adapter and returns the output lines.
     *
     * Called off the EDT on a pooled thread.
     */
    fun execute(command: String): List<String>

    /**
     * Returns `false` while the debuggee is running or after the session
     * has exited. The console prints a reminder instead of forwarding
     * [Allow][ConsoleCommandRuling.Allow] commands when this is `false`.
     */
    fun isAcceptingCommands(): Boolean

    /**
     * Performs the IDE action corresponding to an intercepted command.
     *
     * Called on the EDT. Implementations must not block.
     */
    fun performIntercept(action: InterceptAction)
}

/**
 * Builds a Swing [JComponent] that renders a platform [ConsoleView] for output
 * and a [JBTextField] input bar for sending raw debugger commands.
 *
 * Input flow on Enter:
 * 1. The command is echoed as USER_INPUT.
 * 2. [classifyConsoleCommand] classifies it for the transport's [sessionKind].
 * 3. [ConsoleCommandRuling.Block]: the reason is printed as ERROR_OUTPUT.
 * 4. [ConsoleCommandRuling.Intercept]: the message is printed as SYSTEM_OUTPUT
 *    and the transport's [performIntercept] is called on the EDT.
 * 5. [ConsoleCommandRuling.Allow]: if [isAcceptingCommands] is false a
 *    reminder is printed as SYSTEM_OUTPUT; otherwise [execute] is called on a
 *    pooled thread and the output lines are printed as NORMAL_OUTPUT.
 *
 * The [ConsoleView] is registered as a child of [parentDisposable].
 *
 * @param project the IDE project (used to create the platform console builder)
 * @param transport the debug adapter transport
 * @param parentDisposable parent [Disposable] whose disposal cleans up the console
 * @return the panel [JComponent] ready to embed in a [RunnerLayoutUi] content
 */
fun buildDebuggerConsolePanel(
    project: Project,
    transport: DebuggerConsoleTransport,
    parentDisposable: Disposable,
): JComponent {
    val consoleView: ConsoleView = TextConsoleBuilderFactory.getInstance()
        .createBuilder(project)
        .console
    Disposer.register(parentDisposable, consoleView)
    consoleView.component.accessibleContext.accessibleName = "Debugger console output"

    val inputField = JBTextField().apply {
        emptyText.text = "Enter debugger command…"
        accessibleContext.accessibleName = "Debugger command input"
    }

    // Command history -- most-recent at index 0.
    val history = ArrayDeque<String>()
    var historyIndex = -1

    inputField.addActionListener {
        val raw = inputField.text.trim()
        if (raw.isEmpty()) return@addActionListener
        inputField.text = ""
        historyIndex = -1
        if (history.isEmpty() || history.first() != raw) {
            history.addFirst(raw)
            if (history.size > HISTORY_LIMIT) history.removeLast()
        }
        handleConsoleInput(raw, transport, consoleView)
    }

    inputField.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            when (e.keyCode) {
                KeyEvent.VK_UP -> {
                    if (history.isEmpty()) return
                    historyIndex = (historyIndex + 1).coerceAtMost(history.size - 1)
                    inputField.text = history[historyIndex]
                    e.consume()
                }
                KeyEvent.VK_DOWN -> {
                    if (historyIndex <= 0) {
                        historyIndex = -1
                        inputField.text = ""
                    } else {
                        historyIndex--
                        inputField.text = history[historyIndex]
                    }
                    e.consume()
                }
            }
        }
    })

    return JPanel(BorderLayout()).apply {
        add(consoleView.component, BorderLayout.CENTER)
        add(inputField, BorderLayout.SOUTH)
    }
}

// ---------------------------------------------------------------------------
// Internal
// ---------------------------------------------------------------------------

private const val HISTORY_LIMIT = 100

private fun handleConsoleInput(
    command: String,
    transport: DebuggerConsoleTransport,
    consoleView: ConsoleView,
) {
    // Echo the command as user input.
    val promptPrefix = when {
        transport.sessionKind == ConsoleSessionKind.GDB_DAP -> "(gdb) "
        else -> "(lldb) "
    }
    consoleView.print("$promptPrefix$command\n", ConsoleViewContentType.USER_INPUT)

    when (val ruling = classifyConsoleCommand(command, transport.sessionKind)) {
        is ConsoleCommandRuling.Block -> {
            consoleView.print("error: ${ruling.reason}\n", ConsoleViewContentType.ERROR_OUTPUT)
        }
        is ConsoleCommandRuling.Intercept -> {
            consoleView.print("${ruling.message}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            transport.performIntercept(ruling.action)
        }
        ConsoleCommandRuling.Allow -> {
            if (!transport.isAcceptingCommands()) {
                consoleView.print(
                    "Target is running; pause execution first.\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT,
                )
                return
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                val lines = transport.execute(command)
                ApplicationManager.getApplication().invokeLater {
                    for (line in lines) {
                        consoleView.print("$line\n", ConsoleViewContentType.NORMAL_OUTPUT)
                    }
                }
            }
        }
    }
}
