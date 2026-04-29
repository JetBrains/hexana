package org.jetbrains.hexana.javaLang

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
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
 * Resolves the `.wasm` [VirtualFile] backing a specific Chicory `instance.export(...)`
 * call, by walking the local data flow:
 * `instance` → its initializer `Instance.builder(module).build()` → `module` → the
 * `Parser.parse(...)` call (or any helper that wraps it) → the resource-name string
 * literal ending in `.wasm`.
 *
 * Returns `null` if the trace breaks at any point — callers should fall back to the
 * file-level [findReferencedWasmFile] for legacy single-module-per-file behaviour.
 *
 * Why this exists: when a single Java file uses **multiple** WASM modules (e.g. one
 * per method, each with its own `Parser.parse(...)`), file-level resolution
 * arbitrarily picks the first indexed filename and applies it to every call site.
 * That produces wrong export-name completions and false-positive inspections.
 */
internal fun findScopedWasmFile(
    exportCall: PsiMethodCallExpression,
    psiFile: PsiFile
): VirtualFile? {
    val javaFile = psiFile as? PsiJavaFile ?: return null
    val instanceQualifier = exportCall.methodExpression.qualifierExpression as? PsiReferenceExpression
        ?: return null
    val instanceVar = instanceQualifier.resolve() as? PsiVariable ?: return null
    val instanceInit = instanceVar.initializer ?: return null

    val moduleArg = findInstanceBuilderArg(instanceInit) ?: return null
    val filename = findWasmFilenameInExpression(moduleArg, depth = 0) ?: return null

    val containingVirtualFile = javaFile.virtualFile ?: return null
    val packagePath = javaFile.packageName.takeIf { it.isNotEmpty() }?.replace('.', '/')
    return resolveWasmFile(filename, containingVirtualFile, javaFile.project, packagePath)
}

/**
 * [getWasmDataForFile] but scoped to the specific `instance.export(...)` call site.
 * Caller is responsible for falling back to the file-level lookup when this returns
 * `null` — keeps single-module files working through the legacy path.
 */
internal fun getScopedWasmData(
    exportCall: PsiMethodCallExpression,
    psiFile: PsiFile
): CoreWasmData? {
    val wasmFile = findScopedWasmFile(exportCall, psiFile) ?: return null
    return WasmBinaryDataCacheService.getInstance().getOrParseBinary(wasmFile) as? CoreWasmData
}

/**
 * Block-level scoped lookup for `new HostFunction(...)` call sites.
 *
 * Unlike [findScopedWasmFile] (which traces backward from an `export()` call's
 * `instance` qualifier), a HostFunction has no `instance` to walk through — it's
 * the *input* to a later `Instance.builder().withImportValues(...)` call, not an
 * output of one. We approximate scoping by finding the textually closest
 * `Instance.builder(module)` call inside the same method/block and tracing
 * `module → .wasm` from there.
 *
 * "Textually closest" handles both common shapes:
 *   - HostFunction declared *before* the Instance.builder call (typical)
 *   - HostFunction declared *after* (rare, e.g. when the lambda captures the
 *     instance)
 *
 * Returns `null` when the heuristic fails (no builder in scope, multiple
 * unrelated builders, builder argument not traceable to a `.wasm` literal) so
 * callers can fall back to file-level resolution.
 */
internal fun findScopedWasmFileForHostFunction(
    newExpr: PsiNewExpression,
    psiFile: PsiFile
): VirtualFile? {
    val javaFile = psiFile as? PsiJavaFile ?: return null
    val containingVirtualFile = javaFile.virtualFile ?: return null

    val scope = PsiTreeUtil.getParentOfType(
        newExpr,
        PsiMethod::class.java,
        PsiClassInitializer::class.java,
        PsiField::class.java
    ) ?: return null

    val builderCall = findClosestInstanceBuilderInScope(scope, newExpr) ?: return null
    val moduleArg = builderCall.argumentList.expressions.firstOrNull() ?: return null
    val filename = findWasmFilenameInExpression(moduleArg, depth = 0) ?: return null

    val packagePath = javaFile.packageName.takeIf { it.isNotEmpty() }?.replace('.', '/')
    return resolveWasmFile(filename, containingVirtualFile, javaFile.project, packagePath)
}

