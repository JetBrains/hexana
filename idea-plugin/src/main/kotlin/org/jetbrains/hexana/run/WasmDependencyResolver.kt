package org.jetbrains.hexana.run

import com.intellij.openapi.application.ReadAction
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

/**
 * Resolves wasm module import names to project files via [WasmIndexId].
 *
 * For each distinct import name, the resolver queries the file-based index
 * for a wasm component that exports that name. When a unique match is found,
 * the corresponding file path is returned so it can be supplied as a
 * dependency (e.g. via wasmtime `--preload`).
 *
 * Component dependencies are resolved transitively: when a resolved
 * dependency is itself a component with imports, those imports are also
 * resolved so that composition tools receive the full dependency graph.
 */
internal object WasmDependencyResolver {

    /**
     * Resolves dependencies for a wasm file, auto-detecting whether it is
     * a core module or component. Component dependencies are resolved
     * transitively so that the full dependency graph is available for
     * composition.
     */
    fun resolveDependencies(wasmBytes: ByteArray, project: Project, excludeFile: VirtualFile? = null): Map<String, String> {
        return when (WasmBinaryProviderSelector.select(wasmBytes.toCommonByteBuffer())) {
            BinaryProviderType.WasmComponent -> {
                val provider = WasmComponentBinaryProvider(wasmBytes.toCommonByteBuffer())
                if (!provider.canProvideWasmModule()) return emptyMap()
                val importNames = provider.imports.map { it.importName }.distinct()
                val resolved = resolveImportNames(importNames, project, excludeFile)
                resolveTransitiveDependencies(resolved, project, excludeFile)
            }
            BinaryProviderType.Wasm -> {
                val provider = WasmBinaryProvider(wasmBytes.toCommonByteBuffer())
                if (!provider.canProvideWasmModule()) return emptyMap()
                val importModuleNames = provider.imports.map { it.prefix }.distinct()
                resolveImportNames(importModuleNames, project, excludeFile)
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
    ): Map<String, String> {
        val allResolved = LinkedHashMap(directDeps)
        val visitedPaths = mutableSetOf<String>()
        excludeFile?.path?.let { visitedPaths.add(it) }

        val queue = ArrayDeque(directDeps.values.distinct())

        while (queue.isNotEmpty()) {
            val depPath = queue.removeFirst()
            if (!visitedPaths.add(depPath)) continue

            val depFile = java.io.File(depPath)
            if (!depFile.exists()) continue

            val depBytes = depFile.readBytes()
            if (WasmBinaryProviderSelector.select(depBytes.toCommonByteBuffer()) != BinaryProviderType.WasmComponent) continue

            val depProvider = WasmComponentBinaryProvider(depBytes.toCommonByteBuffer())
            if (!depProvider.canProvideWasmModule()) continue

            val transImportNames = depProvider.imports.map { it.importName }.distinct()
                .filter { it !in allResolved }
            if (transImportNames.isEmpty()) continue

            val transResolved = resolveImportNames(transImportNames, project, excludeFile)
            for ((name, path) in transResolved) {
                if (name !in allResolved) {
                    allResolved[name] = path
                    queue.add(path)
                }
            }
        }

        return allResolved
    }

    private fun resolveImportNames(
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
                        false // stop after first match
                    } else {
                        true // continue searching
                    }
                }

                foundFile?.let { resolved[name] = it.path }
            }

            resolved
        }
    }
}
