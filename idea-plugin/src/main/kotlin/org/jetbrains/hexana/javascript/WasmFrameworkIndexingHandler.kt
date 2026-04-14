package org.jetbrains.hexana.javascript

import com.intellij.lang.javascript.JSStringUtil
import com.intellij.lang.javascript.index.FrameworkIndexingHandler
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecma6.TypeScriptModule
import com.intellij.lang.javascript.psi.resolve.JSEvaluateContext
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil
import com.intellij.lang.javascript.psi.types.*
import com.intellij.lang.javascript.psi.types.recordImpl.PropertySignatureImpl
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.contextOfType
import com.intellij.psi.util.descendantsOfType
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.looksLikeWitInterfaceExport
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.buildComponentTypeIndex
import org.jetbrains.hexana.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.hexana.wasm.sections.*

class WasmFrameworkIndexingHandler: FrameworkIndexingHandler() {
    override fun adjustTypeBeforeAdd(type: JSType, context: JSEvaluateContext): JSType {
        if (type is JSGenericTypeImpl && type.arguments.size == 1) {
            if ((type.type as? JSTypeImpl)?.qualifiedName?.qualifiedName != "Promise") return type
            val processedType = type.arguments.first() as? JSTypeImpl ?: return type
            if (isAcceptableType(processedType)) {
                val call = context.processedExpression?.context as? JSCallExpression ?: return type
                val wasmType = adjustWasmType(type.source, call)
                if (wasmType != null) {
                    return JSGenericTypeImpl(type.source, type.type, listOf(wasmType))
                }
            }
        }
        return type
    }

    private fun isAcceptableType(
        type: JSTypeImpl
    ): Boolean {
        if (type.qualifiedName.qualifiedName == "WebAssemblyInstantiatedSource") return true
        if (type.qualifiedName.qualifiedName == "Instance") {
            return type.sourceElement?.contextOfType<TypeScriptModule>()?.name == "WebAssembly"
        }
        if (type.qualifiedName.qualifiedName == "Module") {
            return type.sourceElement?.contextOfType<TypeScriptModule>()?.name == "WebAssembly"
        }
        if (type.qualifiedName.qualifiedName == "ByteArray") return true
        return false
    }

    private fun adjustWasmType(typeSource: JSTypeSource, expr: JSCallExpression?): JSType? {
        val callee = (expr?.stubSafeMethodExpression as? JSReferenceExpression)?.referencedName
        if (callee != "instantiateStreaming"
            && callee != "instantiate"
            && callee != "compileStreaming"
            && callee != "compile") return null
        val arg = expr.arguments.firstOrNull() ?: return null
        val wasmLiteral = findWasmLiteral(arg)
        if (wasmLiteral != null) {
            return createFromLiteral(expr, wasmLiteral, typeSource)
        }
        val resolvedType = JSResolveUtil.getExpressionJSType(arg)
        if (resolvedType is JSRecordTypeImpl) {
            return resolvedType.properties.firstOrNull { it.memberName == "instance" }?.jsType
        }
        // hack for instantiating from bytes - try to infer from the call chain
        if (resolvedType is JSTypeImpl && resolvedType.qualifiedName.qualifiedName == "ArrayBuffer") {
            expr.contextOfType<JSCallExpression>()?.descendantsOfType<JSCallExpression>()
                ?.firstOrNull { (it.stubSafeMethodExpression as? JSReferenceExpression)?.referenceName == "fetch" }
                ?.arguments?.firstOrNull()?.let { arg ->
                    val wasmLiteral = findWasmLiteral(arg)
                    if (wasmLiteral != null) {
                        return createFromLiteral(expr, wasmLiteral, typeSource)
                    }
                }
        }
        return null
    }

    private fun createFromLiteral(
        expr: JSCallExpression,
        wasmLiteral: String,
        typeSource: JSTypeSource
    ): JSType? = expr.containingFile.originalFile.virtualFile?.parent?.findFileByRelativePath(wasmLiteral)?.let {
        it.toCommonByteBuffer()?.let { buffer ->
            createTypeFromBinary(buffer, typeSource)
        }
    }

