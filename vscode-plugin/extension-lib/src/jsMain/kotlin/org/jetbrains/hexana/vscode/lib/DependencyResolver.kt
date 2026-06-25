@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.WasmDependencyResolver
import org.jetbrains.hexana.shared.FileEntry
import org.jetbrains.hexana.shared.FileSystem

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

/**
 * Adapts a JS [FileSystemAccess] to the shared [FileSystem] interface.
 */
private fun FileSystemAccess.toFileSystem(): FileSystem = object : FileSystem {
    override fun listEntries(dir: String): List<FileEntry> =
        listDirectory(dir).map { FileEntry(it.name, it.isDirectory, it.isFile) }

    override fun readFile(path: String): ByteArray? = this@toFileSystem.readFile(path)

    override fun fileExists(path: String): Boolean = this@toFileSystem.fileExists(path)

    override fun parentDir(path: String): String = this@toFileSystem.parentDir(path)

    override fun joinPath(base: String, child: String): String = this@toFileSystem.joinPath(base, child)

    override fun pathRoot(path: String): String = this@toFileSystem.pathRoot(path)
}

/**
 * Walks up from [wasmFilePath] looking for a directory that contains
 * a workspace marker file (`Cargo.toml`, `package.json`, `.git`).
 */
@JsExport
fun findWorkspaceRoot(wasmFilePath: String, fs: FileSystemAccess): String? =
    WasmDependencyResolver.findWorkspaceRoot(wasmFilePath, fs.toFileSystem())

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
    val allResolved = WasmDependencyResolver.resolveAllDependencies(
        importNames.toList(), scanRoots.toList(), wasmFilePath, fs.toFileSystem(),
    )
    val result = allResolved.map { (importName, filePath) -> ResolvedDep(importName, filePath) }
    return DependencyResolution(result.toTypedArray())
}
