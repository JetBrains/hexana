package org.jetbrains.hexana.run

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.hexana.WasmIndexId
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wit.processIndexEntries
import org.jetbrains.hexana.wit.psi.WitResolver
import java.io.File

/**
 * Resolves wasm module import names to project files via [WasmIndexId],
 * falling back to a filesystem scan when the IDE index does not cover the
 * dependency files.
 *
 * For each distinct import name, the resolver first queries the file-based
 * index for a wasm component that exports that name. When the index lookup
 * does not find a match, it scans the project directory and the parent
 * directories of the main wasm file for `.wasm` components whose exports
 * satisfy the import.
 *
 * Component dependencies are resolved transitively: when a resolved
 * dependency is itself a component with imports, those imports are also
 * resolved so that composition tools receive the full dependency graph.
 */
internal object WasmDependencyResolver {

    private val LOG = Logger.getInstance(WasmDependencyResolver::class.java)

    /** Well-known WASI namespace prefixes that are provided by the runtime. */
    private val RUNTIME_PREFIXES = listOf("wasi:")

    /**
     * Resolves dependencies for a wasm file, auto-detecting whether it is
     * a core module or component. Component dependencies are resolved
     * transitively so that the full dependency graph is available for
     * composition.
     */
    fun resolveDependencies(wasmBytes: ByteArray, project: Project, excludeFile: VirtualFile? = null): Map<String, String> {
        val wasmFilePath = excludeFile?.path
        return when (WasmBinaryProviderSelector.select(wasmBytes.toCommonByteBuffer())) {
            BinaryProviderType.WasmComponent -> {
                val provider = WasmComponentBinaryProvider(wasmBytes.toCommonByteBuffer())
                if (!provider.canProvideWasmModule()) return emptyMap()
                val importNames = provider.imports.map { it.importName }.distinct()
                val resolved = resolveImportNames(importNames, project, excludeFile, wasmFilePath)
                resolveTransitiveDependencies(resolved, project, excludeFile, wasmFilePath)
            }
            BinaryProviderType.Wasm -> {
                val provider = WasmBinaryProvider(wasmBytes.toCommonByteBuffer())
                if (!provider.canProvideWasmModule()) return emptyMap()
                val importModuleNames = provider.imports.map { it.prefix }.distinct()
                resolveImportNames(importModuleNames, project, excludeFile, wasmFilePath)
            }
            else -> emptyMap()
        }
    }

    /**
     * Transitively resolves component dependencies. For each resolved file
     * that is itself a component, its imports are resolved too. Already
     * visited files are skipped to avoid cycles.
     */
    private fun resolveTransitiveDependencies(
        directDeps: Map<String, String>,
        project: Project,
        excludeFile: VirtualFile?,
        wasmFilePath: String?,
    ): Map<String, String> {
        val allResolved = LinkedHashMap(directDeps)
        val visitedPaths = mutableSetOf<String>()
        excludeFile?.path?.let { visitedPaths.add(it) }

        val queue = ArrayDeque(directDeps.values.distinct())

        while (queue.isNotEmpty()) {
            val depPath = queue.removeFirst()
            if (!visitedPaths.add(depPath)) continue

            val depFile = File(depPath)
            if (!depFile.exists()) continue

            val depBytes = depFile.readBytes()
            if (WasmBinaryProviderSelector.select(depBytes.toCommonByteBuffer()) != BinaryProviderType.WasmComponent) continue

            val depProvider = WasmComponentBinaryProvider(depBytes.toCommonByteBuffer())
            if (!depProvider.canProvideWasmModule()) continue

            val transImportNames = depProvider.imports.map { it.importName }.distinct()
                .filter { it !in allResolved }
            if (transImportNames.isEmpty()) continue

            val transResolved = resolveImportNames(transImportNames, project, excludeFile, wasmFilePath)
            for ((name, path) in transResolved) {
                if (name !in allResolved) {
                    allResolved[name] = path
                    queue.add(path)
                }
            }
        }

        return allResolved
    }

    /**
     * Resolves import names first via the IDE file index, then falls back
     * to a filesystem scan for any non-runtime imports that remain unresolved.
     */
    private fun resolveImportNames(
        names: List<String>,
        project: Project,
        excludeFile: VirtualFile?,
        wasmFilePath: String?,
    ): Map<String, String> {
        val resolved = resolveViaIndex(names, project, excludeFile)

        val unresolved = names.filter { it !in resolved && !isRuntimeImport(it) }
        if (unresolved.isEmpty()) return resolved

        val scanRoots = buildScanRoots(project.basePath, wasmFilePath)
        if (scanRoots.isEmpty()) return resolved

        val fsResolved = resolveViaFilesystem(unresolved, scanRoots, wasmFilePath)
        return resolved + fsResolved
    }