    private fun findWasmLiteral(arg: JSExpression): String? =
        SyntaxTraverser.psiTraverser(arg).filterIsInstance<JSLiteralExpression>().map { it.value }
            .firstOrNull { it is String && it.contains(".wasm") }?.let { it as? String }?.let {
                JSStringUtil.unquoteAndUnescapeString(it)
            }

    private fun createTypeFromBinary(buffer: CommonByteBuffer, source: JSTypeSource): JSType? {
        when (WasmBinaryProviderSelector.select(buffer)) {
            BinaryProviderType.Generic -> return null
            BinaryProviderType.WasmComponent -> {
                ((WasmParser().parse(buffer) as? WasmParseSuccess)?.wasmModule as? WasmComponent)?.let {
                    return createComponentWasmType(it, buffer, source)
                }
            }
            BinaryProviderType.Wasm -> {
                ((WasmParser().parse(buffer) as? WasmParseSuccess)?.wasmModule as? WasmModule)?.let {
                    return createCoreWasmType(it, buffer, source)
                }
            }
        }
        return null
    }

    private fun createCoreWasmType(module: WasmModule, buffer: CommonByteBuffer, typeSource: JSTypeSource): JSRecordTypeImpl? {
        val exportSection = module.exportSection ?: return null
        val exports = with(exportSection) { buffer.scan() }

        val typeRecords = module.typesSection?.let { with(it) { buffer.parseTypes() } } ?: emptyList()
        val funcTypeIndices = module.functionSection?.let { with(it) { buffer.parseFunctions() } } ?: emptyList()
        val importedFunctions = module.importSection?.let { sec ->
            with(sec) { buffer.getImportRecords() }.count { it.type is WasmTypeIndexExternalType }
        } ?: 0

        val properties = exports.map { export ->
            PropertySignatureImpl(
                export.name,
                mapCoreExportToJsType(export, typeRecords, funcTypeIndices, importedFunctions, typeSource),
                false, false
            )
        }
        return coreWasmShell(typeSource, properties)
    }

    private fun coreWasmShell(typeSource: JSTypeSource, exportProperties: List<PropertySignatureImpl>): JSRecordTypeImpl {
        return JSRecordTypeImpl(
            typeSource,
            listOf(
                PropertySignatureImpl(
                    "instance",
                    JSRecordTypeImpl(
                        typeSource,
                        listOf(PropertySignatureImpl("exports",
                            JSContextualUnionTypeImpl(typeSource, listOf(
                                JSRecordTypeImpl(typeSource, exportProperties),
                                JSNamedTypeFactory.createType("WebAssembly.Instance.Exports", typeSource, JSTypeContext.INSTANCE, false),
                            ))
                            , false, false))
                    ),
                    false, false
                ),
                PropertySignatureImpl(
                    "module",
                    JSNamedTypeFactory.createType("WebAssembly.Module", typeSource, JSTypeContext.INSTANCE, false),
                    false, false
                )
            )
        )
    }

    private fun mapCoreExportToJsType(
        export: WasmExportRecordEntry,
        typeRecords: List<WasmTypeRecord>,
        funcTypeIndices: List<Int>,
        importedFunctions: Int,
        source: JSTypeSource
    ): JSType {
        return when (export) {
            is WasmFunctionExportRecordEntry -> {
                val localIndex = export.index.toInt() - importedFunctions
                val typeIdx = funcTypeIndices.getOrNull(localIndex) ?: return JSAnyType.get(source)
                val funcType = typeRecords.getOrNull(typeIdx) as? WasmSectionFunctionTypeRecord ?: return JSAnyType.get(source)
                mapCoreFuncTypeToJs(funcType, source)
            }
            is WasmMemoryExportRecordEntry -> JSNamedTypeFactory.createType("WebAssembly.Memory", source, JSTypeContext.INSTANCE, false)
            is WasmTableExportRecordEntry -> JSNamedTypeFactory.createType("WebAssembly.Table", source, JSTypeContext.INSTANCE, false)
            is WasmGlobalExportRecordEntry -> JSNamedTypeFactory.createType("WebAssembly.Global", source, JSTypeContext.INSTANCE, false)
            is WasmTagExportRecordEntry -> JSNamedTypeFactory.createType("WebAssembly.Tag", source, JSTypeContext.INSTANCE, false)
            is WasmUnsupportedExportRecordEntry -> JSAnyType.get(source)
        }
    }

