package org.jetbrains.hexana.javaLang

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*

/**
 * Inspection that warns when the number of arguments passed to a WASM exported function
 * does not match the function's signature in the `.wasm` module.
 *
 * Covers:
 * - GraalWasm: `value.invokeMember("funcName", arg1, arg2, ...)` — args after the name string
 * - Chicory:   `exportFunc.apply(arg1, arg2, ...)` — all args are WASM function arguments
 *
 * Note: For `invokeMember`, the first argument is the function name, so WASM args = total args - 1.
 * For Chicory `apply()`, all arguments are WASM args.
 */
class WasmExportArgCountInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                val methodName = expression.methodExpression.referenceName ?: return

                when (methodName) {
                    "invokeMember" -> {
                        if (isCalledOnExportsObject(expression)) {
                            checkInvokeMember(expression, holder)
                        }
                    }
                    "apply" -> checkChicoryApply(expression, holder)
                }
            }
        }
    }

    private fun checkInvokeMember(expression: PsiMethodCallExpression, holder: ProblemsHolder) {
        val args = expression.argumentList.expressions
        if (args.isEmpty()) return

        val nameArg = args[0] as? PsiLiteralExpression ?: return
        val exportName = nameArg.value as? String ?: return

        val data = getWasmDataForFile(holder.file) ?: return
        val funcType = resolveFunctionType(data, exportName) ?: return

        val expectedParamCount = funcType.params.size
        val actualArgCount = args.size - 1 // first arg is the function name

        if (actualArgCount != expectedParamCount) {
            holder.registerProblem(
                nameArg,
                "Function '$exportName' expects $expectedParamCount argument(s), but $actualArgCount provided"
            )
        }
    }

    private fun checkChicoryApply(expression: PsiMethodCallExpression, holder: ProblemsHolder) {
        // We need to trace back to find the export name.
        // Pattern: ExportFunction func = instance.export("name"); func.apply(...)
        // The qualifier of .apply() should resolve to a variable assigned from instance.export("name")
        val qualifier = expression.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return
        val resolved = qualifier.resolve() as? PsiLocalVariable ?: return
        val initializer = resolved.initializer as? PsiMethodCallExpression ?: return

        val initMethodName = initializer.methodExpression.referenceName ?: return
        if (initMethodName != "export") return

        val exportName = getFirstStringArg(initializer) ?: return
        // Use the export() call as the call site for scoped lookup so the arity
        // check compares against the correct module when a file uses several.
        val data = getScopedWasmData(initializer, holder.file)
            ?: getWasmDataForFile(holder.file)
            ?: return
        val funcType = resolveFunctionType(data, exportName) ?: return

        val expectedParamCount = funcType.params.size
        val actualArgCount = expression.argumentList.expressions.size

        if (actualArgCount != expectedParamCount) {
            holder.registerProblem(
                expression.argumentList,
                "Function '$exportName' expects $expectedParamCount argument(s), but $actualArgCount provided"
            )
        }
    }
}
