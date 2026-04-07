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
import org.jetbrains.hexana.wasm.components.*

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
            expr.containingFile.originalFile.virtualFile?.parent?.findFileByRelativePath(wasmLiteral)?.let {
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

    private fun createCustomWasmType(type: JSUnwrapPromiseType, component: WasmComponent, buffer: CommonByteBuffer): JSRecordTypeImpl {
        val allTypes = component.sections.filterIsInstance<WasmComponentTypesSection>().flatMap {
            with(it) {
                buffer.scanTypes()
            }
        }

        return JSRecordTypeImpl(
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
                                    }.map { export ->
                                        PropertySignatureImpl(export.exportName
                                            .substringAfter('/').substringBefore('@'),
                                            resolveExportType(export, allTypes, type.source), false, false)
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

    private fun resolveExportType(export: WasmComponentExport, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val defType = resolveDefType(export, allTypes) ?: return JSAnyType.get(source)
        return mapComponentDefTypeToJsType(defType, allTypes, source)
    }

    private fun resolveDefType(export: WasmComponentExport, allTypes: List<ComponentDefType>): ComponentDefType? {
        val externDesc = export.externDescription
        if (externDesc != null) {
            return when (externDesc) {
                is ComponentExternType -> allTypes.getOrNull(externDesc.idx.toInt())
                is ComponentExternCoreType -> allTypes.getOrNull(externDesc.idx.toInt())
                else -> null
            }
        }
        return when (export.sortIndex.sort) {
            is ComponentSort.Func,
            is ComponentSort.Type,
            is ComponentSort.Component,
            is ComponentSort.Instance,
            is ComponentSort.Value,
            is ComponentSortWithCoreSort -> allTypes.getOrNull(export.sortIndex.index.toInt())
        }
    }

    private fun mapComponentDefTypeToJsType(defType: ComponentDefType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        return when (defType) {
            is ComponentRoutineType -> mapFunctionTypeToJs(defType, allTypes, source)
            is ComponentInstanceType -> mapInstanceTypeToJs(defType, allTypes, source)
            is ComponentPrimitiveType -> mapPrimitiveToJs(defType, source)
            is ComponentValueIdxType -> {
                val resolved = allTypes.getOrNull(defType.idx.toInt())
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source) else JSAnyType.get(source)
            }
            is ComponentPrimitiveValueType -> mapPrimitiveToJs(defType.type, source)
            is ComponentRecordType -> mapRecordTypeToJs(defType, allTypes, source)
            is ComponentListType -> mapListTypeToJs(defType, allTypes, source)
            is ComponentOptionType -> mapOptionTypeToJs(defType, allTypes, source)
            is ComponentResultType -> mapResultTypeToJs(defType, allTypes, source)
            is ComponentTupleType -> mapTupleTypeToJs(defType, allTypes, source)
            is ComponentEnumType -> mapEnumTypeToJs(defType, source)
            is ComponentFlagType -> mapFlagTypeToJs(defType, allTypes, source)
            is ComponentVariantType -> mapVariantTypeToJs(defType, allTypes, source)
            is ComponentType -> JSAnyType.get(source)
            is ComponentOwnType -> JSAnyType.get(source)
            is ComponentBorrowType -> JSAnyType.get(source)
            is ComponentResourceType -> JSAnyType.get(source)
            is ComponentStreamType -> JSAnyType.get(source)
            is ComponentFutureType -> JSAnyType.get(source)
            is ComponentArrayType -> mapArrayTypeToJs(defType, allTypes, source)
        }
    }

    private fun mapPrimitiveToJs(primitive: ComponentPrimitiveType, source: JSTypeSource): JSType {
        return when (primitive) {
            is ComponentPrimitiveBoolType -> JSNamedTypeFactory.createType("boolean", source, JSTypeContext.INSTANCE, false)
            is ComponentPrimitiveS8Type,
            is ComponentPrimitiveU8Type,
            is ComponentPrimitiveS16Type,
            is ComponentPrimitiveU16Type,
            is ComponentPrimitiveS32Type,
            is ComponentPrimitiveU32Type,
            is ComponentPrimitiveF32Type,
            is ComponentPrimitiveF64Type -> JSNamedTypeFactory.createType("number", source, JSTypeContext.INSTANCE, false)
            is ComponentPrimitiveS64Type,
            is ComponentPrimitiveU64Type -> JSNamedTypeFactory.createType("bigint", source, JSTypeContext.INSTANCE, false)
            is ComponentPrimitiveString,
            is ComponentPrimitiveCharType -> JSNamedTypeFactory.createType("string", source, JSTypeContext.INSTANCE, false)
            is ComponentPrimitiveErrorContext -> JSAnyType.get(source)
        }
    }

    private fun mapFunctionTypeToJs(funcType: ComponentRoutineType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val paramTypes = funcType.params.map { param ->
            PropertySignatureImpl(param.label, mapValueTypeToJs(param.value, allTypes, source), false, false)
        }
        val returnType = mapReturnTypeToJs(funcType.ret, allTypes, source)
        return JSFunctionTypeImpl(source, paramTypes.map { JSParameterTypeDecoratorImpl(it.memberName, it.jsType, false, false, true) }, returnType)
    }

    private fun mapValueTypeToJs(valueType: ComponentValueType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        return when (valueType) {
            is ComponentPrimitiveValueType -> mapPrimitiveToJs(valueType.type, source)
            is ComponentValueIdxType -> {
                val resolved = allTypes.getOrNull(valueType.idx.toInt())
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source) else JSAnyType.get(source)
            }
        }
    }

    private fun mapReturnTypeToJs(returnType: ComponentReturnType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        return when (returnType) {
            is ComponentVoidType -> JSNamedTypeFactory.createType("void", source, JSTypeContext.INSTANCE, false)
            is ComponentValueType -> mapValueTypeToJs(returnType, allTypes, source)
            else -> JSAnyType.get(source)
        }
    }

    private fun mapRecordTypeToJs(recordType: ComponentRecordType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val properties = recordType.fields.map { field ->
            PropertySignatureImpl(field.label, mapValueTypeToJs(field.value, allTypes, source), false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun mapListTypeToJs(listType: ComponentListType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val elementType = mapValueTypeToJs(listType.elementType, allTypes, source)
        return JSArrayTypeImpl(elementType, source)
    }

    private fun mapArrayTypeToJs(arrayType: ComponentArrayType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val elementType = mapValueTypeToJs(arrayType.elementType, allTypes, source)
        return JSArrayTypeImpl(elementType, source)
    }

    private fun mapOptionTypeToJs(optionType: ComponentOptionType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val innerType = mapValueTypeToJs(optionType.option, allTypes, source)
        val undefinedType = JSNamedTypeFactory.createType("undefined", source, JSTypeContext.INSTANCE, false)
        return JSContextualUnionTypeImpl(source, listOf(innerType, undefinedType))
    }

    private fun mapResultTypeToJs(resultType: ComponentResultType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val okType = resultType.result?.let { mapValueTypeToJs(it, allTypes, source) }
            ?: JSNamedTypeFactory.createType("void", source, JSTypeContext.INSTANCE, false)
        val errType = resultType.error?.let { mapValueTypeToJs(it, allTypes, source) }
        return if (errType != null) {
            JSContextualUnionTypeImpl(source, listOf(okType, errType))
        } else {
            okType
        }
    }

    private fun mapTupleTypeToJs(tupleType: ComponentTupleType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val elementTypes = tupleType.elementTypes.map { mapValueTypeToJs(it, allTypes, source) }
        val unionType = if (elementTypes.size == 1) elementTypes.first()
        else JSContextualUnionTypeImpl(source, elementTypes)
        return JSArrayTypeImpl(unionType, source)
    }

    private fun mapEnumTypeToJs(enumType: ComponentEnumType, source: JSTypeSource): JSType {
        val literalTypes = enumType.entries.map { entry ->
            JSStringLiteralTypeImpl(entry, false, source)
        }
        return JSContextualUnionTypeImpl(source, literalTypes)
    }

    private fun mapFlagTypeToJs(flagType: ComponentFlagType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val properties = flagType.flags.map { flag ->
            PropertySignatureImpl(flag, JSNamedTypeFactory.createType("boolean", source, JSTypeContext.INSTANCE, false), false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun mapVariantTypeToJs(variantType: ComponentVariantType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val caseTypes = variantType.variants.map { variant ->
            val tagProperty = PropertySignatureImpl("tag", JSStringLiteralTypeImpl(variant.label, false, source), false, false)
            val properties = mutableListOf(tagProperty)
            val variantValue = variant.value
            if (variantValue != null) {
                properties.add(PropertySignatureImpl("val", mapValueTypeToJs(variantValue, allTypes, source), false, false))
            }
            JSRecordTypeImpl(source, properties)
        }
        return JSContextualUnionTypeImpl(source, caseTypes)
    }

    private fun mapInstanceTypeToJs(instanceType: ComponentInstanceType, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        val properties = instanceType.cd.filterIsInstance<ExportDeclaration>().map { exportDecl ->
            val memberType = resolveExternDescriptionType(exportDecl.ed, allTypes, source)
            PropertySignatureImpl(exportDecl.exportName.substringAfter('/').substringBefore('@'), memberType, false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun resolveExternDescriptionType(ed: ComponentExternDescription, allTypes: List<ComponentDefType>, source: JSTypeSource): JSType {
        return when (ed) {
            is ComponentExternType -> {
                val resolved = allTypes.getOrNull(ed.idx.toInt())
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source) else JSAnyType.get(source)
            }
            is ComponentExternCoreType -> {
                val resolved = allTypes.getOrNull(ed.idx.toInt())
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source) else JSAnyType.get(source)
            }
            is ComponentExternValueBound -> {
                when (val bound = ed.bound) {
                    is ValueBoundType -> {
                        val resolved = allTypes.getOrNull(bound.idx.toInt())
                        if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source) else JSAnyType.get(source)
                    }
                    is ValueBoundValue -> mapValueTypeToJs(bound.type, allTypes, source)
                }
            }
            is ComponentExternTypeBound -> JSAnyType.get(source)
            is ComponentExternComponent -> JSAnyType.get(source)
            is ComponentExternInstance -> {
                val resolved = allTypes.getOrNull(ed.idx.toInt())
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source) else JSAnyType.get(source)
            }
        }
    }
}