    private fun mapCoreFuncTypeToJs(funcType: WasmSectionFunctionTypeRecord, source: JSTypeSource): JSType {
        val params = funcType.params.mapIndexed { index, valueType ->
            JSParameterTypeDecoratorImpl("p$index", mapWasmValueTypeToJsType(valueType, source), false, false, true)
        }
        val returnType = when (funcType.retTypes.size) {
            0 -> JSNamedTypeFactory.createType("void", source, JSTypeContext.INSTANCE, false)
            1 -> mapWasmValueTypeToJsType(funcType.retTypes.first(), source)
            else -> JSArrayTypeImpl(
                JSContextualUnionTypeImpl(source, funcType.retTypes.map { mapWasmValueTypeToJsType(it, source) }),
                source
            )
        }
        return JSFunctionTypeImpl(source, params, returnType)
    }

    private fun mapWasmValueTypeToJsType(valueType: WasmValueType<*>, source: JSTypeSource): JSType {
        return when (valueType) {
            is WasmI32Type, is WasmF32Type, is WasmF64Type -> JSNamedTypeFactory.createType("number", source, JSTypeContext.INSTANCE, false)
            is WasmI64Type -> JSNamedTypeFactory.createType("bigint", source, JSTypeContext.INSTANCE, false)
            is WasmVecType -> JSNamedTypeFactory.createType("v128", source, JSTypeContext.INSTANCE, false)
            is WasmRefType -> JSAnyType.get(source)
            else -> JSAnyType.get(source)
        }
    }

    private fun createComponentWasmType(component: WasmComponent, buffer: CommonByteBuffer,
                                        typeSource: JSTypeSource): JSRecordTypeImpl {
        val allTypes = buildComponentTypeIndex(component.sections, buffer)

        val allComponentInstances = component.instanceSection.flatMap {
            with(it) {
                buffer.parseInstances()
            }
        }

        return JSRecordTypeImpl(
            typeSource,
            listOf(
                PropertySignatureImpl(
                    "instance",
                    JSRecordTypeImpl(
                        typeSource,
                        listOf(PropertySignatureImpl("exports",
                            JSContextualUnionTypeImpl(typeSource, listOf(
                                JSRecordTypeImpl(typeSource, component.exportSection.flatMap {
                                    with(it) {
                                        buffer.scan()
                                    }.map { export ->
                                        PropertySignatureImpl(export.exportName
                                            .substringAfter('/').substringBefore('@'),
                                            resolveExportType(export, allTypes,
                                                allComponentInstances, typeSource), false, false)
                                    }
                                }),
                                JSNamedTypeFactory.createType("WebAssembly.Instance.Exports", typeSource, JSTypeContext.INSTANCE, false),
                            ))
                            , false, false))
                    ),
                    false, false
                ),
                PropertySignatureImpl(
                    "module",
                    JSNamedTypeFactory.createType("WebAssembly.Module", typeSource, JSTypeContext.INSTANCE, false),
                    false, false
                )
            )
        )
    }

    private fun resolveExportType(export: WasmComponentExport, allTypes: List<ComponentDefType>,
                                  allComponentInstances: List<InstanceExpression>, source: JSTypeSource): JSType {
        val defType = resolveDefType(export, allTypes, allComponentInstances) ?: return JSAnyType.get(source)
        val jsType = mapComponentDefTypeToJsType(defType, allTypes, source, mutableSetOf())
        if (jsType is JSRecordTypeImpl && (defType is ComponentType || defType is ComponentInstanceType)) {
            val exportName = export.exportName.substringAfter('/').substringBefore('@')
            val matchedType = jsType.properties.singleOrNull { it.memberName == exportName }?.jsType
            if (matchedType != null) {
                return matchedType
            }
        }
        return jsType
    }

