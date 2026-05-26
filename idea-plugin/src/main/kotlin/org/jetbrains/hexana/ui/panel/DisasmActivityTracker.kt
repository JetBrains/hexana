package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks in-flight Capstone disassembly work so the IDE status bar can
 * surface a progress widget while the disassembly view is decoding /
 * laying out chunks.
 *
 * Granularity: one increment per outstanding `AsyncFunctionBody`
 * decode+render pair, plus one per active `loadCapstoneDisassembly`
 * call. The counter is best-effort (it represents UI-visible activity,
 * not a precise queue depth); for example, when a coroutine is
 * cancelled mid-decode the `finally` block still calls [end].
 *
 * Project-level service so the count is scoped to a single IDE project;
 * multiple open projects each have their own status bar widget.
 *
 * Thread-safety: the counter is an [AtomicInteger]; the listener list
 * is a [CopyOnWriteArrayList], so notifications are safe from any
 * thread but listeners must NOT acquire EDT-bound locks inside their
 * callback (the increment may come from a background coroutine).
 */
@Service(Service.Level.PROJECT)
internal class DisasmActivityTracker {

    private val active = AtomicInteger(0)
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()

    fun begin() {
        notify(active.incrementAndGet())
    }

    fun end() {
        notify(active.decrementAndGet())
    }

    fun snapshot(): Int = active.get()

    /** Subscribe to changes. Listener fires with the new count on every transition. */
    fun addListener(listener: (Int) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Int) -> Unit) {
        listeners -= listener
    }

    private fun notify(count: Int) {
        listeners.forEach { it(count) }
    }

    companion object {
        fun getInstance(project: Project): DisasmActivityTracker =
            project.getService(DisasmActivityTracker::class.java)
    }
}