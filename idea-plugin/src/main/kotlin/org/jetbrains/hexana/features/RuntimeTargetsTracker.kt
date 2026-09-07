package org.jetbrains.hexana.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.hexana.shared.features.RuntimeTargets

/**
 * Holds the declared runtime targets and decides when a change is worth telling the UI about.
 *
 * Split out of [RuntimeTargetsService] so the policy — which is the part with judgement in it
 * — is a pure object, testable without a project fixture or a live VFS. The service owns the
 * VFS subscription and the file paths; this owns "did anything actually change".
 */
internal class RuntimeTargetsTracker {

    private val _changes = MutableStateFlow(0)

    /** Increments on every announced change; the key the badge and the tab recompose on. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    @Volatile
    private var cached: RuntimeTargets? = null

    /** The current declaration, reading through [read] on first use. */
    fun get(read: () -> RuntimeTargets): RuntimeTargets = cached ?: read().also { cached = it }

    /**
     * Records what the file says after an external change.
     *
     * Announces only when the declaration actually differs: the file is rewritten by saves
     * with no edits and by checkouts of an identical file, and neither should disturb the UI.
     * Also stays quiet when nothing has read the targets yet — there is no UI to refresh.
     */
    fun observe(fresh: RuntimeTargets) {
        val previous = cached
        cached = fresh
        if (previous != null && previous.targets != fresh.targets) _changes.value++
    }

    /**
     * Records a write this IDE just made, and announces it.
     *
     * Has to announce rather than wait for the write's own VFS event: by the time that
     * arrives the cache already matches disk, so [observe] would see no change and applying
     * the settings page would never refresh the UI.
     */
    fun recordSave(saved: RuntimeTargets) {
        val changed = cached?.targets != saved.targets
        cached = saved
        if (changed) _changes.value++
    }
}

/**
 * Whether a VFS event at [eventPath] could have changed the settings file at [settingsPath].
 *
 * Matches the exact path rather than any file merely *named* `runtimes.json`, and also matches
 * an ancestor directory: creating or deleting `.hexana/` wholesale — a checkout, a copied
 * directory — is reported on the directory, not on the file inside it.
 */
internal fun runtimeTargetsEventMatches(settingsPath: String, eventPath: String): Boolean =
    eventPath == settingsPath || settingsPath.startsWith("$eventPath/")
