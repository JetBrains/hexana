package org.jetbrains.hexana.javaLang

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
 * Resolves the `.wasm` [VirtualFile] referenced from a Java source file.
 *
 * Scans the file for known patterns:
 * - GraalWasm: `Source.newBuilder("wasm", Foo.class.getResource("file.wasm"))`
 * - Chicory:   `Parser.parse(Foo.class.getResourceAsStream("file.wasm"))`
 * - Chicory:   `Parser.parse(new File("file.wasm"))`
 */
internal fun findReferencedWasmFile(psiFile: PsiFile): VirtualFile? {
    val javaFile = psiFile as? PsiJavaFile ?: return null
    val containingVirtualFile = javaFile.virtualFile ?: return null

    // Collect all string literals that end with .wasm
    val wasmLiterals = mutableListOf<String>()
    javaFile.accept(object : JavaRecursiveElementVisitor() {
        override fun visitLiteralExpression(expression: PsiLiteralExpression) {
            val value = expression.value as? String ?: return
            if (value.endsWith(".wasm")) {
                wasmLiterals.add(value)
            }
        }
    })

    if (wasmLiterals.isEmpty()) return null
    val wasmFileName = wasmLiterals.first()

    // Try sibling directory (same folder as the Java file)
    val siblingDir = containingVirtualFile.parent
    siblingDir?.findChild(wasmFileName)?.let { return it }

    // Try resource roots of the containing module
    val project = javaFile.project
    val fileIndex = ProjectFileIndex.getInstance(project)
    val module = fileIndex.getModuleForFile(containingVirtualFile)
    if (module != null) {
        for (sourceRoot in ModuleRootManager.getInstance(module).sourceRoots) {
            sourceRoot.findFileByRelativePath(wasmFileName)?.let { return it }
        }
    }

    // Fallback: search by name across project scope
    val psiManager = PsiManager.getInstance(project)
    val scope = GlobalSearchScope.projectScope(project)
    val shortName = wasmFileName.substringAfterLast('/')
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
