package org.jetbrains.hexana.javascript

import com.intellij.lang.javascript.JSStringUtil
import com.intellij.lang.javascript.index.FrameworkIndexingHandler
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecma6.TypeScriptModule
import com.intellij.lang.javascript.psi.resolve.JSEvaluateContext
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil
import com.intellij.lang.javascript.psi.resolve.JSTypeInfo
import com.intellij.lang.javascript.psi.stubs.impl.JSImplicitElementImpl
import com.intellij.lang.javascript.psi.types.*
import com.intellij.lang.javascript.psi.types.recordImpl.PropertySignatureImpl
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.contextOfType
import com.intellij.psi.util.descendantsOfType
import org.jetbrains.hexana.shared.looksLikeWitInterfaceExport
import org.jetbrains.hexana.wasmcache.ComponentWasmData
import org.jetbrains.hexana.wasmcache.CoreWasmData
import org.jetbrains.hexana.wasmcache.ParsedWasmData
import org.jetbrains.hexana.wasmcache.WasmBinaryDataCacheService
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.hexana.wasm.sections.*

class WasmFrameworkIndexingHandler: FrameworkIndexingHandler() {
    private val instantiateCallNames = setOf("instantiateStreaming", "instantiate")
    private val compileCallNames = setOf("compileStreaming", "compile")
    private val allAcceptedCallNames = instantiateCallNames + compileCallNames

    override fun addContextType(info: JSTypeInfo?, context: PsiElement?) {
        if (info == null || context == null) return
        val parent = context.context
        if (parent !is JSObjectLiteralExpression) return
        // sadly, webstorm type inference doesn't automatically support
        //  contextual type evaluation from nested properties,
        //  so we have to do that here manually
        val nameStack = ArrayDeque<String>()
        var current = parent
        while (current?.context is JSProperty) {
            nameStack.add((current.context as JSProperty).name ?: "")
            current = current.context?.context
        }
        val args = current?.context as? JSArgumentList ?: return
        if (args.arguments.indexOf(current) != 1) return
        val call = args.context as? JSCallExpression ?: return
        if (!instantiateCallNames.contains((call.stubSafeMethodExpression as? JSReferenceExpression)?.referenceName)) return
        val typeSource = JSTypeSource(context, JSTypeSource.SourceLanguage.JS, false)
        var importsType = resolveImportsType(typeSource, call)
        if (importsType != null) {
            while (!nameStack.isEmpty()) {
                importsType = importsType?.findPropertySignature(nameStack.removeLast())?.jsType as? JSRecordType ?: return
            }
            info.addRecordType(importsType)
        }
        super.addContextType(info, context)
    }