    private fun resolveDefType(export: WasmComponentExport, allTypes: List<ComponentDefType>,
                               allComponentInstances: List<InstanceExpression>
    ): ComponentDefType? {
        val externDesc = export.externDescription
        if (externDesc != null) {
            return when (externDesc) {
                is ComponentExternType -> allTypes.getOrNull(externDesc.idx.toInt())
                is ComponentExternCoreType -> allTypes.getOrNull(externDesc.idx.toInt())
                is ComponentExternComponent -> allTypes.getOrNull(externDesc.idx.toInt())
                is ComponentExternInstance -> allTypes.getOrNull(externDesc.idx.toInt())
                else -> null
            }
        }
        return when (export.sortIndex.sort) {
            is ComponentSort.Type -> allTypes.getOrNull(export.sortIndex.index.toInt())
            is ComponentSort.Instance -> {
                val resolved = resolveComponentInstanceType(export.sortIndex.index, allTypes, allComponentInstances)
                if (resolved != null) return resolved
                // The instance index space includes entries from alias sections,
                // so the export's sort index may exceed the instance section's own list.
                // When the instance lookup fails but the export name matches a WIT interface pattern,
                // fall back to the first routine type from the parent component's type definitions.
                if (looksLikeWitInterfaceExport(export.exportName)) {
                    allTypes.filterIsInstance<ComponentRoutineType>().firstOrNull()
                } else null
            }
            is ComponentSort.Func -> resolveComponentFuncType(export.sortIndex.index, allTypes
            )
            is ComponentSort.Component,
            is ComponentSort.Value,
            is ComponentSortWithCoreSort -> null
        }
    }

    private fun resolveComponentInstanceType(instanceIndex: UInt, allTypes: List<ComponentDefType>, allComponentInstances: List<InstanceExpression>): ComponentDefType? {
        return when (val instance = allComponentInstances.getOrNull(instanceIndex.toInt())) {
            is ComponentInstanceExpression -> {
                allTypes.getOrNull(instance.index.toInt())
            }
            is InlineInstanceExpression -> {
                val exportDeclarations = instance.e.mapNotNull { inlineExport ->
                    resolveComponentInlineExportToDeclaration(inlineExport)
                }
                ComponentInstanceType(exportDeclarations)
            }
            null -> null
        }
    }

    private fun resolveComponentInlineExportToDeclaration(inlineExport: InstanceInlineExport): ExportDeclaration? {
        return when (inlineExport) {
            is FuncInstanceInlineExport -> ExportDeclaration(inlineExport.name, ComponentExternType(inlineExport.index))
            is TypeInstanceInlineExport -> ExportDeclaration(inlineExport.name, ComponentExternType(inlineExport.index))
            is InstanceInlineExportExpression -> ExportDeclaration(inlineExport.name, ComponentExternInstance(inlineExport.index))
            is ComponentInstanceInlineExport -> ExportDeclaration(inlineExport.name, ComponentExternComponent(inlineExport.index))
            is CoreInstanceInlineExport,
            is ValueInstanceInlineExport -> null
        }
    }

    private fun resolveComponentFuncType(funcIndex: UInt, allTypes: List<ComponentDefType>
    ): ComponentDefType? {
        return allTypes.getOrNull(funcIndex.toInt())
    }

