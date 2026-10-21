package org.jetbrains.hexana.shared

/**
 * Platform-independent filesystem abstraction used by [WasmDependencyResolver]
 * to scan directories and read files.
 *
 * Each platform provides an adapter:
 * - JVM (IntelliJ): wraps `java.io.File`
 * - JS (VS Code extension): wraps Node.js `fs`/`path`
 */
interface FileSystem {
    /** Lists entries in [dir]. Returns an empty list when the directory is unreadable. */
    fun listEntries(dir: String): List<FileEntry>

    /** Reads a file as a byte array, or `null` if unreadable. */
    fun readFile(path: String): ByteArray?

    /** Returns `true` if [path] exists on the filesystem. */
    fun fileExists(path: String): Boolean

    /** Returns the parent directory of [path]. */
    fun parentDir(path: String): String

    /** Joins path segments. */
    fun joinPath(base: String, child: String): String

    /** Returns the filesystem root of [path] (e.g. `/` or `C:\`). */
    fun pathRoot(path: String): String

    /**
     * Size of [path] when it is a regular file (after following links), or null for a directory,
     * a device, a missing file, or a file system that cannot tell. Callers that read whole files
     * of untrusted name check this first.
     */
    fun regularFileSize(path: String): Long? = null
}

/** A directory entry returned by [FileSystem.listEntries]. */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val isFile: Boolean,
)

private val WORKSPACE_MARKERS = listOf("Cargo.toml", "package.json", ".git")

/**
 * Directory names never descended into during dependency scanning: build
 * outputs and VCS/tooling metadata that hold no publishable `.wasm`
 * dependencies but can contain tens of thousands of files. Without this,
 * a scan rooted next to a Rust `target/` or a `node_modules/` walks the
 * whole tree on a background thread and can stall the IDE behind a
 * contended read lock (observed as an EDT freeze in the Run toolbar's
 * action-update). Matched case-sensitively against the entry name.
 */
private val SCAN_SKIP_DIRS = setOf(
    "target", "node_modules", ".git", ".gradle", ".idea", "build", "dist", "out", ".cache", ".svn", ".hg",
)

/** Hard bounds on a single [WasmDependencyResolver.resolveViaFilesystem] scan. */
private const val MAX_SCAN_DEPTH = 8
private const val MAX_SCAN_ENTRIES = 20_000

/**
 * Resolves Wasm component dependencies by scanning the filesystem for
 * `.wasm` files whose component exports match the requested import names.
 *
 * This is the platform-independent core shared by the IntelliJ plugin
 * (which adds an IDE-index pre-pass) and the VS Code extension.
 */
object WasmDependencyResolver {

    /**
     * Walks up from [wasmFilePath] looking for a directory that contains
     * a workspace marker file (`Cargo.toml`, `package.json`, `.git`).
     */
    fun findWorkspaceRoot(wasmFilePath: String, fs: FileSystem): String? {
        var dir = fs.parentDir(wasmFilePath)
        val root = fs.pathRoot(dir)
        while (dir.isNotEmpty() && dir != root) {
            for (marker in WORKSPACE_MARKERS) {
                if (fs.fileExists(fs.joinPath(dir, marker))) {
                    return dir
                }
            }
            dir = fs.parentDir(dir)
        }
        return null
    }

    /**
     * Determines the directories to scan for dependency Wasm files.
     * Uses the optional [projectBasePath] and walks up from [wasmFilePath]
     * to find a likely workspace root.
     */
    fun buildScanRoots(
        projectBasePath: String?,
        wasmFilePath: String?,
        fs: FileSystem,
    ): List<String> {
        val roots = LinkedHashSet<String>()

        if (projectBasePath != null && fs.listEntries(projectBasePath).isNotEmpty()) {
            roots.add(projectBasePath)
        }

        if (wasmFilePath != null) {
            val workspaceRoot = findWorkspaceRoot(wasmFilePath, fs)
            if (workspaceRoot != null) {
                roots.add(workspaceRoot)
            }
        }

        return roots.toList()
    }