    override fun adjustTypeBeforeAdd(type: JSType, context: JSEvaluateContext): JSType {
        if (type !is JSGenericTypeImpl || type.arguments.size != 1) return type
        val typeSource = type.source
        if ((type.type as? JSTypeImpl)?.qualifiedName?.qualifiedName != "Promise") return type
        val processedType = type.arguments.first() as? JSTypeImpl ?: return type
        if (!isAcceptableType(processedType)) return type
        val call = context.processedExpression?.context as? JSCallExpression ?: return type
        val wasmType = resolveExportsType(typeSource, call)
        if (wasmType != null) {
            return JSGenericTypeImpl(typeSource, type.type, listOf(wasmType))
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

    // we can only provide inference in statically possible cases
    //  i.e., when we have the name of the wasm file directly specified somewhere
    private fun <T> handleStaticallyPossibleCases(callExpression: JSCallExpression,
                                              handleType: (JSType) -> T?,
                                              handleLiteral: (String) -> T?): T? {
        val firstArg = callExpression.arguments.firstOrNull() ?: return null

        // if we have a wasm file path directly specified, just use it
        val wasmLiteral = findWasmLiteral(firstArg)
        if (wasmLiteral != null) {
            return handleLiteral(wasmLiteral)
        }

        // if we can infer information from the first argument
        //  (i.e., it's already an inferred type), use it
        val resolvedType = JSResolveUtil.getExpressionJSType(firstArg) ?: return null
        handleType(resolvedType)?.let { return it }

        // last resort - hack for instantiating from bytes - try to infer from the call chain
        if (resolvedType is JSTypeImpl && resolvedType.qualifiedName.qualifiedName == "ArrayBuffer") {
            callExpression.contextOfType<JSCallExpression>()?.descendantsOfType<JSCallExpression>()
                ?.firstOrNull { (it.stubSafeMethodExpression as? JSReferenceExpression)?.referenceName == "fetch" }
                ?.arguments?.firstOrNull()?.let { arg ->
                    val wasmLiteral = findWasmLiteral(arg)
                    if (wasmLiteral != null) {
                        return handleLiteral(wasmLiteral)
                    }
                }
        }
        return null
    }

    private fun resolveExportsType(typeSource: JSTypeSource, expr: JSCallExpression): JSType? {
        val callee = (expr.stubSafeMethodExpression as? JSReferenceExpression)?.referenceName
        if (!allAcceptedCallNames.contains(callee)) return null
        return handleStaticallyPossibleCases(expr, {
            val type = when (it) {
                is WasmWrapperType -> createFromLiteral(expr, it.wasmFilePath, typeSource)
                else -> it
            }
            (type as? JSRecordType)?.properties?.firstOrNull { it.memberName == "instance" }?.jsType
        }, { wasmLiteral ->
            if (compileCallNames.contains(callee)) {
                createModuleWrapperFromLiteral(wasmLiteral, typeSource)
            } else {
                createFromLiteral(expr, wasmLiteral, typeSource)
            }
        })
    }

    private fun resolveImportsType(typeSource: JSTypeSource, call: JSCallExpression): JSRecordType? {
        val parsedData = handleStaticallyPossibleCases(call, {
            (it as? WasmWrapperType)?.let {
                resolveWasmDataFromLiteral(call, it.wasmFilePath)
            }
        }, { resolveWasmDataFromLiteral(call, it) }) ?: return null
        return createImportsTypeFromParsedData(parsedData, typeSource)
    }

    private fun resolveWasmDataFromLiteral(expr: JSCallExpression, wasmLiteral: String): ParsedWasmData? {
        return expr.containingFile.originalFile.virtualFile?.parent?.findFileByRelativePath(wasmLiteral)?.let { wasmFile ->
            WasmBinaryDataCacheService.getInstance().getOrParseBinary(wasmFile)
        }
    }

    private fun createImportsTypeFromParsedData(data: ParsedWasmData, source: JSTypeSource): JSRecordTypeImpl? {
        return when (data) {
            is CoreWasmData -> createCoreWasmImportsType(data, source)
            is ComponentWasmData -> null
        }
    }

    private fun createCoreWasmImportsType(data: CoreWasmData, typeSource: JSTypeSource): JSRecordTypeImpl? {
        if (data.imports.isEmpty()) return null
        val grouped = data.imports.groupBy { it.prefix }
        val moduleProperties = grouped.map { (moduleName, imports) ->
            val importProperties = imports.map { import ->
                PropertySignatureImpl(
                    import.name,
                    mapCoreImportToJsType(import, data.typeRecords, typeSource),
                    false, false
                )
            }
            PropertySignatureImpl(
                moduleName,
                JSSimpleRecordTypeImpl(typeSource, importProperties),
                false, false
            )
        }
        return JSSimpleRecordTypeImpl(typeSource, moduleProperties)
    }

    private fun mapCoreImportToJsType(
        import: WasmImportRecord,
        typeRecords: List<WasmTypeRecord>,
        source: JSTypeSource
    ): JSType {
        return when (val type = import.type) {
            is WasmTypeIndexExternalType -> {
                val funcType = typeRecords.getOrNull(type.index.toInt()) as? WasmSectionFunctionTypeRecord
                    ?: return JSAnyType.get(source)
                mapCoreFuncTypeToJs(funcType, source)
            }
            is WasmTableExternalType -> JSNamedTypeFactory.createType("WebAssembly.Table", source, JSTypeContext.INSTANCE, false)
            is WasmMemType -> JSNamedTypeFactory.createType("WebAssembly.Memory", source, JSTypeContext.INSTANCE, false)
            is WasmGlobalType -> JSNamedTypeFactory.createType("WebAssembly.Global", source, JSTypeContext.INSTANCE, false)
            is WasmTagType -> JSNamedTypeFactory.createType("WebAssembly.Tag", source, JSTypeContext.INSTANCE, false)
        }
    }

    private fun createFromLiteral(
        expr: JSCallExpression,
        wasmLiteral: String,
        typeSource: JSTypeSource
    ): JSType? = expr.containingFile.originalFile.virtualFile?.parent?.findFileByRelativePath(wasmLiteral)?.let { wasmFile ->
        val parsedData = WasmBinaryDataCacheService.getInstance().getOrParseBinary(wasmFile) ?: return@let null
        createTypeFromParsedData(parsedData, JSTypeSource(
            wasmFile.findPsiFile(expr.project),
            typeSource.language,
            typeSource.isStrict
        ))
    }

    private fun createModuleWrapperFromLiteral(
        wasmLiteral: String,
        typeSource: JSTypeSource
    ): JSType {
        val moduleType = JSNamedTypeFactory.createType("WebAssembly.Module", typeSource, JSTypeContext.INSTANCE, false)
        return WasmWrapperType(moduleType, wasmLiteral, typeSource)
    }

    private fun createTypeFromParsedData(data: ParsedWasmData, source: JSTypeSource): JSType? {
        return when (data) {
            is CoreWasmData -> createCoreWasmTypeFromData(data, source)
            is ComponentWasmData -> createComponentWasmTypeFromData(data, source)
        }
    }

    private fun findWasmLiteral(arg: JSExpression): String? =
        SyntaxTraverser.psiTraverser(arg).filterIsInstance<JSLiteralExpression>().map { it.value }
            .firstOrNull { it is String && it.contains(".wasm") }?.let { it as? String }?.let {
                JSStringUtil.unquoteAndUnescapeString(it)
            }

    private fun createCoreWasmTypeFromData(data: CoreWasmData, typeSource: JSTypeSource): JSRecordTypeImpl {
        val properties = data.exports.map { export ->
            PropertySignatureImpl(
                export.name,
                mapCoreExportToJsType(export, data.typeRecords, data.funcTypeIndices, data.importedFunctions,
                    JSTypeSource(WasmImplicitElementImpl(export.name, typeSource.sourceElement), typeSource.language, typeSource.isStrict)),
                false, false
            )
        }
        return createWasmModuleRecordType(typeSource, properties)
    }

    private fun createWasmModuleRecordType(typeSource: JSTypeSource, exportProperties: List<PropertySignatureImpl>): JSRecordTypeImpl {
        return JSRecordTypeImpl(
            typeSource,
            listOf(
                PropertySignatureImpl(
                    "instance",
                    JSRecordTypeImpl(
                        typeSource,
                        listOf(PropertySignatureImpl("exports",
                            JSCompositeTypeFactory.createContextualUnionType(
                                listOf(
                                JSRecordTypeImpl(typeSource, exportProperties),
                                JSNamedTypeFactory.createType("WebAssembly.Instance.Exports", typeSource, JSTypeContext.INSTANCE, false),
                            ), typeSource)
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
                JSCompositeTypeFactory.createContextualUnionType(
                    funcType.retTypes.map { mapWasmValueTypeToJsType(it, source) },
                    source
                ),
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

    private fun createComponentWasmTypeFromData(data: ComponentWasmData, typeSource: JSTypeSource): JSRecordTypeImpl {
        return createWasmModuleRecordType(typeSource, data.exports.map { export ->
            PropertySignatureImpl(
                export.exportName.substringAfter('/').substringBefore('@'),
                resolveExportType(
                    export, data.allTypes,
                    data.allComponentInstances, typeSource
                ), false, false
            )
        })
    }

    private fun resolveExportType(export: WasmComponentExport, allTypes: List<ComponentDefType>,
                                  allComponentInstances: List<InstanceExpression>, source: JSTypeSource): JSType {
        val defType = resolveDefType(export, allTypes, allComponentInstances) ?: return JSAnyType.get(source)
        val jsType = mapComponentDefTypeToJsType(defType, allTypes, source, mutableMapOf())
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

    private fun mapComponentDefTypeToJsType(defType: ComponentDefType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        return when (defType) {
            is ComponentRoutineType -> mapFunctionTypeToJs(defType, allTypes, source, cache)
            is ComponentInstanceType -> mapInstanceTypeToJs(defType, allTypes, source, cache)
            is ComponentPrimitiveType -> mapPrimitiveToJs(defType, source)
            is ComponentValueIdxType -> {
                resolveIndexedType(defType.idx.toInt(), allTypes, source, cache)
            }
            is ComponentPrimitiveValueType -> mapPrimitiveToJs(defType.type, source)
            is ComponentRecordType -> mapRecordTypeToJs(defType, allTypes, source, cache)
            is ComponentListType -> mapListTypeToJs(defType, allTypes, source, cache)
            is ComponentOptionType -> mapOptionTypeToJs(defType, allTypes, source, cache)
            is ComponentResultType -> mapResultTypeToJs(defType, allTypes, source, cache)
            is ComponentTupleType -> mapTupleTypeToJs(defType, allTypes, source, cache)
            is ComponentEnumType -> mapEnumTypeToJs(defType, source)
            is ComponentFlagType -> mapFlagTypeToJs(defType, source)
            is ComponentVariantType -> mapVariantTypeToJs(defType, allTypes, source, cache)
            is ComponentType -> mapComponentTypeToJs(defType, allTypes, source, cache)
            is ComponentOwnType -> JSAnyType.get(source)
            is ComponentBorrowType -> JSAnyType.get(source)
            is ComponentResourceType -> JSAnyType.get(source)
            is ComponentStreamType -> JSAnyType.get(source)
            is ComponentFutureType -> JSAnyType.get(source)
            is ComponentArrayType -> mapArrayTypeToJs(defType, allTypes, source, cache)
            is ComponentAliasedType -> {
                val target = defType.alias.aliasTarget
                if (target is AliasTarget.Outer) {
                    resolveIndexedType(target.outerIndex.toInt(), allTypes, source, cache)
                } else {
                    JSAnyType.get(source)
                }
            }
            is ComponentImportedType -> resolveExternDescriptionType(defType.import.externDescription, allTypes, source, cache)
        }
    }

    private fun resolveIndexedType(idx: Int, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        cache[idx]?.let { cached ->
            @Suppress("SuspiciousEqualsCombination")
            return if (cached === SENTINEL_TYPE) JSAnyType.get(source) else cached
        }
        val resolved = allTypes.getOrNull(idx) ?: return JSAnyType.get(source)
        cache[idx] = SENTINEL_TYPE
        val result = mapComponentDefTypeToJsType(resolved, allTypes, source, cache)
        cache[idx] = result
        return result
    }

    companion object {
        private val SENTINEL_TYPE: JSType = JSAnyType.get(JSTypeSource.EMPTY)
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

    private fun mapFunctionTypeToJs(funcType: ComponentRoutineType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        val paramTypes = funcType.params.map { param ->
            PropertySignatureImpl(param.label, mapValueTypeToJs(param.value, allTypes, source, cache), false, false)
        }
        val returnType = mapReturnTypeToJs(funcType.ret, allTypes, source, cache)
        return JSFunctionTypeImpl(source, paramTypes.map { JSParameterTypeDecoratorImpl(it.memberName, it.jsType, false, false, true) }, returnType)
    }

    private fun mapValueTypeToJs(valueType: ComponentValueType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        return when (valueType) {
            is ComponentPrimitiveValueType -> mapPrimitiveToJs(valueType.type, source)
            is ComponentValueIdxType -> {
                resolveIndexedType(valueType.idx.toInt(), allTypes, source, cache)
            }
        }
    }

    private fun mapReturnTypeToJs(returnType: ComponentReturnType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        return when (returnType) {
            is ComponentVoidType -> JSNamedTypeFactory.createType("void", source, JSTypeContext.INSTANCE, false)
            is ComponentValueType -> mapValueTypeToJs(returnType, allTypes, source, cache)
            else -> JSAnyType.get(source)
        }
    }

    private fun mapRecordTypeToJs(recordType: ComponentRecordType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        val properties = recordType.fields.map { field ->
            PropertySignatureImpl(field.label, mapValueTypeToJs(field.value, allTypes, source, cache), false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun mapListTypeToJs(listType: ComponentListType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        val elementType = mapValueTypeToJs(listType.elementType, allTypes, source, cache)
        return JSArrayTypeImpl(elementType, source)
    }

    private fun mapArrayTypeToJs(arrayType: ComponentArrayType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        val elementType = mapValueTypeToJs(arrayType.elementType, allTypes, source, cache)
        return JSArrayTypeImpl(elementType, source)
    }

    private fun mapOptionTypeToJs(optionType: ComponentOptionType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        val innerType = mapValueTypeToJs(optionType.option, allTypes, source, cache)
        val undefinedType = JSNamedTypeFactory.createType("undefined", source, JSTypeContext.INSTANCE, false)
        return JSCompositeTypeFactory.createContextualUnionType(listOf(innerType, undefinedType), source)
    }

    private fun mapResultTypeToJs(resultType: ComponentResultType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        val okType = resultType.result?.let { mapValueTypeToJs(it, allTypes, source, cache) }
            ?: JSNamedTypeFactory.createType("void", source, JSTypeContext.INSTANCE, false)
        val errType = resultType.error?.let { mapValueTypeToJs(it, allTypes, source, cache) }
        return if (errType != null) {
            JSCompositeTypeFactory.createContextualUnionType(listOf(okType, errType), source)
        } else {
            okType
        }
    }

    private fun mapTupleTypeToJs(tupleType: ComponentTupleType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        val elementTypes = tupleType.elementTypes.map { mapValueTypeToJs(it, allTypes, source, cache) }
        val unionType = if (elementTypes.size == 1) elementTypes.first()
        else JSCompositeTypeFactory.createContextualUnionType(elementTypes, source)
        return JSArrayTypeImpl(unionType, source)
    }

    private fun mapEnumTypeToJs(enumType: ComponentEnumType, source: JSTypeSource): JSType {
        val literalTypes = enumType.entries.map { entry ->
            JSStringLiteralTypeImpl(entry, false, source)
        }
        return JSCompositeTypeFactory.createContextualUnionType(literalTypes, source)
    }

    private fun mapFlagTypeToJs(flagType: ComponentFlagType, source: JSTypeSource): JSType {
        val properties = flagType.flags.map { flag ->
            PropertySignatureImpl(flag, JSNamedTypeFactory.createType("boolean", source, JSTypeContext.INSTANCE, false), false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun mapVariantTypeToJs(variantType: ComponentVariantType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        val caseTypes = variantType.variants.map { variant ->
            val tagProperty = PropertySignatureImpl("tag", JSStringLiteralTypeImpl(variant.label, false, source), false, false)
            val properties = mutableListOf(tagProperty)
            val variantValue = variant.value
            if (variantValue != null) {
                properties.add(PropertySignatureImpl("val", mapValueTypeToJs(variantValue, allTypes, source, cache), false, false))
            }
            JSRecordTypeImpl(source, properties)
        }
        return JSCompositeTypeFactory.createContextualUnionType(caseTypes, source)
    }

    private fun mapComponentTypeToJs(componentType: ComponentType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        val exportDeclarations = componentType.cd.mapNotNull { decl ->
            when (decl) {
                is ComponentInstanceDeclaration -> decl.id as? ExportDeclaration
                else -> null
            }
        }
        val properties = exportDeclarations.map { exportDecl ->
            val memberType = resolveExternDescriptionType(exportDecl.ed, allTypes, source, cache)
            PropertySignatureImpl(exportDecl.exportName.substringAfter('/').substringBefore('@'), memberType, false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun mapInstanceTypeToJs(instanceType: ComponentInstanceType, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        val properties = instanceType.cd.filterIsInstance<ExportDeclaration>().map { exportDecl ->
            val memberType = resolveExternDescriptionType(exportDecl.ed, allTypes, source, cache)
            PropertySignatureImpl(exportDecl.exportName.substringAfter('/').substringBefore('@'), memberType, false, false)
        }
        return JSRecordTypeImpl(source, properties)
    }

    private fun resolveExternDescriptionType(ed: ComponentExternDescription, allTypes: List<ComponentDefType>, source: JSTypeSource, cache: MutableMap<Int, JSType>): JSType {
        return when (ed) {
            is ComponentExternType -> resolveIndexedType(ed.idx.toInt(), allTypes, source, cache)
            is ComponentExternCoreType -> resolveIndexedType(ed.idx.toInt(), allTypes, source, cache)
            is ComponentExternValueBound -> {
                when (val bound = ed.bound) {
                    is ValueBoundType -> resolveIndexedType(bound.idx.toInt(), allTypes, source, cache)
                    is ValueBoundValue -> mapValueTypeToJs(bound.type, allTypes, source, cache)
                }
            }
            is ComponentExternTypeBound -> JSAnyType.get(source)
            is ComponentExternComponent -> resolveIndexedType(ed.idx.toInt(), allTypes, source, cache)
            is ComponentExternInstance -> resolveIndexedType(ed.idx.toInt(), allTypes, source, cache)
        }
    }

}

class WasmImplicitElementImpl(name: String, provider: PsiElement?): JSImplicitElementImpl(name, provider)

class WasmWrapperType(
    private val delegateType: JSType,
    val wasmFilePath: String,
    source: JSTypeSource
) : JSTypeBaseImpl(source), JSWrapperType {
    override fun copyWithNewSource(source: JSTypeSource): JSType = WasmWrapperType(delegateType, wasmFilePath, source)
    override fun buildTypeTextImpl(format: JSType.TypeTextFormat, builder: JSTypeTextBuilder) {
        delegateType.buildTypeText(format, builder)
    }
    override fun acceptChildren(visitor: JSRecursiveTypeVisitor) {
        delegateType.accept(visitor)
    }
    override fun hashCodeImpl(): Int = 31 * delegateType.hashCode() + wasmFilePath.hashCode()
    override fun isEquivalentToWithSameClass(type: JSType, context: com.intellij.util.ProcessingContext?, allowResolve: Boolean): Boolean {
        return type is WasmWrapperType && type.wasmFilePath == wasmFilePath && type.delegateType.isEquivalentTo(delegateType, context, allowResolve)
    }

    override fun getOriginalType(): JSType {
        return delegateType
    }

    override fun distributeType(): JSType {
        return this
    }
}
