package org.jetbrains.hexana.javascript

import com.intellij.lang.javascript.index.FrameworkIndexingHandler
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.resolve.JSEvaluateContext
import com.intellij.lang.javascript.psi.types.JSNamedTypeFactory
import com.intellij.lang.javascript.psi.types.JSRecordTypeImpl
import com.intellij.lang.javascript.psi.types.JSTypeContext
import com.intellij.lang.javascript.psi.types.evaluable.JSUnwrapPromiseType
import com.intellij.lang.javascript.psi.types.recordImpl.PropertySignatureImpl
import com.intellij.psi.SyntaxTraverser

class WasmFrameworkIndexingHandler: FrameworkIndexingHandler() {
    override fun adjustTypeBeforeAdd(type: JSType, context: JSEvaluateContext): JSType {
        if (type !is JSUnwrapPromiseType) return type
        val expr = (type.sourceElement as? JSPrefixExpression)?.expression
        if (expr !is JSCallExpression) return type
        val callee = (expr.stubSafeMethodExpression as? JSReferenceExpression)?.referencedName
        if (callee != "instantiateStreaming") return type
        val arg = expr.arguments.firstOrNull() ?: return type
        val wasmLiteral = SyntaxTraverser.psiTraverser(arg).filterIsInstance<JSLiteralExpression>().map { it.value }
            .firstOrNull { it is String && it.contains(".wasm") }

        return JSRecordTypeImpl(
            type.source,
            listOf(
                PropertySignatureImpl("instance",
                    JSNamedTypeFactory.createType("WebAssembly.Instance", type.source, JSTypeContext.INSTANCE, false),
                    false, false
                ),
                PropertySignatureImpl("module",
                    JSNamedTypeFactory.createType("WebAssembly.Module", type.source, JSTypeContext.INSTANCE, false),
                    false, false
                )
            )
        )
    }
}