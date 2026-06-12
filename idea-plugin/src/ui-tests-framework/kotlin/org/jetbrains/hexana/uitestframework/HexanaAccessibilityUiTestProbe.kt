package org.jetbrains.hexana.uitestframework

import com.intellij.openapi.application.ApplicationManager
import java.awt.Window
import java.util.IdentityHashMap
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext

object HexanaAccessibilityUiTestProbe {
    @JvmStatic
    fun accessibleNames(): List<String> =
        runOnEdt {
            val visited = IdentityHashMap<AccessibleContext, Boolean>()
            Window.getWindows()
                .asSequence()
                .filter { it.isShowing }
                .flatMap { window ->
                    sequence {
                        collectAccessibleNames(window.accessibleContext, visited)
                    }
                }
                .distinct()
                .sorted()
                .toList()
        }

    private suspend fun SequenceScope<String>.collectAccessibleNames(
        context: AccessibleContext?,
        visited: IdentityHashMap<AccessibleContext, Boolean>,
    ) {
        if (context == null || visited.put(context, true) != null) return

        context.accessibleName?.takeIf { it.isNotBlank() }?.let { yield(it) }

        val childCount = runCatching { context.accessibleChildrenCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            val child = runCatching { context.getAccessibleChild(index) as? Accessible }.getOrNull()
            collectAccessibleNames(child?.accessibleContext, visited)
        }
    }

    private fun <T> runOnEdt(action: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            return action()
        }

        var result: Result<T>? = null
        app.invokeAndWait {
            result = runCatching(action)
        }
        return result!!.getOrThrow()
    }
}
