package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounds on `resolveViaFilesystem`'s directory walk: it must not descend into
 * build-output trees, must stop at a depth cap, and must honor a total-entry
 * budget. Without these it walked entire Rust `target/` trees on a background
 * thread and froze the IDE behind a contended read lock.
 */
class WasmDependencyResolverScanTest {

    /** In-memory filesystem; records every directory listed so we can assert what was walked. */
    private class FakeFs(private val tree: Map<String, List<FileEntry>>) : FileSystem {
        val listed = mutableListOf<String>()
        override fun listEntries(dir: String): List<FileEntry> {
            listed.add(dir)
            return tree[dir] ?: emptyList()
        }
        override fun readFile(path: String): ByteArray? = null
        override fun fileExists(path: String) = tree.containsKey(path)
        override fun parentDir(path: String) = path.substringBeforeLast('/', "")
        override fun joinPath(base: String, child: String) = "$base/$child"
        override fun pathRoot(path: String) = "/"
    }

    private fun dir(vararg names: String) = names.map { FileEntry(it, isDirectory = true, isFile = false) }

    @Test
    fun `skip dirs are never listed`() {
        val fs = FakeFs(
            mapOf(
                "/root" to (dir("target", "node_modules", ".git", "src")),
                "/root/src" to emptyList(),
                "/root/target" to dir("huge"),
            ),
        )
        // One unresolved import so the scan does not early-exit.
        WasmDependencyResolver.resolveViaFilesystem(listOf("dep:1.0"), listOf("/root"), null, fs)

        assertTrue("/root/src" in fs.listed, "normal dirs are walked")
        assertTrue(fs.listed.none { it.contains("target") }, "target/ must be skipped: ${fs.listed}")
        assertTrue(fs.listed.none { it.contains("node_modules") }, "node_modules/ must be skipped")
        assertTrue(fs.listed.none { it.contains(".git") }, ".git/ must be skipped")
    }

    @Test
    fun `depth cap halts a deep chain`() {
        // A linear chain /d/d/d/... deeper than the cap; each level nests one dir.
        val tree = HashMap<String, List<FileEntry>>()
        var path = "/deep"
        repeat(40) {
            tree[path] = dir("d")
            path = "$path/d"
        }
        val fs = FakeFs(tree)
        WasmDependencyResolver.resolveViaFilesystem(listOf("dep:1.0"), listOf("/deep"), null, fs)

        // Root at depth 0 plus MAX_SCAN_DEPTH (8) levels = 9 dirs listed, not 40.
        assertEquals(9, fs.listed.size, "walk must stop at the depth cap, listed: ${fs.listed.size}")
    }

    @Test
    fun `entry budget bounds a wide fan-out`() {
        // A root with far more direct subdirectories than the entry budget.
        val subdirs = (0 until 50_000).map { FileEntry("s$it", isDirectory = true, isFile = false) }
        val tree = HashMap<String, List<FileEntry>>()
        tree["/wide"] = subdirs
        subdirs.forEach { tree["/wide/${it.name}"] = emptyList() }
        val fs = FakeFs(tree)
        WasmDependencyResolver.resolveViaFilesystem(listOf("dep:1.0"), listOf("/wide"), null, fs)

        // Root + at most MAX_SCAN_ENTRIES child listings, nowhere near 50k.
        assertTrue(fs.listed.size < 20_100, "entry budget must bound the walk, listed ${fs.listed.size}")
    }
}
