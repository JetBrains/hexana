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
 */
internal object WasmDependencyResolver {

    /**
     * Resolves dependencies for a core wasm module by looking up each distinct
     * import module name (`prefix`) in the [WasmIndexId].
     *
     * @return a map of import module name → absolute file path for every
     *         import whose provider was found in the project index.
     */
    fun resolveCoreDependencies(wasmBytes: ByteArray, project: Project, excludeFile: VirtualFile? = null): Map<String, String> {
        val provider = WasmBinaryProvider(wasmBytes.toCommonByteBuffer())
        if (!provider.canProvideWasmModule()) return emptyMap()

        val importModuleNames = provider.imports.map { it.prefix }.distinct()
        return resolveImportNames(importModuleNames, project, excludeFile)
    }

    /**
     * Resolves dependencies for a wasm component by looking up each distinct
     * import name in the [WasmIndexId].
     *
     * @return a map of import name → absolute file path for every
     *         import whose provider was found in the project index.
     */
    fun resolveComponentDependencies(wasmBytes: ByteArray, project: Project, excludeFile: VirtualFile? = null): Map<String, String> {
        val provider = WasmComponentBinaryProvider(wasmBytes.toCommonByteBuffer())
        if (!provider.canProvideWasmModule()) return emptyMap()
        val importNames = provider.imports.map { it.importName }.distinct()
        return resolveImportNames(importNames, project, excludeFile)
    }

    /**
     * Resolves dependencies for a wasm file, auto-detecting whether it is
     * a core module or component.
     */
    fun resolveDependencies(wasmBytes: ByteArray, project: Project, excludeFile: VirtualFile? = null): Map<String, String> {
        return when (WasmBinaryProviderSelector.select(wasmBytes.toCommonByteBuffer())) {
            BinaryProviderType.WasmComponent -> {
                val provider = WasmComponentBinaryProvider(wasmBytes.toCommonByteBuffer())
                if (!provider.canProvideWasmModule()) return emptyMap()
                val importNames = provider.imports.map { it.importName }.distinct()
                resolveImportNames(importNames, project, excludeFile)
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