    private fun mapComponentDefTypeToJsType(defType: ComponentDefType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        return when (defType) {
            is ComponentRoutineType -> mapFunctionTypeToJs(defType, allTypes, source, visited)
            is ComponentInstanceType -> mapInstanceTypeToJs(defType, allTypes, source, visited)
            is ComponentPrimitiveType -> mapPrimitiveToJs(defType, source)
            is ComponentValueIdxType -> {
                val idx = defType.idx.toInt()
                if (!visited.add(idx)) return JSAnyType.get(source)
                val resolved = allTypes.getOrNull(idx)
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source, visited) else JSAnyType.get(source)
            }
            is ComponentPrimitiveValueType -> mapPrimitiveToJs(defType.type, source)
            is ComponentRecordType -> mapRecordTypeToJs(defType, allTypes, source, visited)
            is ComponentListType -> mapListTypeToJs(defType, allTypes, source, visited)
            is ComponentOptionType -> mapOptionTypeToJs(defType, allTypes, source, visited)
            is ComponentResultType -> mapResultTypeToJs(defType, allTypes, source, visited)
            is ComponentTupleType -> mapTupleTypeToJs(defType, allTypes, source, visited)
            is ComponentEnumType -> mapEnumTypeToJs(defType, source)
            is ComponentFlagType -> mapFlagTypeToJs(defType, source)
            is ComponentVariantType -> mapVariantTypeToJs(defType, allTypes, source, visited)
            is ComponentType -> mapComponentTypeToJs(defType, allTypes, source, visited)
            is ComponentOwnType -> JSAnyType.get(source)
            is ComponentBorrowType -> JSAnyType.get(source)
            is ComponentResourceType -> JSAnyType.get(source)
            is ComponentStreamType -> JSAnyType.get(source)
            is ComponentFutureType -> JSAnyType.get(source)
            is ComponentArrayType -> mapArrayTypeToJs(defType, allTypes, source, visited)
            is ComponentAliasedType -> {
                val target = defType.alias.aliasTarget
                if (target is AliasTarget.Outer) {
                    val idx = target.outerIndex.toInt()
                    if (!visited.add(idx)) return JSAnyType.get(source)
                    val resolved = allTypes.getOrNull(idx)
                    if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source, visited) else JSAnyType.get(source)
                } else {
                    JSAnyType.get(source)
                }
            }
            is ComponentImportedType -> resolveExternDescriptionType(defType.import.externDescription, allTypes, source, visited)
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

    private fun mapFunctionTypeToJs(funcType: ComponentRoutineType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        val paramTypes = funcType.params.map { param ->
            PropertySignatureImpl(param.label, mapValueTypeToJs(param.value, allTypes, source, visited), false, false)
        }
        val returnType = mapReturnTypeToJs(funcType.ret, allTypes, source, visited)
        return JSFunctionTypeImpl(source, paramTypes.map { JSParameterTypeDecoratorImpl(it.memberName, it.jsType, false, false, true) }, returnType)
    }