    /**
     * Resolves component dependencies via filesystem scan, including
     * transitive dependencies.
     *
     * @param importNames the import names that need resolution
     * @param scanRoots   directories to scan for `.wasm` files
     * @param excludePath the path of the main component (excluded from matching)
     * @param fs filesystem access
     * @return map of import name → resolved file path
     */
    fun resolveViaFilesystem(
        importNames: List<String>,
        scanRoots: List<String>,
        excludePath: String?,
        fs: FileSystem,
    ): Map<String, String> {
        val needed = importNames.associateBy { splitNameAndVersion(it).first }
        val resolved = mutableMapOf<String, String>()
        val visited = mutableSetOf<String>()
        excludePath?.let { visited.add(it) }

        val budget = ScanBudget()
        for (root in scanRoots) {
            if (resolved.size == needed.size || budget.exhausted) break
            scanDirectory(root, needed, resolved, visited, fs, depth = 0, budget = budget)
        }
        return resolved
    }

    /** Mutable entry counter shared across a scan's roots; caps total work. */
    private class ScanBudget(var remaining: Int = MAX_SCAN_ENTRIES) {
        val exhausted: Boolean get() = remaining <= 0
    }

    /**
     * Transitively resolves component dependencies. For each resolved file
     * that is itself a component, its imports are resolved too. Already
     * visited files are skipped to avoid cycles.
     */
    fun resolveTransitiveDependencies(
        directDeps: Map<String, String>,
        scanRoots: List<String>,
        excludePath: String?,
        fs: FileSystem,
    ): Map<String, String> {
        val allResolved = LinkedHashMap(directDeps)
        val visitedPaths = mutableSetOf<String>()
        excludePath?.let { visitedPaths.add(it) }

        val queue = ArrayDeque(directDeps.values.distinct())

        while (queue.isNotEmpty()) {
            val depPath = queue.removeFirst()
            if (!visitedPaths.add(depPath)) continue

            val depBytes = fs.readFile(depPath) ?: continue
            if (detectBinaryType(depBytes) != org.jetbrains.hexana.providers.BinaryProviderType.WasmComponent) continue

            val depExports = componentExportNames(depBytes)
            if (depExports.isEmpty()) continue

            val depImports = componentImportNames(depBytes)
            val transImports = depImports.filter { it !in allResolved && !isRuntimeImport(it) }
            if (transImports.isEmpty()) continue

            val transResolved = resolveViaFilesystem(transImports, scanRoots, excludePath, fs)
            for ((name, filePath) in transResolved) {
                if (name !in allResolved) {
                    allResolved[name] = filePath
                    queue.add(filePath)
                }
            }
        }

        return allResolved
    }

    /**
     * Convenience method that resolves direct and transitive dependencies
     * in one call.
     */
    fun resolveAllDependencies(
        importNames: List<String>,
        scanRoots: List<String>,
        excludePath: String?,
        fs: FileSystem,
    ): Map<String, String> {
        val directDeps = resolveViaFilesystem(importNames, scanRoots, excludePath, fs)
        return resolveTransitiveDependencies(directDeps, scanRoots, excludePath, fs)
    }

    private fun scanDirectory(
        dir: String,
        needed: Map<String, String>,
        resolved: MutableMap<String, String>,
        visited: MutableSet<String>,
        fs: FileSystem,
        depth: Int,
        budget: ScanBudget,
    ) {
        if (depth > MAX_SCAN_DEPTH || budget.exhausted) return
        if (!visited.add(dir)) return

        val entries = fs.listEntries(dir)

        for (entry in entries) {
            if (resolved.size >= needed.size || budget.exhausted) return
            budget.remaining--
            val fullPath = fs.joinPath(dir, entry.name)

            if (entry.isDirectory) {
                // Never descend into build-output / VCS trees: they hold no
                // publishable dependencies but can be enormous (see SCAN_SKIP_DIRS).
                if (entry.name in SCAN_SKIP_DIRS) continue
                scanDirectory(fullPath, needed, resolved, visited, fs, depth + 1, budget)
            } else if (entry.isFile && entry.name.endsWith(".wasm")) {
                matchComponentExports(fullPath, needed, resolved, fs)
            }
        }
    }

    private fun matchComponentExports(
        filePath: String,
        needed: Map<String, String>,
        resolved: MutableMap<String, String>,
        fs: FileSystem,
    ) {
        val bytes = fs.readFile(filePath) ?: return
        if (detectBinaryType(bytes) != org.jetbrains.hexana.providers.BinaryProviderType.WasmComponent) return

        val exportNames = componentExportNames(bytes)
        for (exportName in exportNames) {
            val baseExportName = splitNameAndVersion(exportName).first
            val originalImport = needed[baseExportName] ?: continue
            if (originalImport !in resolved) {
                resolved[originalImport] = filePath
            }
        }
    }
}
