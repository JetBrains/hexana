package org.jetbrains.hexana.javaLang

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.hexana.wasmcache.CoreWasmData
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

private const val GRAALVM_PACKAGE = "org.graalvm.polyglot"

/**
 * Provides WASM-aware code completion for GraalVM Polyglot API patterns:
 *
 * - **Export names**: `value.invokeMember("...")`, `value.getMember("...")`, `value.hasMember("...")`
 * - **Import module names**: first-level keys in `ProxyObject.fromMap(Map.of("...", ...))`
 *   inside `module.newInstance(...)`
 * - **Import function names**: second-level keys in nested `Map.of("...", ...)`
 *
 * Only active when `org.graalvm.polyglot.Value` is on the project classpath.
 */
class GraalWasmJavaCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(JavaTokenType.STRING_LITERAL),
            GraalWasmCompletionProvider()
        )
    }
}

private class GraalWasmCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val position = parameters.position

        val psiFile = parameters.originalFile
        if (!fileImportsPackage(psiFile, GRAALVM_PACKAGE)) return
        val data = getWasmDataForFile(psiFile) ?: return

        val literal = PsiTreeUtil.getParentOfType(position, PsiLiteralExpression::class.java) ?: return
        val methodCall = PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression::class.java) ?: return
        val methodName = methodCall.methodExpression.referenceName ?: return

        when (methodName) {
            "invokeMember", "getMember", "hasMember" -> addExportCompletions(data, result)
            "of" -> addImportCompletions(data, literal, methodCall, result)
        }
    }

    private fun addExportCompletions(data: CoreWasmData, result: CompletionResultSet) {
        for (export in data.exports) {
            val icon = when (export) {
                is WasmFunctionExportRecordEntry -> AllIcons.Nodes.Function
                else -> AllIcons.Nodes.Property
            }
            val typeText = when (export.externType.toInt()) {
                0 -> "func"
                1 -> "table"
                2 -> "memory"
                3 -> "global"
                4 -> "tag"
                else -> null
            }
            result.addElement(
                PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(export.name)
                        .withIcon(icon)
                        .withTypeText(typeText),
                    100.0
                )
            )
        }
    }

    /**
     * Determines the import completion context by walking up the PSI tree from the
     * Map.of() call containing the caret.
     *
     * Inline pattern (Map.of nested directly inside newInstance):
     * ```
     * module.newInstance(ProxyObject.fromMap(          // depth 1: module names
     *     Map.of("env", ProxyObject.fromMap(           // depth 2: function names
     *         Map.of("<caret>")
     *     ))
     * ))
     * ```
     *
     * Variable pattern (Map.of inside fromMap assigned to a variable):
     * ```
     * ProxyObject envImports = ProxyObject.fromMap(Map.of("<caret>"));
     * ProxyObject allImports = ProxyObject.fromMap(Map.of("env", envImports));
     * module.newInstance(allImports);
     * ```
     *
     * Parameter pattern (fromMap result passed to a method):
     * ```
     * initModule(ProxyObject.fromMap(Map.of("<caret>")));
     * ```
     */
    private fun addImportCompletions(
        data: CoreWasmData,
        literal: PsiLiteralExpression,
        mapOfCall: PsiMethodCallExpression,
        result: CompletionResultSet
    ) {
        // Walk up: Map.of -> its parent should be ProxyObject.fromMap(Map.of(...))
        val fromMapCall = findEnclosingFromMap(mapOfCall) ?: return

        // The ProxyObject.fromMap(...) parent determines the context
        val fromMapParentCall = PsiTreeUtil.getParentOfType(
            fromMapCall, PsiMethodCallExpression::class.java, true
        )

        val parentMethodName = fromMapParentCall?.methodExpression?.referenceName

        when (parentMethodName) {
            "newInstance" -> {
                // Inline depth 1: suggest import module names
                addImportModuleCompletions(data, result)
            }
            "of" -> {
                // Inline depth 2: Map.of → fromMap → Map.of (outer) → fromMap → newInstance
                val outerFromMap = findEnclosingFromMap(fromMapParentCall!!)
                val newInstanceCall = if (outerFromMap != null) {
                    PsiTreeUtil.getParentOfType(outerFromMap, PsiMethodCallExpression::class.java, true)
                } else null

                if (newInstanceCall != null && newInstanceCall.methodExpression.referenceName == "newInstance") {
                    val moduleName = findModuleNameForNestedValue(mapOfCall, fromMapCall, fromMapParentCall)
                    addImportFunctionCompletions(data, moduleName, result)
                } else {
                    // Outer Map.of is not inside newInstance (variable/parameter pattern)
                    // Treat as module-level: suggest module names
                    addImportModuleCompletions(data, result)
                }
            }
            else -> {
                // fromMap is not inside newInstance or Map.of — it's assigned to a variable
                // or passed as a parameter. Detect depth by checking if an outer Map.of
                // contains this fromMap result as a value.
                // Heuristic: if the Map.of is directly inside fromMap and the fromMap's parent
                // is not another Map.of, assume depth 1 (module names).
                // If there IS an outer Map.of wrapping via fromMap, assume depth 2 (function names).
                val outerMapOf = findOuterMapOfViaVariable(fromMapCall)
                if (outerMapOf != null) {
                    val moduleName = findModuleNameForVariableRef(fromMapCall, outerMapOf)
                    addImportFunctionCompletions(data, moduleName, result)
                } else {
                    addImportModuleCompletions(data, result)
                }
            }
        }
    }

    /**
     * When `ProxyObject.fromMap(Map.of(...))` is assigned to a variable like `envImports`,
     * looks for an outer `Map.of(..., envImports, ...)` in the same scope that uses this
     * variable as a value argument, which would make the inner map depth 2 (function names).
     */
    private fun findOuterMapOfViaVariable(fromMapCall: PsiMethodCallExpression): PsiMethodCallExpression? {
        // fromMap is assigned to a variable: ProxyObject x = ProxyObject.fromMap(...)
        val variableDecl = PsiTreeUtil.getParentOfType(fromMapCall, PsiLocalVariable::class.java)
            ?: return null
        val variableName = variableDecl.name ?: return null

        // Search the enclosing code block for a Map.of() that references this variable
        val codeBlock = PsiTreeUtil.getParentOfType(variableDecl, PsiCodeBlock::class.java)
            ?: return null

        for (call in PsiTreeUtil.findChildrenOfType(codeBlock, PsiMethodCallExpression::class.java)) {
            if (call.methodExpression.referenceName != "of") continue
            val args = call.argumentList.expressions
            for (arg in args) {
                if (arg is PsiReferenceExpression && arg.referenceName == variableName) {
                    return call
                }
            }
        }
        return null
    }

    /**
     * Given a fromMap call assigned to a variable and an outer Map.of that uses that variable,
     * finds the module name key that precedes the variable reference in the outer Map.of args.
     */
    private fun findModuleNameForVariableRef(
        fromMapCall: PsiMethodCallExpression,
        outerMapOf: PsiMethodCallExpression
    ): String? {
        val variableDecl = PsiTreeUtil.getParentOfType(fromMapCall, PsiLocalVariable::class.java)
            ?: return null
        val variableName = variableDecl.name ?: return null
        val outerArgs = outerMapOf.argumentList.expressions
        for (i in outerArgs.indices) {
            val arg = outerArgs[i]
            if (arg is PsiReferenceExpression && arg.referenceName == variableName && i > 0) {
                return (outerArgs[i - 1] as? PsiLiteralExpression)?.value as? String
            }
        }
        return null
    }

    private fun addImportModuleCompletions(data: CoreWasmData, result: CompletionResultSet) {
        val moduleNames = data.imports.map { it.prefix }.distinct()
        for (name in moduleNames) {
            result.addElement(
                PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(name)
                        .withIcon(AllIcons.Nodes.Module)
                        .withTypeText("import module"),
                    100.0
                )
            )
        }
    }

    private fun addImportFunctionCompletions(
        data: CoreWasmData,
        moduleName: String?,
        result: CompletionResultSet
    ) {
        val functions = if (moduleName != null) {
            data.imports.filter { it.prefix == moduleName }
        } else {
            data.imports.toList()
        }
        for (imp in functions) {
            val isFunc = imp.type is WasmTypeIndexExternalType
            result.addElement(
                PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(imp.name)
                        .withIcon(if (isFunc) AllIcons.Nodes.Function else AllIcons.Nodes.Property)
                        .withTypeText(if (isFunc) "func" else "import"),
                    100.0
                )
            )
        }
    }

    /**
     * Finds the enclosing `ProxyObject.fromMap(...)` call that wraps the given expression.
     */
    private fun findEnclosingFromMap(inner: PsiMethodCallExpression): PsiMethodCallExpression? {
        val parent = PsiTreeUtil.getParentOfType(
            inner, PsiMethodCallExpression::class.java, true
        ) ?: return null
        return if (parent.methodExpression.referenceName == "fromMap") parent else null
    }

    /**
     * Given the inner Map.of (for function names), its enclosing ProxyObject.fromMap,
     * and the outer Map.of (for module names), finds the module name string literal
     * that is the key preceding the ProxyObject.fromMap value in the outer Map.of args.
     *
     * Pattern: `Map.of("module_name", ProxyObject.fromMap(Map.of("<caret>")))`
     *                   ^^ we want this   ^^ fromMapCall is an arg here
     */
    private fun findModuleNameForNestedValue(
        innerMapOf: PsiMethodCallExpression,
        fromMapCall: PsiMethodCallExpression,
        outerMapOf: PsiMethodCallExpression
    ): String? {
        val outerArgs = outerMapOf.argumentList.expressions
        for (i in outerArgs.indices) {
            if (PsiTreeUtil.isAncestor(outerArgs[i], fromMapCall, false) && i > 0) {
                return (outerArgs[i - 1] as? PsiLiteralExpression)?.value as? String
            }
        }
        return null
    }
}