    private fun mapValueTypeToJs(valueType: ComponentValueType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        return when (valueType) {
            is ComponentPrimitiveValueType -> mapPrimitiveToJs(valueType.type, source)
            is ComponentValueIdxType -> {
                val idx = valueType.idx.toInt()
                if (!visited.add(idx)) return JSAnyType.get(source)
                val resolved = allTypes.getOrNull(idx)
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source, visited) else JSAnyType.get(source)
            }
        }
    }

    private fun mapReturnTypeToJs(returnType: ComponentReturnType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        return when (returnType) {
            is ComponentVoidType -> JSNamedTypeFactory.createType("void", source, JSTypeContext.INSTANCE, false)
            is ComponentValueType -> mapValueTypeToJs(returnType, allTypes, source, visited)
            else -> JSAnyType.get(source)
        }
    }

    private fun mapRecordTypeToJs(recordType: ComponentRecordType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        val properties = recordType.fields.map { field ->
            PropertySignatureImpl(field.label, mapValueTypeToJs(field.value, allTypes, source, visited), false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun mapListTypeToJs(listType: ComponentListType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        val elementType = mapValueTypeToJs(listType.elementType, allTypes, source, visited)
        return JSArrayTypeImpl(elementType, source)
    }

    private fun mapArrayTypeToJs(arrayType: ComponentArrayType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        val elementType = mapValueTypeToJs(arrayType.elementType, allTypes, source, visited)
        return JSArrayTypeImpl(elementType, source)
    }

    private fun mapOptionTypeToJs(optionType: ComponentOptionType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        val innerType = mapValueTypeToJs(optionType.option, allTypes, source, visited)
        val undefinedType = JSNamedTypeFactory.createType("undefined", source, JSTypeContext.INSTANCE, false)
        return JSContextualUnionTypeImpl(source, listOf(innerType, undefinedType))
    }

    private fun mapResultTypeToJs(resultType: ComponentResultType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        val okType = resultType.result?.let { mapValueTypeToJs(it, allTypes, source, visited) }
            ?: JSNamedTypeFactory.createType("void", source, JSTypeContext.INSTANCE, false)
        val errType = resultType.error?.let { mapValueTypeToJs(it, allTypes, source, visited) }
        return if (errType != null) {
            JSContextualUnionTypeImpl(source, listOf(okType, errType))
        } else {
            okType
        }
    }

    private fun mapTupleTypeToJs(tupleType: ComponentTupleType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        val elementTypes = tupleType.elementTypes.map { mapValueTypeToJs(it, allTypes, source, visited) }
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

    private fun mapFlagTypeToJs(flagType: ComponentFlagType, source: JSTypeSource): JSType {
        val properties = flagType.flags.map { flag ->
            PropertySignatureImpl(flag, JSNamedTypeFactory.createType("boolean", source, JSTypeContext.INSTANCE, false), false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun mapVariantTypeToJs(variantType: ComponentVariantType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        val caseTypes = variantType.variants.map { variant ->
            val tagProperty = PropertySignatureImpl("tag", JSStringLiteralTypeImpl(variant.label, false, source), false, false)
            val properties = mutableListOf(tagProperty)
            val variantValue = variant.value
            if (variantValue != null) {
                properties.add(PropertySignatureImpl("val", mapValueTypeToJs(variantValue, allTypes, source, visited), false, false))
            }
            JSRecordTypeImpl(source, properties)
        }
        return JSContextualUnionTypeImpl(source, caseTypes)
    }

    private fun mapComponentTypeToJs(componentType: ComponentType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        val exportDeclarations = componentType.cd.mapNotNull { decl ->
            when (decl) {
                is ComponentInstanceDeclaration -> decl.id as? ExportDeclaration
                else -> null
            }
        }
        val properties = exportDeclarations.map { exportDecl ->
            val memberType = resolveExternDescriptionType(exportDecl.ed, allTypes, source, visited)
            PropertySignatureImpl(exportDecl.exportName.substringAfter('/').substringBefore('@'), memberType, false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun mapInstanceTypeToJs(instanceType: ComponentInstanceType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        val properties = instanceType.cd.filterIsInstance<ExportDeclaration>().map { exportDecl ->
            val memberType = resolveExternDescriptionType(exportDecl.ed, allTypes, source, visited)
            PropertySignatureImpl(exportDecl.exportName.substringAfter('/').substringBefore('@'), memberType, false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun resolveExternDescriptionType(ed: ComponentExternDescription, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
        return when (ed) {
            is ComponentExternType -> {
                val idx = ed.idx.toInt()
                if (!visited.add(idx)) return JSAnyType.get(source)
                val resolved = allTypes.getOrNull(idx)
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source, visited) else JSAnyType.get(source)
            }
            is ComponentExternCoreType -> {
                val idx = ed.idx.toInt()
                if (!visited.add(idx)) return JSAnyType.get(source)
                val resolved = allTypes.getOrNull(idx)
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source, visited) else JSAnyType.get(source)
            }
            is ComponentExternValueBound -> {
                when (val bound = ed.bound) {
                    is ValueBoundType -> {
                        val idx = bound.idx.toInt()
                        if (!visited.add(idx)) return JSAnyType.get(source)
                        val resolved = allTypes.getOrNull(idx)
                        if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source, visited) else JSAnyType.get(source)
                    }
                    is ValueBoundValue -> mapValueTypeToJs(bound.type, allTypes, source, visited)
                }
            }
            is ComponentExternTypeBound -> JSAnyType.get(source)
            is ComponentExternComponent -> {
                val idx = ed.idx.toInt()
                if (!visited.add(idx)) return JSAnyType.get(source)
                val resolved = allTypes.getOrNull(idx)
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source, visited) else JSAnyType.get(source)
            }
            is ComponentExternInstance -> {
                val idx = ed.idx.toInt()
                if (!visited.add(idx)) return JSAnyType.get(source)
                val resolved = allTypes.getOrNull(idx)
                if (resolved != null) mapComponentDefTypeToJsType(resolved, allTypes, source, visited) else JSAnyType.get(source)
            }
        }
    }

}
