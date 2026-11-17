package org.jetbrains.hexana.features

import org.jetbrains.hexana.shared.features.RuntimeTarget
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `.hexana/runtimes.json` is written by other tools — the VS Code extension, a text editor, a
 * `git pull` — so the service follows the file rather than caching it once. This covers the
 * judgement in that: which changes are worth telling the UI about. No IDE, no VFS.
 */
class RuntimeTargetsTrackerTest {

    private val chrome = RuntimeTargets(listOf(RuntimeTarget("Chrome", "120")))
    private val chromeNewer = RuntimeTargets(listOf(RuntimeTarget("Chrome", "137")))
    private val wasmtime = RuntimeTargets(listOf(RuntimeTarget("Wasmtime", "26.0")))

    @Test
    fun `reads through on first use and caches afterwards`() {
        val tracker = RuntimeTargetsTracker()
        var reads = 0

        val first = tracker.get { reads++; chrome }
        val second = tracker.get { reads++; wasmtime }

        assertSame(chrome, first)
        assertSame(chrome, second)
        assertEquals(1, reads)
    }

    @Test
    fun `starts with no announced changes`() {
        assertEquals(0, RuntimeTargetsTracker().changes.value)
    }

    @Test
    fun `an external change to an already-read value is announced`() {
        val tracker = RuntimeTargetsTracker()
        tracker.get { chrome }

        tracker.observe(chromeNewer)

        assertEquals(1, tracker.changes.value)
        assertEquals(chromeNewer.targets, tracker.get { error("should be cached") }.targets)
    }

    @Test
    fun `each further external change is announced again`() {
        val tracker = RuntimeTargetsTracker()
        tracker.get { chrome }

        tracker.observe(chromeNewer)
        tracker.observe(wasmtime)

        assertEquals(2, tracker.changes.value)
    }

    /** A save with no edits, or a checkout of an identical file, must not disturb the UI. */
    @Test
    fun `an external rewrite with the same declaration is not announced`() {
        val tracker = RuntimeTargetsTracker()
        tracker.get { chrome }

        tracker.observe(RuntimeTargets(listOf(RuntimeTarget("Chrome", "120"))))

        assertEquals(0, tracker.changes.value)
    }

    /** Nothing has read the targets yet, so there is no UI to refresh. */
    @Test
    fun `an external change before the first read is not announced`() {
        val tracker = RuntimeTargetsTracker()

        tracker.observe(chrome)

        assertEquals(0, tracker.changes.value)
        assertEquals(chrome.targets, tracker.get { error("should be cached") }.targets)
    }

    @Test
    fun `deleting the file reads as nothing declared and is announced`() {
        val tracker = RuntimeTargetsTracker()
        tracker.get { chrome }

        tracker.observe(RuntimeTargets.EMPTY)

        assertEquals(1, tracker.changes.value)
        assertEquals(emptyList<RuntimeTarget>(), tracker.get { error("should be cached") }.targets)
    }

    /**
     * A save cannot wait for its own VFS event: the cache already matches disk by then, so
     * `observe` would see no change and applying the settings page would never refresh.
     */
    @Test
    fun `a save announces itself`() {
        val tracker = RuntimeTargetsTracker()
        tracker.get { RuntimeTargets.EMPTY }

        tracker.recordSave(chrome)

        assertEquals(1, tracker.changes.value)
        assertEquals(chrome.targets, tracker.get { error("should be cached") }.targets)
    }

    @Test
    fun `the save's own vfs event that follows is not announced twice`() {
        val tracker = RuntimeTargetsTracker()
        tracker.get { RuntimeTargets.EMPTY }
        tracker.recordSave(chrome)

        // What the VFS listener reports once the write lands.
        tracker.observe(RuntimeTargets(listOf(RuntimeTarget("Chrome", "120"))))

        assertEquals(1, tracker.changes.value)
    }

    @Test
    fun `saving an unchanged declaration is not announced`() {
        val tracker = RuntimeTargetsTracker()
        tracker.get { chrome }

        tracker.recordSave(RuntimeTargets(listOf(RuntimeTarget("Chrome", "120"))))

        assertEquals(0, tracker.changes.value)
    }

    // --- which VFS events are ours ---

    private val settings = "/home/me/project/.hexana/runtimes.json"

    @Test
    fun `the settings file's own path matches`() {
        assertEquals(true, runtimeTargetsEventMatches(settings, settings))
    }

    /** A checkout or a copied directory is reported on the directory, not the file inside. */
    @Test
    fun `an ancestor directory matches`() {
        assertEquals(true, runtimeTargetsEventMatches(settings, "/home/me/project/.hexana"))
        assertEquals(true, runtimeTargetsEventMatches(settings, "/home/me/project"))
    }

    @Test
    fun `an unrelated file with the same name does not match`() {
        assertEquals(false, runtimeTargetsEventMatches(settings, "/home/me/project/other/runtimes.json"))
        assertEquals(false, runtimeTargetsEventMatches(settings, "/home/me/runtimes.json"))
    }

    @Test
    fun `a sibling directory sharing a name prefix does not match`() {
        assertEquals(false, runtimeTargetsEventMatches(settings, "/home/me/project/.hexana-backup"))
        assertEquals(false, runtimeTargetsEventMatches(settings, "/home/me/project2"))
    }

    @Test
    fun `a file below the settings file does not match`() {
        assertEquals(false, runtimeTargetsEventMatches(settings, "$settings/nested"))
    }
}
