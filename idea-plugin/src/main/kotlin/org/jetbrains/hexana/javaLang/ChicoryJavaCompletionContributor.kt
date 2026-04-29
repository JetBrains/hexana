package org.jetbrains.hexana.javaLang

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.hexana.javascript.CoreWasmData
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

private const val CHICORY_PACKAGE = "com.dylibso.chicory"

/**
 * Provides WASM-aware code completion for Chicory WebAssembly runtime API patterns:
 *
 * - **Export names**: `instance.export("...")`
 * - **Import module names**: first argument of `new HostFunction("...", ...)`
 * - **Import function names**: second argument of `new HostFunction("mod", "...", ...)`
 *
 * Only active when `com.dylibso.chicory.runtime.Instance` is on the project classpath.
 */
class ChicoryJavaCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(JavaTokenType.STRING_LITERAL),
            ChicoryCompletionProvider()
        )
    }
}

private class ChicoryCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val position = parameters.position

        val psiFile = parameters.originalFile
        if (!fileImportsPackage(psiFile, CHICORY_PACKAGE)) return

        val literal = PsiTreeUtil.getParentOfType(position, PsiLiteralExpression::class.java) ?: return

        // Check if we're inside instance.export("...") — scope lookup to the specific
        // module fed into that instance, falling back to file-level for code shapes the
        // data-flow walk can't trace (parameters, fields, method-return chains).
        val methodCall = PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression::class.java)
        if (methodCall != null && methodCall.methodExpression.referenceName == "export") {
            val data = getScopedWasmData(methodCall, psiFile) ?: getWasmDataForFile(psiFile) ?: return
            addExportCompletions(data, result)
            return
        }

        // Check if we're inside new HostFunction("...", "...", ...) — scope to the
        // closest sibling Instance.builder(module) in the same method/block, then
        // fall back to file-level when no builder is in scope (e.g. when the
        // HostFunction is constructed by a helper method).
        val newExpr = PsiTreeUtil.getParentOfType(literal, PsiNewExpression::class.java)
        if (newExpr != null) {
            val classRef = newExpr.classReference
            if (classRef != null && classRef.referenceName == "HostFunction") {
                val data = getScopedWasmDataForHostFunction(newExpr, psiFile)
                    ?: getWasmDataForFile(psiFile)
                    ?: return
                addHostFunctionCompletions(data, literal, newExpr, result)
                return
            }
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

    private fun addHostFunctionCompletions(
        data: CoreWasmData,
        literal: PsiLiteralExpression,
        newExpr: PsiNewExpression,
        result: CompletionResultSet
    ) {
        val argList = newExpr.argumentList ?: return
        val args = argList.expressions
        val argIndex = args.indexOf(literal)

        when (argIndex) {
            0 -> {
                // First argument: module name
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
            1 -> {
                // Second argument: function name for the module specified in the first argument
                val moduleName = (args.getOrNull(0) as? PsiLiteralExpression)?.value as? String
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
        }
    }
}
