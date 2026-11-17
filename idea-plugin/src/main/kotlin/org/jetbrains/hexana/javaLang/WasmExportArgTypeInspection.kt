package org.jetbrains.hexana.javaLang

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import org.jetbrains.hexana.wasm.sections.*

/**
 * Inspection that warns when an argument passed to a WASM exported function
 * has a Java type incompatible with the WASM parameter type.
 *
 * Type mapping (WASM → compatible Java types):
 * - `i32` → int, short, byte, char (integer types ≤ 32-bit)
 * - `i64` → long, int, short, byte, char (integer types ≤ 64-bit)
 * - `f32` → float
 * - `f64` → double, float
 *
 * Covers:
 * - GraalWasm: `exports.invokeMember("func", arg1, arg2, ...)`
 */
class WasmExportArgTypeInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                val methodName = expression.methodExpression.referenceName ?: return
                if (methodName != "invokeMember") return
                if (!isCalledOnExportsObject(expression)) return

                val args = expression.argumentList.expressions
                if (args.size < 2) return

                val nameArg = args[0] as? PsiLiteralExpression ?: return
                val exportName = nameArg.value as? String ?: return

                val data = getWasmDataForFile(holder.file) ?: return
                val funcType = resolveFunctionType(data, exportName) ?: return

                val wasmParams = funcType.params
                val javaArgs = args.drop(1) // skip the function name argument

                for (i in wasmParams.indices) {
                    if (i >= javaArgs.size) break
                    val javaArg = javaArgs[i]
                    val javaType = javaArg.type ?: continue
                    val wasmParam = wasmParams[i]

                    if (!isCompatible(wasmParam, javaType)) {
                        holder.registerProblem(
                            javaArg,
                            "Argument type '${javaType.presentableText}' is not compatible " +
                                "with WASM parameter type '${wasmTypeName(wasmParam)}' " +
                                "at position ${i + 1} of '$exportName'"
                        )
                    }
                }
            }
        }
    }

    companion object {
        private val I32_COMPATIBLE = setOf(
            PsiTypes.intType().canonicalText,
            PsiTypes.shortType().canonicalText,
            PsiTypes.byteType().canonicalText,
            PsiTypes.charType().canonicalText,
            "java.lang.Integer",
            "java.lang.Short",
            "java.lang.Byte",
            "java.lang.Character",
        )

        private val I64_COMPATIBLE = I32_COMPATIBLE + setOf(
            PsiTypes.longType().canonicalText,
            "java.lang.Long",
        )

        private val F32_COMPATIBLE = setOf(
            PsiTypes.floatType().canonicalText,
            "java.lang.Float",
        )

        private val F64_COMPATIBLE = F32_COMPATIBLE + setOf(
            PsiTypes.doubleType().canonicalText,
            "java.lang.Double",
        )

        internal fun isCompatible(wasmType: WasmValueType<*>, javaType: PsiType): Boolean {
            val canonical = javaType.canonicalText
            return when (wasmType) {
                is WasmI32Type -> canonical in I32_COMPATIBLE
                is WasmI64Type -> canonical in I64_COMPATIBLE
                is WasmF32Type -> canonical in F32_COMPATIBLE
                is WasmF64Type -> canonical in F64_COMPATIBLE
                else -> true // unknown WASM types — don't flag
            }
        }

        internal fun wasmTypeName(type: WasmValueType<*>): String = when (type) {
            is WasmI32Type -> "i32"
            is WasmI64Type -> "i64"
            is WasmF32Type -> "f32"
            is WasmF64Type -> "f64"
            is WasmVecType -> "v128"
            else -> "unknown"
        }
    }
}