/**
 * [getWasmDataForFile] but scoped to a specific `new HostFunction(...)` site via
 * [findScopedWasmFileForHostFunction].
 */
internal fun getScopedWasmDataForHostFunction(
    newExpr: PsiNewExpression,
    psiFile: PsiFile
): CoreWasmData? {
    val wasmFile = findScopedWasmFileForHostFunction(newExpr, psiFile) ?: return null
    return WasmBinaryDataCacheService.getInstance().getOrParseBinary(wasmFile) as? CoreWasmData
}

/**
 * Finds the `Instance.builder(...)` call within [scope] whose text offset is
 * closest to [target]. Matches by short name (`Instance.builder`) so both
 * imported and fully-qualified forms work without a classpath dependency.
 * Returns `null` when no qualifying call exists in scope.
 */
private fun findClosestInstanceBuilderInScope(
    scope: PsiElement,
    target: PsiElement
): PsiMethodCallExpression? {
    val targetOffset = target.textOffset
    return PsiTreeUtil.findChildrenOfType(scope, PsiMethodCallExpression::class.java)
        .filter { call ->
            call.methodExpression.referenceName == "builder" &&
                (call.methodExpression.qualifierExpression as? PsiReferenceExpression)
                    ?.referenceName == "Instance"
        }
        .minByOrNull { kotlin.math.abs(it.textOffset - targetOffset) }
}

/**
 * Walks a method-call chain looking for `Instance.builder(module)` and returns the
 * `module` argument expression. Handles the canonical
 * `Instance.builder(module).withImportValues(...).build()` shape regardless of how
 * many builder methods sit between `builder(...)` and the terminal `.build()`.
 */
private fun findInstanceBuilderArg(expr: PsiExpression?): PsiExpression? {
    var current: PsiMethodCallExpression? = expr as? PsiMethodCallExpression
    while (current != null) {
        if (current.methodExpression.referenceName == "builder") {
            return current.argumentList.expressions.firstOrNull()
        }
        current = current.methodExpression.qualifierExpression as? PsiMethodCallExpression
    }
    return null
}

/**
 * Recursively unwraps an expression looking for a string literal that ends in
 * `.wasm`. Bounded to keep PSI walks cheap on real-world code.
 *
 * Handles the common shapes that feed `Parser.parse(...)`:
 *  - direct literal:                 `"foo.wasm"` / `"/foo.wasm"`
 *  - `getResource(...)`:             `App.class.getResource("foo.wasm")`
 *  - `getResourceAsStream(...)`:     `App.class.getResourceAsStream("foo.wasm")`
 *  - `Paths.get(...)`:               `Paths.get("foo.wasm")`
 *  - `new File(...)`:                `new File("foo.wasm")`
 *  - any helper method that returns one of the above (one indirection is enough
 *    for the example-app pattern `parseResource("/foo.wasm")`).
 *  - reference to a local variable initialised from any of the above.
 */
private fun findWasmFilenameInExpression(expr: PsiExpression?, depth: Int): String? {
    if (expr == null || depth > 8) return null
    return when (expr) {
        is PsiLiteralExpression -> {
            (expr.value as? String)?.takeIf { it.endsWith(".wasm") }
        }
        is PsiReferenceExpression -> {
            val resolved = expr.resolve()
            findWasmFilenameInExpression((resolved as? PsiVariable)?.initializer, depth + 1)
        }
        is PsiMethodCallExpression -> {
            for (arg in expr.argumentList.expressions) {
                findWasmFilenameInExpression(arg, depth + 1)?.let { return it }
            }
            findWasmFilenameInExpression(expr.methodExpression.qualifierExpression, depth + 1)
        }
        is PsiNewExpression -> {
            expr.argumentList?.expressions?.firstNotNullOfOrNull {
                findWasmFilenameInExpression(it, depth + 1)
            }
        }
        else -> null
    }
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
