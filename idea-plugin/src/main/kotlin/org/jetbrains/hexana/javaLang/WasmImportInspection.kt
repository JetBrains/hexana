package org.jetbrains.hexana.javaLang

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * Inspection that warns when import module or function names used in GraalWasm's
 * `ProxyObject.fromMap(Map.of(...))` do not match the import section of the `.wasm` module.
 *
 * Covers:
 * - Module names: first-level string keys in `Map.of("module_name", ...)`
 *   inside `module.newInstance(ProxyObject.fromMap(...))`
 * - Function names: second-level string keys in nested `Map.of("func_name", ...)`
 *
 * Expected PSI chain:
 * ```
 * newInstance(ProxyObject.fromMap(Map.of(          // depth 1: module names
 *     "env", ProxyObject.fromMap(Map.of(           // depth 2: function names
 *         "func_name", impl
 *     ))
 * )))
 * ```
 *
 * Only applies when GraalVM Polyglot API patterns are detected.
 */
class WasmImportInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitLiteralExpression(expression: PsiLiteralExpression) {
                val value = expression.value as? String ?: return

                // Must be inside a Map.of() call
                val mapOfCall = findDirectMapOfCall(expression) ?: return

                val argIndex = getArgIndex(expression, mapOfCall)
                // Only check even-indexed args (keys in Map.of(k1, v1, k2, v2, ...))
                if (argIndex < 0 || argIndex % 2 != 0) return

                val data = getWasmDataForFile(holder.file) ?: return

                // Walk up: Map.of → ProxyObject.fromMap → ?
                val fromMapCall = findEnclosingFromMap(mapOfCall) ?: return
                val parentCall = PsiTreeUtil.getParentOfType(
                    fromMapCall, PsiMethodCallExpression::class.java, true
                ) ?: return
                val parentMethodName = parentCall.methodExpression.referenceName

                if (parentMethodName == "newInstance") {
                    // Depth 1: this is a module name
                    val moduleNames = data.imports.map { it.prefix }.toSet()
                    if (value !in moduleNames) {
                        holder.registerProblem(
                            expression,
                            "Import module '$value' not found in WebAssembly module"
                        )
                    }
                } else if (parentMethodName == "of") {
                    // Depth 2: this is a function name inside a module
                    // parentCall is the outer Map.of, verify it's wrapped in fromMap → newInstance
                    val outerFromMap = findEnclosingFromMap(parentCall)
                    val newInstanceCall = if (outerFromMap != null) {
                        PsiTreeUtil.getParentOfType(outerFromMap, PsiMethodCallExpression::class.java, true)
                    } else null

                    if (newInstanceCall != null && newInstanceCall.methodExpression.referenceName == "newInstance") {
                        val moduleName = findModuleNameForInner(fromMapCall, parentCall)
                        if (moduleName != null) {
                            val funcNames = data.imports
                                .filter { it.prefix == moduleName }
                                .map { it.name }
                                .toSet()
                            if (value !in funcNames) {
                                holder.registerProblem(
                                    expression,
                                    "Import function '$value' not found in module '$moduleName'"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds the directly enclosing `Map.of(...)` call for a literal expression.
     */
    private fun findDirectMapOfCall(element: PsiElement): PsiMethodCallExpression? {
        val call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java, true)
            ?: return null
        return if (call.methodExpression.referenceName == "of") call else null
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

    private fun getArgIndex(literal: PsiLiteralExpression, call: PsiMethodCallExpression): Int {
        val args = call.argumentList.expressions
        return args.indexOfFirst { it === literal }
    }

    /**
     * Given the inner `ProxyObject.fromMap(...)` call and the outer `Map.of(...)` call,
     * finds the module name string key that precedes the fromMap value in the outer args.
     *
     * Pattern: `Map.of("module_name", ProxyObject.fromMap(Map.of(...)))`
     */
    private fun findModuleNameForInner(
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
