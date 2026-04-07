package org.jetbrains.hexana.javascript

import com.intellij.lang.javascript.index.FrameworkIndexingHandler
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSPrefixExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSType
import com.intellij.lang.javascript.psi.resolve.JSEvaluateContext
import com.intellij.lang.javascript.psi.types.JSNamedTypeFactory
import com.intellij.lang.javascript.psi.types.JSRecordTypeImpl
import com.intellij.lang.javascript.psi.types.JSTypeContext
import com.intellij.lang.javascript.psi.types.evaluable.JSUnwrapPromiseType
import com.intellij.lang.javascript.psi.types.recordImpl.PropertySignatureImpl

class WasmFrameworkIndexingHandler: FrameworkIndexingHandler() {
    override fun adjustTypeBeforeAdd(type: JSType, context: JSEvaluateContext): JSType {
        /*if (type is JSNamedType && type.qualifiedName.name == "Instance"
            && type.sourceElement?.parentOfType<TypeScriptInterface>()?.name == "WebAssemblyInstantiatedSource") {
            // todo instantiate here!
        }*/

        if (type is JSUnwrapPromiseType) {
            val expr = (type.sourceElement as? JSPrefixExpression)?.expression
            if (expr is JSCallExpression) {
                val callee = (expr.stubSafeMethodExpression as? JSReferenceExpression)?.referencedName
                if (callee == "instantiateStreaming") {
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
            return type
        }

        return type
    }
}