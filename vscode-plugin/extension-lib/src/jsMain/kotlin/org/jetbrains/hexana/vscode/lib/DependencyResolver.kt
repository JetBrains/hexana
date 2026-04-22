@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.componentExportNames
import org.jetbrains.hexana.shared.componentImportNames
import org.jetbrains.hexana.shared.isRuntimeImport
import org.jetbrains.hexana.shared.splitNameAndVersion

/**
 * A single resolved dependency: maps an import name to a file path.
 */
@JsExport
data class ResolvedDep(val importName: String, val filePath: String)

/**
 * Result of a [resolveAllDependencies] call.
 */
@JsExport
data class DependencyResolution(
    val resolved: Array<ResolvedDep>,
) {
    override fun equals(other: Any?): Boolean =
        other is DependencyResolution && resolved.contentEquals(other.resolved)

    override fun hashCode(): Int = resolved.contentHashCode()
}

/**
 * Callback interface that TypeScript implements to provide filesystem
 * access to the dependency resolver running inside Kotlin/JS.
 *
 * Each method mirrors the subset of Node.js `fs`/`path` APIs needed
 * for scanning directories and reading Wasm files.
 */
@JsExport
external interface FileSystemAccess {
    /** Returns the directory entries (names) under [dir], or an empty array if unreadable. */
    fun listDirectory(dir: String): Array<FileEntry>

    /** Reads a file as a byte array, or `null` if unreadable. */
    fun readFile(path: String): ByteArray?

    /** Returns `true` if [path] exists on the filesystem. */
    fun fileExists(path: String): Boolean

    /** Returns the parent directory of [path]. */
    fun parentDir(path: String): String

    /** Joins path segments. */
    fun joinPath(vararg parts: String): String

    /** Returns the filesystem root of [path] (e.g. `/` or `C:\`). */
    fun pathRoot(path: String): String
}

/**
 * A directory entry returned by [FileSystemAccess.listDirectory].
 */
@JsExport
external interface FileEntry {
    val name: String
    val isDirectory: Boolean
    val isFile: Boolean
}

private val WORKSPACE_MARKERS = listOf("Cargo.toml", "package.json", ".git")

/**
 * Walks up from [wasmFilePath] looking for a directory that contains
 * a workspace marker file (`Cargo.toml`, `package.json`, `.git`).
 */
@JsExport
fun findWorkspaceRoot(wasmFilePath: String, fs: FileSystemAccess): String? {
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
 * Resolves component dependencies (including transitive) for the
 * component at [wasmFilePath] given its [importNames].
 *
 * @param importNames the component import names that need resolution
 * @param scanRoots   directories to scan for `.wasm` files
 * @param wasmFilePath the path of the main component (excluded from matching)
 * @param fs filesystem access callbacks
 * @return resolved dependencies as an array of [ResolvedDep]
 */
@JsExport
fun resolveAllDependencies(
    importNames: Array<String>,
    scanRoots: Array<String>,
    wasmFilePath: String,
    fs: FileSystemAccess,
): DependencyResolution {
    val directDeps = resolveComponentDeps(importNames.toList(), scanRoots.toList(), wasmFilePath, fs)
    val allResolved = resolveTransitiveDeps(directDeps, scanRoots.toList(), wasmFilePath, fs)
    val result = allResolved.map { (importName, filePath) -> ResolvedDep(importName, filePath) }
    return DependencyResolution(result.toTypedArray())
}

// ---------------------------------------------------------------------------
// Internal resolution logic
// ---------------------------------------------------------------------------

private fun resolveComponentDeps(
    importNames: List<String>,
    scanRoots: List<String>,
    excludePath: String,
    fs: FileSystemAccess,
): Map<String, String> {
    val resolved = mutableMapOf<String, String>()
    // Map: baseName (without @version) → original import name
    val needed = mutableMapOf<String, String>()
    for (name in importNames) {
        needed[splitNameAndVersion(name).first] = name
    }
    val visited = mutableSetOf(excludePath)

    for (root in scanRoots) {
        if (resolved.size >= needed.size) break
        scanDirectory(root, needed, resolved, visited, fs)
    }

    return resolved
}

private fun resolveTransitiveDeps(
    directDeps: Map<String, String>,
    scanRoots: List<String>,
    excludePath: String,
    fs: FileSystemAccess,
): Map<String, String> {
    val allResolved = directDeps.toMutableMap()
    val visitedPaths = mutableSetOf(excludePath)
    val queue = ArrayDeque(directDeps.values.toSet().toList())

    while (queue.isNotEmpty()) {
        val depPath = queue.removeFirst()
        if (!visitedPaths.add(depPath)) continue

        val depBytes = fs.readFile(depPath) ?: continue
        if (!isComponentBinary(depBytes)) continue

        val depExports = componentExportNames(depBytes)
        if (depExports.isEmpty()) continue

        val depImports = componentImportNames(depBytes)
        val transImports = depImports.filter { it !in allResolved && !isRuntimeImport(it) }
        if (transImports.isEmpty()) continue

        val transResolved = resolveComponentDeps(transImports, scanRoots, excludePath, fs)
        for ((name, filePath) in transResolved) {
            if (name !in allResolved) {
                allResolved[name] = filePath
                queue.addLast(filePath)
            }
        }
    }

    return allResolved
}

private fun scanDirectory(
    dir: String,
    needed: Map<String, String>,
    resolved: MutableMap<String, String>,
    visited: MutableSet<String>,
    fs: FileSystemAccess,
) {
    if (!visited.add(dir)) return

    val entries = fs.listDirectory(dir)

    for (entry in entries) {
        if (resolved.size >= needed.size) return
        val fullPath = fs.joinPath(dir, entry.name)

        if (entry.isDirectory) {
            scanDirectory(fullPath, needed, resolved, visited, fs)
        } else if (entry.isFile && entry.name.endsWith(".wasm")) {
            matchComponentExports(fullPath, needed, resolved, fs)
        }
    }
}

private fun matchComponentExports(
    filePath: String,
    needed: Map<String, String>,
    resolved: MutableMap<String, String>,
    fs: FileSystemAccess,
) {
    val bytes = fs.readFile(filePath) ?: return
    if (!isComponentBinary(bytes)) return

    val exportNames = componentExportNames(bytes)
    for (exportName in exportNames) {
        val baseExportName = splitNameAndVersion(exportName).first
        val originalImport = needed[baseExportName] ?: continue
        if (originalImport !in resolved) {
            resolved[originalImport] = filePath
        }
    }
}
