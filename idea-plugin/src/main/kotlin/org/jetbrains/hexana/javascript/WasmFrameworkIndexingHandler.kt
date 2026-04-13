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
import org.jetbrains.hexana.looksLikeWitInterfaceExport
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.buildComponentTypeIndex
import org.jetbrains.hexana.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
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
        val allTypes = buildComponentTypeIndex(component.sections, buffer)

        val allCoreInstances = component.coreInstanceSection.flatMap {
            with(it) {
                buffer.parseCoreInstances()
            }
        }

        val allComponentInstances = component.instanceSection.flatMap {
            with(it) {
                buffer.parseInstances()
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
                                            resolveExportType(export, allTypes, allCoreInstances, allComponentInstances, component.moduleSection, buffer, type.source), false, false)
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

    private fun resolveExportType(export: WasmComponentExport, allTypes: List<ComponentDefType>, allCoreInstances: List<InstanceCoreExpression>, allComponentInstances: List<InstanceExpression>, allModuleSections: List<WasmComponentModuleSection>, buffer: CommonByteBuffer, source: JSTypeSource): JSType {
        val defType = resolveDefType(export, allTypes, allCoreInstances, allComponentInstances, allModuleSections, buffer) ?: return JSAnyType.get(source)
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

    private fun resolveDefType(export: WasmComponentExport, allTypes: List<ComponentDefType>, allCoreInstances: List<InstanceCoreExpression>, allComponentInstances: List<InstanceExpression>, allModuleSections: List<WasmComponentModuleSection>, buffer: CommonByteBuffer): ComponentDefType? {
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
        return when (val sort = export.sortIndex.sort) {
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
            is ComponentSort.Func -> resolveComponentFuncType(export.sortIndex.index, allTypes, allCoreInstances, allModuleSections, buffer)
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

    private fun resolveComponentFuncType(funcIndex: UInt, allTypes: List<ComponentDefType>, allCoreInstances: List<InstanceCoreExpression>, allModuleSections: List<WasmComponentModuleSection>, buffer: CommonByteBuffer): ComponentDefType? {
        return allTypes.getOrNull(funcIndex.toInt())
    }

    private fun resolveCoreInstanceType(instanceIndex: UInt, allTypes: List<ComponentDefType>, allCoreInstances: List<InstanceCoreExpression>, allModuleSections: List<WasmComponentModuleSection>, buffer: CommonByteBuffer): ComponentDefType? {
        return when (val instance = allCoreInstances.getOrNull(instanceIndex.toInt())) {
            is InlineInstanceCoreExpression -> {
                val exportDeclarations = instance.e.mapNotNull { inlineExport ->
                    resolveCoreInlineExportToDeclaration(inlineExport, allTypes)
                }
                ComponentInstanceType(exportDeclarations)
            }
            is ModuleInstanceCoreExpression -> {
                val moduleSec = allModuleSections.getOrNull(instance.moduleIndex.toInt()) ?: return null
                val coreModule = with(moduleSec) { buffer.scan() }
                val moduleBuffer = buffer.slice(coreModule.contentOffset until coreModule.contentOffset + coreModule.contentSize)
                val parseResult = WasmParser().parse(moduleBuffer)
                val coreWasmModule = (parseResult as? WasmParseSuccess)?.wasmModule as? WasmModule ?: return null
                val coreExportSection = coreWasmModule.exportSection ?: return null
                val coreExports = with(coreExportSection) { moduleBuffer.scan() }
                val exportDeclarations = coreExports.map { coreExport ->
                    ExportDeclaration(coreExport.name, ComponentExternType(0u))
                }
                ComponentInstanceType(exportDeclarations)
            }
            null -> null
        }
    }

    private fun resolveCoreInlineExportToDeclaration(inlineExport: InlineInstanceCoreInlineExpression, allTypes: List<ComponentDefType>): ExportDeclaration? {
        return when (inlineExport) {
            is FuncInlineCoreExpression -> {
                ExportDeclaration(inlineExport.name, ComponentExternType(inlineExport.index))
            }
            is TypeInlineCoreExpression -> {
                ExportDeclaration(inlineExport.name, ComponentExternType(inlineExport.index))
            }
            is InstanceInlineCoreExpression -> {
                val instanceType = allTypes.getOrNull(inlineExport.index.toInt())
                if (instanceType != null) {
                    ExportDeclaration(inlineExport.name, ComponentExternInstance(inlineExport.index))
                } else null
            }
            is GlobalInlineCoreExpression,
            is MemoryInlineCoreExpression,
            is ModuleInlineCoreExpression,
            is TableInlineCoreExpression -> null
        }
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
            is ComponentFlagType -> mapFlagTypeToJs(defType, allTypes, source, visited)
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

    private fun mapFlagTypeToJs(flagType: ComponentFlagType, allTypes: List<ComponentDefType>, source: JSTypeSource, visited: MutableSet<Int>): JSType {
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
