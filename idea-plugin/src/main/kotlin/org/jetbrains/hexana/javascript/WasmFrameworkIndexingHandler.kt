package org.jetbrains.hexana.javascript

import com.intellij.lang.javascript.index.FrameworkIndexingHandler
import com.intellij.lang.javascript.psi.JSType
import com.intellij.lang.javascript.psi.ecma6.TypeScriptInterface
import com.intellij.lang.javascript.psi.resolve.JSEvaluateContext
import com.intellij.lang.javascript.psi.types.JSNamedType
import com.intellij.psi.util.parentOfType

class WasmFrameworkIndexingHandler: FrameworkIndexingHandler() {
    override fun adjustTypeBeforeAdd(type: JSType, context: JSEvaluateContext): JSType {
        if (type is JSNamedType && type.qualifiedName.name == "Instance"
            && type.sourceElement?.parentOfType<TypeScriptInterface>()?.name == "WebAssemblyInstantiatedSource") {
            // todo instantiate here!
        }

        return type
    }
}