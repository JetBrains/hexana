package org.jetbrains.hexana.javaLang

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*

/**
 * Inspection that warns when a string literal passed to a WASM export accessor
 * does not match any export in the referenced `.wasm` module.
 *
 * Covers:
 * - GraalWasm: `exports.invokeMember("name")`, `exports.getMember("name")`
 *   where `exports` is a variable assigned from `instance.getMember("exports")`
 * - Chicory:   `instance.export("name")`
 *
 * `hasMember()` is intentionally excluded since it is a runtime check, not an invocation.
 */
class WasmExportInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                val methodName = expression.methodExpression.referenceName ?: return

                when (methodName) {
                    // Chicory: instance.export("name")
                    "export" -> checkExportName(expression, holder)
                    // GraalWasm: exports.invokeMember("name"), exports.getMember("name")
                    "invokeMember", "getMember" -> {
                        if (isCalledOnExportsObject(expression)) {
                            checkExportName(expression, holder)
                        }
                    }
                }
            }
        }
    }

    private fun checkExportName(expression: PsiMethodCallExpression, holder: ProblemsHolder) {
        val args = expression.argumentList.expressions
        if (args.isEmpty()) return
        val firstArg = args[0] as? PsiLiteralExpression ?: return
        val exportName = firstArg.value as? String ?: return

        val data = getWasmDataForFile(holder.file) ?: return
        val exportNames = data.exports.map { it.name }.toSet()

        if (exportName !in exportNames) {
            holder.registerProblem(
                firstArg,
                "Export '$exportName' not found in WebAssembly module"
            )
        }
    }

}
