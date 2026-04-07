package org.jetbrains.hexana.javascript

import com.intellij.lang.javascript.JSStringUtil
import com.intellij.lang.javascript.index.FrameworkIndexingHandler
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.resolve.JSEvaluateContext
import com.intellij.lang.javascript.psi.types.*
import com.intellij.lang.javascript.psi.types.evaluable.JSUnwrapPromiseType
import com.intellij.lang.javascript.psi.types.recordImpl.PropertySignatureImpl
import com.intellij.psi.SyntaxTraverser
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.components.WasmComponent

class WasmFrameworkIndexingHandler: FrameworkIndexingHandler() {
    override fun adjustTypeBeforeAdd(type: JSType, context: JSEvaluateContext): JSType {
        if (type !is JSUnwrapPromiseType) return type
        val expr = (type.sourceElement as? JSPrefixExpression)?.expression
        if (expr !is JSCallExpression) return type
        val callee = (expr.stubSafeMethodExpression as? JSReferenceExpression)?.referencedName
        if (callee != "instantiateStreaming") return type
        val arg = expr.arguments.firstOrNull() ?: return type
        val wasmLiteral = SyntaxTraverser.psiTraverser(arg).filterIsInstance<JSLiteralExpression>().map { it.value }
            .firstOrNull { it is String && it.contains(".wasm") }?.let { it as? String }?.let {
                JSStringUtil.unquoteAndUnescapeString(it)
            }
        if (wasmLiteral != null) {
            expr.containingFile.virtualFile?.findFileByRelativePath(wasmLiteral)?.let {
                it.refresh(false, false)
                val buffer = it.toCommonByteBuffer() ?: return type
                if (WasmBinaryProviderSelector.select(buffer) == BinaryProviderType.WasmComponent) {
                    ((WasmParser().parse(buffer) as? WasmParseSuccess)?.wasmModule as? WasmComponent)?.let {
                        return createCustomWasmType(type, it, buffer)
                    }
                }
            }
        }
        return type
    }

    private fun createCustomWasmType(type: JSUnwrapPromiseType, component: WasmComponent, buffer: CommonByteBuffer): JSRecordTypeImpl = JSRecordTypeImpl(
        type.source,
        listOf(
            PropertySignatureImpl(
                "instance",
                JSRecordTypeImpl(
                    type.source,
                    listOf(PropertySignatureImpl("exports",
                        JSContextualUnionTypeImpl(type.source, listOf(
                            JSRecordTypeImpl(type.source, component.exportSection.flatMap {
                                with(it) {
                                    buffer.scan()
                                }.map {
                                    PropertySignatureImpl(it.exportName, JSAnyType.get(type.source), false, false)
                                }
                            }),
                            JSNamedTypeFactory.createType("WebAssembly.Instance.Exports", type.source, JSTypeContext.INSTANCE, false),
                        ))
                        , false, false))
                ),
                false, false
            ),
            PropertySignatureImpl(
                "module",
                JSNamedTypeFactory.createType("WebAssembly.Module", type.source, JSTypeContext.INSTANCE, false),
                false, false
            )
        )
    )
}