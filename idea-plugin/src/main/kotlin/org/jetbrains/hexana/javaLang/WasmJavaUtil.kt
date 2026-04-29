package org.jetbrains.hexana.javaLang

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.hexana.javascript.CoreWasmData
import org.jetbrains.hexana.javascript.WasmBinaryDataCacheService
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

/**
 * Resolves all `.wasm` [VirtualFile]s referenced from a Java source file.
 *
 * Uses [JavaWasmReferenceIndex] for fast filename discovery, then resolves
 * each filename to a [VirtualFile] via sibling directory, module source roots,
 * or project-wide filename search.
 */
internal fun findAllReferencedWasmFiles(psiFile: PsiFile): List<VirtualFile> {
    val javaFile = psiFile as? PsiJavaFile ?: return emptyList()
    val containingVirtualFile = javaFile.virtualFile ?: return emptyList()
    val project = javaFile.project

    val wasmFileNames = getIndexedWasmFileNames(containingVirtualFile, project)
    if (wasmFileNames.isEmpty()) return emptyList()

    val packagePath = javaFile.packageName.takeIf { it.isNotEmpty() }?.replace('.', '/')
    return wasmFileNames.mapNotNull { resolveWasmFile(it, containingVirtualFile, project, packagePath) }
}

/**
 * Resolves the first `.wasm` [VirtualFile] referenced from a Java source file.
 *
 * Uses [JavaWasmReferenceIndex] for fast lookup.
 */
internal fun findReferencedWasmFile(psiFile: PsiFile): VirtualFile? {
    return findAllReferencedWasmFiles(psiFile).firstOrNull()
}

/**
 * Resolves a WASM filename to a [VirtualFile] by searching, in order:
 * 1. Sibling directory of the containing file
 * 2. `<sourceRoot>/<packagePath>/<filename>` for each source root (mirrors `Class.getResource` lookup)
 * 3. `<sourceRoot>/<filename>` for each source root (root-relative paths)
 * 4. Compiler output directories `<output>/<packagePath>/<filename>` and `<output>/<filename>`
 *    — needed for Maven/Gradle projects that generate WASM into `target/classes` or `build/classes`,
 *    which are typically marked as excluded and not covered by [GlobalSearchScope.projectScope]
 * 5. Project-wide filename search (fallback)
 */
private fun resolveWasmFile(
    wasmFileName: String,
    containingFile: VirtualFile,
    project: Project,
    packagePath: String?
): VirtualFile? {
    containingFile.parent?.findChild(wasmFileName)?.let { return it }

    val fileIndex = ProjectFileIndex.getInstance(project)
    val module = fileIndex.getModuleForFile(containingFile)
    if (module != null) {
        val sourceRoots = ModuleRootManager.getInstance(module).sourceRoots
        if (packagePath != null) {
            for (sourceRoot in sourceRoots) {
                sourceRoot.findFileByRelativePath("$packagePath/$wasmFileName")?.let { return it }
            }
        }
        for (sourceRoot in sourceRoots) {
            sourceRoot.findFileByRelativePath(wasmFileName)?.let { return it }
        }

        val outputRoots = CompilerModuleExtension.getInstance(module)?.let { ext ->
            listOfNotNull(ext.compilerOutputPath, ext.compilerOutputPathForTests)
        }.orEmpty()
        if (packagePath != null) {
            for (output in outputRoots) {
                output.findFileByRelativePath("$packagePath/$wasmFileName")?.let { return it }
            }
        }
        for (output in outputRoots) {
            output.findFileByRelativePath(wasmFileName)?.let { return it }
        }
    }

    val shortName = wasmFileName.substringAfterLast('/')
    val scope = GlobalSearchScope.projectScope(project)
    com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(shortName, scope).firstOrNull()?.let { return it }

    return null
}

/**
 * Checks whether the given Java file imports any class from the specified package prefix.
 * This is a lightweight detection that works even when the library is not on the project classpath
 * (e.g. in test fixtures where only the source file is provided).
 */
internal fun fileImportsPackage(psiFile: PsiFile, packagePrefix: String): Boolean {
    val javaFile = psiFile as? PsiJavaFile ?: return false
    return javaFile.importList?.allImportStatements?.any { stmt ->
        stmt.text.contains(packagePrefix)
    } == true
}

/**
 * Returns parsed [CoreWasmData] for the `.wasm` file referenced from the given Java file,
 * or `null` if no WASM reference is found or parsing fails.
 */
internal fun getWasmDataForFile(psiFile: PsiFile): CoreWasmData? {
    val wasmFile = findReferencedWasmFile(psiFile) ?: return null
    return WasmBinaryDataCacheService.getInstance().getOrParseBinary(wasmFile) as? CoreWasmData
}

/**
 * Resolves the function type for a given export name.
 * Returns the [WasmSectionFunctionTypeRecord] if the export is a function, null otherwise.
 */
internal fun resolveFunctionType(
    data: CoreWasmData,
    exportName: String
): WasmSectionFunctionTypeRecord? {
    val export = data.exports.filterIsInstance<WasmFunctionExportRecordEntry>()
        .find { it.name == exportName } ?: return null
    val funcIndex = export.index.toInt()
    val localIndex = funcIndex - data.importedFunctions
    val typeIndex = if (localIndex >= 0 && localIndex < data.funcTypeIndices.size) {
        data.funcTypeIndices[localIndex]
    } else {
        // It's an imported function
        val importRecord = data.imports.getOrNull(funcIndex) ?: return null
        (importRecord.type as? WasmTypeIndexExternalType)?.index?.toInt() ?: return null
    }
    return data.typeRecords.getOrNull(typeIndex) as? WasmSectionFunctionTypeRecord
}

/**
 * Extracts the string literal value from the first argument of a method call.
 * Returns null if the first argument is not a string literal.
 */
internal fun getFirstStringArg(call: PsiMethodCallExpression): String? {
    val args = call.argumentList.expressions
    if (args.isEmpty()) return null
    return (args[0] as? PsiLiteralExpression)?.value as? String
}

/**
 * Checks whether a GraalWasm method call (`invokeMember`/`getMember`) is invoked on
 * a variable that was assigned from `.getMember("exports")` — the standard GraalVM
 * pattern for obtaining the WASM exports object.
 *
 * ```java
 * Value exports = instance.getMember("exports");
 * exports.invokeMember("func_name");  // → true
 * instance.invokeMember("func_name"); // → false
 * ```
 */
internal fun isCalledOnExportsObject(expression: PsiMethodCallExpression): Boolean {
    val qualifier = expression.methodExpression.qualifierExpression as? PsiReferenceExpression
        ?: return false
    val resolved = qualifier.resolve() as? PsiLocalVariable ?: return false
    val initializer = resolved.initializer as? PsiMethodCallExpression ?: return false
    val initMethodName = initializer.methodExpression.referenceName ?: return false
    if (initMethodName != "getMember") return false
    val initArgs = initializer.argumentList.expressions
    if (initArgs.isEmpty()) return false
    val firstArg = (initArgs[0] as? PsiLiteralExpression)?.value as? String
    return firstArg == "exports"
}