    private fun resolveViaIndex(
        names: List<String>,
        project: Project,
        excludeFile: VirtualFile?,
    ): Map<String, String> {
        return ReadAction.compute<Map<String, String>, RuntimeException> {
            val resolved = mutableMapOf<String, String>()
            val scope = GlobalSearchScope.everythingScope(project)

            for (name in names) {
                val (lookupName, _) = WitResolver.splitNameAndVersion(name)
                var foundFile: VirtualFile? = null

                processIndexEntries(WasmIndexId, lookupName, scope) { file, _ ->
                    if (file != excludeFile) {
                        foundFile = file
                        false
                    } else {
                        true
                    }
                }

                foundFile?.let { resolved[name] = it.path }
            }

            resolved
        }
    }

    /**
     * Scans `.wasm` files under [roots] and matches their component exports
     * against [unresolvedNames].
     */
    private fun resolveViaFilesystem(
        unresolvedNames: List<String>,
        roots: List<File>,
        excludePath: String?,
    ): Map<String, String> {
        val needed = unresolvedNames.associateBy { WitResolver.splitNameAndVersion(it).first }
        val resolved = mutableMapOf<String, String>()
        val visited = mutableSetOf<String>()
        excludePath?.let { visited.add(it) }

        for (root in roots) {
            if (resolved.size == unresolvedNames.size) break
            scanDirectory(root, needed, resolved, visited)
        }
        return resolved
    }

    private fun scanDirectory(
        dir: File,
        needed: Map<String, String>,
        resolved: MutableMap<String, String>,
        visited: MutableSet<String>,
    ) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (resolved.size == needed.size) return
            if (file.isDirectory) {
                scanDirectory(file, needed, resolved, visited)
            } else if (file.extension == "wasm" && visited.add(file.absolutePath)) {
                matchComponentExports(file, needed, resolved)
            }
        }
    }

    private fun matchComponentExports(
        file: File,
        needed: Map<String, String>,
        resolved: MutableMap<String, String>,
    ) {
        try {
            val bytes = file.readBytes()
            if (WasmBinaryProviderSelector.select(bytes.toCommonByteBuffer()) != BinaryProviderType.WasmComponent) return
            val provider = WasmComponentBinaryProvider(bytes.toCommonByteBuffer())
            if (!provider.canProvideWasmModule()) return

            for (export in provider.exports) {
                val (exportName, _) = WitResolver.splitNameAndVersion(export.exportName)
                val originalImport = needed[exportName]
                if (originalImport != null && originalImport !in resolved) {
                    LOG.info("Filesystem fallback: resolved '$originalImport' → ${file.absolutePath}")
                    resolved[originalImport] = file.absolutePath
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to parse ${file.absolutePath}: ${e.message}")
        }
    }

    /**
     * Determines the directories to scan for dependency wasm files.
     * Uses the project base path and walks up from the main wasm file
     * to find a likely workspace root (directory containing `Cargo.toml`,
     * `package.json`, or `.git`).
     */
    internal fun buildScanRoots(projectBasePath: String?, wasmFilePath: String?): List<File> {
        val roots = LinkedHashSet<File>()

        if (projectBasePath != null) {
            val projectDir = File(projectBasePath)
            if (projectDir.isDirectory) roots.add(projectDir)
        }

        if (wasmFilePath != null) {
            val wasmFile = File(wasmFilePath)
            val workspaceRoot = findWorkspaceRoot(wasmFile)
            if (workspaceRoot != null && workspaceRoot.isDirectory) {
                roots.add(workspaceRoot)
            }
        }

        return roots.toList()
    }

    private val WORKSPACE_MARKERS = setOf("Cargo.toml", "package.json", ".git")

    /**
     * Walks up from a wasm file to find the nearest directory that looks
     * like a workspace root (contains `Cargo.toml`, `package.json`, or `.git`).
     */
    internal fun findWorkspaceRoot(wasmFile: File): File? {
        var dir = wasmFile.parentFile
        while (dir != null) {
            if (WORKSPACE_MARKERS.any { File(dir, it).exists() }) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }

    internal fun isRuntimeImport(name: String): Boolean =
        RUNTIME_PREFIXES.any { name.startsWith(it) }
}
