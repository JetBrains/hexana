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
import org.jetbrains.hexana.nativebin.JVM_FILE_SYSTEM
import org.jetbrains.hexana.shared.WasmDependencyResolver as SharedResolver
import org.jetbrains.hexana.shared.splitNameAndVersion
import org.jetbrains.hexana.wit.processIndexEntries
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
                SharedResolver.resolveTransitiveDependencies(resolved, buildScanRoots(project.basePath, wasmFilePath), wasmFilePath, JVM_FILE_SYSTEM)
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

        val unresolved = names.filter { it !in resolved && !org.jetbrains.hexana.shared.isRuntimeImport(it) }
        if (unresolved.isEmpty()) return resolved

        val scanRoots = buildScanRoots(project.basePath, wasmFilePath)
        if (scanRoots.isEmpty()) return resolved

        val fsResolved = SharedResolver.resolveViaFilesystem(unresolved, scanRoots, wasmFilePath, JVM_FILE_SYSTEM)
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
                val (lookupName, _) = splitNameAndVersion(name)
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
     * Determines the directories to scan for dependency wasm files.
     * Uses the project base path and walks up from the main wasm file
     * to find a likely workspace root (directory containing `Cargo.toml`,
     * `package.json`, or `.git`).
     */
    internal fun buildScanRoots(projectBasePath: String?, wasmFilePath: String?): List<String> {
        return SharedResolver.buildScanRoots(projectBasePath, wasmFilePath, JVM_FILE_SYSTEM)
    }

    internal fun findWorkspaceRoot(wasmFile: File): File? {
        val result = SharedResolver.findWorkspaceRoot(wasmFile.absolutePath, JVM_FILE_SYSTEM)
        return result?.let { File(it) }
    }

    internal fun isRuntimeImport(name: String): Boolean =
        org.jetbrains.hexana.shared.isRuntimeImport(name)
}
