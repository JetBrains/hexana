package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.components.*
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.hexana.wasm.sections.*

private const val DEFAULT_COMPONENT_TYPE_DEPTH = 64
private const val DEFAULT_COMPONENT_TYPE_NODES = 10_000

/** Returns a flat, serializable component type graph; the parser model remains private to the result. */
fun WasmComponentBinaryProvider.readComponentTypeGraph(
    maxDepth: Int = DEFAULT_COMPONENT_TYPE_DEPTH,
    maxNodes: Int = DEFAULT_COMPONENT_TYPE_NODES,
): ComponentTypeGraphResult {
    if (!canProvideWasmModule()) return ComponentTypeGraphResult(ComponentTypeGraphStage.NOT_APPLICABLE, null, emptyList(), emptyList())
    return componentTypeGraph(types, maxDepth, maxNodes)
}

/** Adapter for callers that already decoded component definition types. */
fun componentTypeGraph(
    types: List<ComponentDefType>,
    maxDepth: Int = DEFAULT_COMPONENT_TYPE_DEPTH,
    maxNodes: Int = DEFAULT_COMPONENT_TYPE_NODES,
): ComponentTypeGraphResult {
    require(maxDepth > 0) { "maxDepth must be positive" }
    require(maxNodes > 0) { "maxNodes must be positive" }
    val mapper = ComponentTypeGraphMapper(maxDepth, maxNodes)
    return mapper.map(types)
}

private data class GraphSpec(
    val kind: ComponentTypeNodeKind,
    val attributes: ComponentTypeNodeAttributes = ComponentTypeNodeAttributes(),
    val children: () -> Sequence<GraphChild> = { emptySequence() },
)
private data class GraphChild(val role: String, val index: Int, val spec: () -> GraphSpec)

private class ComponentTypeGraphMapper(private val maxDepth: Int, private val maxNodes: Int) {
    private val nodes = mutableListOf<ComponentTypeNodeResult>()
    private var visited = 0L
    private var omitted = 0L

    fun map(types: List<ComponentDefType>): ComponentTypeGraphResult {
        val items = mutableListOf<ComponentTypeItemResult>()
        val pending = ArrayDeque<Iterator<TraversalEntry>>()
        pending.addLast(
            types.asSequence().mapIndexed { index, type ->
                TraversalEntry("type/$index", null, null, null, 0, { type.spec() }, type.family())
            }.iterator()
        )
        while (pending.isNotEmpty()) {
            val entries = pending.last()
            if (!entries.hasNext()) {
                pending.removeLast()
                continue
            }
            val entry = entries.next()
            if (entry.parent == null) {
                items += ComponentTypeItemResult(entry.rootIndex(), entry.family ?: ComponentTypeFamily.VALUE, entry.id)
            }
            if (entry.depth > maxDepth) {
                omit(entry, "component_type_depth_limit", countSubtree(entry))
                continue
            }
            if (visited >= maxNodes) {
                omit(entry, "component_type_node_limit", countSubtree(entry))
                continue
            }
            val spec = entry.spec()
            nodes += ComponentTypeNodeResult(entry.id, entry.parent, entry.role, entry.index?.toLong(), spec.kind, spec.attributes)
            visited++
            pending.addLast(spec.children().map { child ->
                TraversalEntry(
                    "${entry.id}/${child.role}/${child.index}",
                    entry.id,
                    child.role,
                    child.index,
                    entry.depth + 1,
                    child.spec,
                )
            }.iterator())
        }
        return ComponentTypeGraphResult(
            ComponentTypeGraphStage.COMPLETED,
            ComponentTypeTraversalLimitsResult(maxDepth.toLong(), maxNodes.toLong(), visited, omitted),
            items,
            nodes.sortedBy { it.nodeId },
        )
    }

    private fun omit(entry: TraversalEntry, reason: String, count: Long) {
        nodes += ComponentTypeNodeResult(entry.id, entry.parent, entry.role, entry.index?.toLong(), ComponentTypeNodeKind.OMITTED,
            ComponentTypeNodeAttributes(reason = reason, omittedNodeCount = count))
        omitted += count
    }

    private fun countSubtree(first: TraversalEntry): Long {
        val work = ArrayDeque<Iterator<GraphChild>>()
        work.addLast(first.spec().children().iterator())
        var count = 0L
        while (work.isNotEmpty()) {
            val children = work.last()
            if (!children.hasNext()) {
                work.removeLast()
                continue
            }
            val child = children.next()
            count++
            work.addLast(child.spec().children().iterator())
        }
        return count + 1
    }
}

private data class TraversalEntry(
    val id: String, val parent: String?, val role: String?, val index: Int?, val depth: Int,
    val spec: () -> GraphSpec,
    val family: ComponentTypeFamily? = null,
) {
    fun rootIndex() = id.removePrefix("type/").substringBefore('/').toLong()
}

private fun ComponentDefType.family() = when (this) {
    is ComponentAliasedType -> ComponentTypeFamily.ALIASED
    is ComponentImportedType -> ComponentTypeFamily.IMPORTED
    is ComponentPrimitiveType -> ComponentTypeFamily.PRIMITIVE
    is ComponentPrimitiveValueType -> ComponentTypeFamily.PRIMITIVE
    is ComponentResourceType -> ComponentTypeFamily.RESOURCE
    is ComponentDefValueType -> ComponentTypeFamily.VALUE
    is ComponentRoutineType -> ComponentTypeFamily.ROUTINE
    is ComponentType -> ComponentTypeFamily.COMPONENT
    is ComponentInstanceType -> ComponentTypeFamily.INSTANCE
}

private fun ComponentDefType.spec(): GraphSpec = when (this) {
    is ComponentAliasedType -> GraphSpec(ComponentTypeNodeKind.ALIAS, ComponentTypeNodeAttributes(sort = alias.componentSort.label(), coreSort = alias.componentSort.coreLabel()),
        { graphChildren(child("target", 0) { alias.aliasTarget.spec() }) })
    is ComponentImportedType -> GraphSpec(ComponentTypeNodeKind.IMPORTED, ComponentTypeNodeAttributes(name = import.importName, version = import.version),
        { graphChildren(child("extern", 0) { import.externDescription.spec() }) })
    is ComponentPrimitiveType -> GraphSpec(primitiveKind(), ComponentTypeNodeAttributes(primitive = primitiveName()))
    is ComponentPrimitiveValueType -> GraphSpec(type.primitiveKind(), ComponentTypeNodeAttributes(primitive = type.primitiveName()))
    is ComponentResourceType -> resourceSpec()
    is ComponentDefValueType -> valueSpec()
    is ComponentRoutineType -> GraphSpec(if (this is ComponentAsyncFunctionType) ComponentTypeNodeKind.ASYNC_FUNCTION else ComponentTypeNodeKind.FUNCTION,
        children = {
            graphChildren("param", params) { p ->
                GraphSpec(ComponentTypeNodeKind.PARAM, ComponentTypeNodeAttributes(name = p.label)) {
                    graphChildren(child("value", 0) { p.value.spec() })
                }
            } + graphChildren(child("result", 0) { ret.specResult() })
        })
    is ComponentType -> GraphSpec(ComponentTypeNodeKind.COMPONENT, children = { graphChildren("declaration", cd) { it.spec() } })
    is ComponentInstanceType -> GraphSpec(ComponentTypeNodeKind.INSTANCE, children = { graphChildren("declaration", cd) { it.specInstance() } })
}

private fun ComponentDefValueType.valueSpec(): GraphSpec = when (this) {
    is ComponentRecordType -> GraphSpec(ComponentTypeNodeKind.RECORD, children = {
        graphChildren("field", fields) { f ->
            GraphSpec(ComponentTypeNodeKind.FIELD, ComponentTypeNodeAttributes(name = f.label)) {
                graphChildren(child("value", 0) { f.value.spec() })
            }
        }
    })
    is ComponentVariantType -> GraphSpec(ComponentTypeNodeKind.VARIANT, children = {
        graphChildren("case", variants) { v ->
            GraphSpec(ComponentTypeNodeKind.CASE, ComponentTypeNodeAttributes(name = v.label)) {
                v.value?.let { graphChildren(child("value", 0) { it.spec() }) } ?: emptySequence()
            }
        }
    })
    is ComponentListType -> GraphSpec(ComponentTypeNodeKind.LIST, children = { graphChildren(child("value", 0) { elementType.spec() }) })
    is ComponentArrayType -> GraphSpec(ComponentTypeNodeKind.ARRAY, ComponentTypeNodeAttributes(length = len.u()), { graphChildren(child("value", 0) { elementType.spec() }) })
    is ComponentTupleType -> GraphSpec(ComponentTypeNodeKind.TUPLE, children = { graphChildren("element", elementTypes) { it.spec() } })
    is ComponentFlagType -> GraphSpec(ComponentTypeNodeKind.FLAGS, children = { graphChildren("flag", flags) { GraphSpec(ComponentTypeNodeKind.FLAG, ComponentTypeNodeAttributes(name = it)) } })
    is ComponentEnumType -> GraphSpec(ComponentTypeNodeKind.ENUM, children = { graphChildren("entry", entries) { GraphSpec(ComponentTypeNodeKind.ENUM_ENTRY, ComponentTypeNodeAttributes(name = it)) } })
    is ComponentOptionType -> GraphSpec(ComponentTypeNodeKind.OPTION, children = { graphChildren(child("value", 0) { option.spec() }) })
    is ComponentResultType -> GraphSpec(ComponentTypeNodeKind.RESULT, children = {
        listOfNotNull(
            result?.let { child("ok", 0) { it.spec() } },
            error?.let { child("error", 0) { it.spec() } },
        ).asSequence()
    })
    is ComponentOwnType -> GraphSpec(ComponentTypeNodeKind.OWN, ComponentTypeNodeAttributes(typeIndex = idx.u()))
    is ComponentBorrowType -> GraphSpec(ComponentTypeNodeKind.BORROW, ComponentTypeNodeAttributes(typeIndex = idx.u()))
    is ComponentStreamType -> GraphSpec(ComponentTypeNodeKind.STREAM, children = { stream?.let { graphChildren(child("value", 0) { it.spec() }) } ?: emptySequence() })
    is ComponentFutureType -> GraphSpec(ComponentTypeNodeKind.FUTURE, children = { future?.let { graphChildren(child("value", 0) { it.spec() }) } ?: emptySequence() })
    is ComponentValueType -> spec()
    is ComponentResourceType -> resourceSpec()
}

private fun ComponentResourceType.resourceSpec() = when (this) {
    is ComponentSyncFuncResourceType -> GraphSpec(ComponentTypeNodeKind.SYNC_FUNC_RESOURCE, ComponentTypeNodeAttributes(functionIndex = funcIdx?.u()))
    is ComponentAsyncFuncResourceType -> GraphSpec(ComponentTypeNodeKind.ASYNC_FUNC_RESOURCE, ComponentTypeNodeAttributes(functionIndex = funcIdx.u(), callbackIndex = callbackIdx?.u()))
}

private fun ComponentValueType.spec() = when (this) {
    is ComponentValueIdxType -> GraphSpec(ComponentTypeNodeKind.VALUE_TYPE_INDEX, ComponentTypeNodeAttributes(typeIndex = idx.u()))
    is ComponentPrimitiveValueType -> GraphSpec(ComponentTypeNodeKind.VALUE_PRIMITIVE, ComponentTypeNodeAttributes(primitive = type.primitiveName()))
}
private fun ComponentReturnType.specResult() = when (this) {
    ComponentVoidType -> GraphSpec(ComponentTypeNodeKind.VOID_RESULT)
    is ComponentValueType -> spec()
    else -> error("Unknown component return type: $this")
}

private fun ComponentDeclaration.spec() = when (this) {
    is ImportDeclaration -> GraphSpec(ComponentTypeNodeKind.COMPONENT_IMPORT_DECLARATION, ComponentTypeNodeAttributes(name = importName, versionSuffix = versionSuffix)) { graphChildren(child("extern", 0) { ed.spec() }) }
    is ComponentImportDeclaration -> GraphSpec(ComponentTypeNodeKind.COMPONENT_IMPORT_DECLARATION, ComponentTypeNodeAttributes(name = id.importName, versionSuffix = id.versionSuffix)) { graphChildren(child("extern", 0) { id.ed.spec() }) }
    is ComponentInstanceDeclaration -> GraphSpec(ComponentTypeNodeKind.COMPONENT_INSTANCE_DECLARATION, children = { graphChildren(child("declaration", 0) { id.specInstance() }) })
}
private fun InstanceDeclaration.specInstance(): GraphSpec = when (this) {
    is WasmComponentCoreType -> GraphSpec(ComponentTypeNodeKind.INSTANCE_CORE_TYPE, children = { graphChildren(child("type", 0) { specCore() }) })
    is ComponentDefType -> GraphSpec(ComponentTypeNodeKind.INSTANCE_COMPONENT_DEFINITION, children = { graphChildren(child("type", 0) { spec() }) })
    is ComponentAlias -> GraphSpec(ComponentTypeNodeKind.INSTANCE_ALIAS, ComponentTypeNodeAttributes(sort = componentSort.label(), coreSort = componentSort.coreLabel())) { graphChildren(child("target", 0) { aliasTarget.spec() }) }
    is ComponentCoreAliasDef -> GraphSpec(ComponentTypeNodeKind.INSTANCE_ALIAS, ComponentTypeNodeAttributes(coreSort = componentCoreSort.label())) { graphChildren(child("target", 0) { aliasCoreTarget.spec() }) }
    is ExportDeclaration -> GraphSpec(ComponentTypeNodeKind.INSTANCE_EXPORT, ComponentTypeNodeAttributes(name = exportName, versionSuffix = versionSuffix)) { graphChildren(child("extern", 0) { ed.spec() }) }
    else -> error("Unsupported component instance declaration: ${this::class.simpleName}")
}

private fun ComponentExternDescription.spec(): GraphSpec = when (this) {
    is ComponentExternCoreType -> GraphSpec(ComponentTypeNodeKind.EXTERN, ComponentTypeNodeAttributes(externKind = "core_type", typeIndex = idx.u()))
    is ComponentExternType -> GraphSpec(ComponentTypeNodeKind.EXTERN, ComponentTypeNodeAttributes(externKind = "type", typeIndex = idx.u()))
    is ComponentExternComponent -> GraphSpec(ComponentTypeNodeKind.EXTERN, ComponentTypeNodeAttributes(externKind = "component", typeIndex = idx.u()))
    is ComponentExternInstance -> GraphSpec(ComponentTypeNodeKind.EXTERN, ComponentTypeNodeAttributes(externKind = "instance", typeIndex = idx.u()))
    is ComponentExternValueBound -> GraphSpec(ComponentTypeNodeKind.EXTERN, ComponentTypeNodeAttributes(externKind = "value")) { graphChildren(child("bound", 0) { bound.spec() }) }
    is ComponentExternTypeBound -> GraphSpec(ComponentTypeNodeKind.EXTERN, ComponentTypeNodeAttributes(externKind = "type_bound")) { graphChildren(child("bound", 0) { bound.spec() }) }
}
private fun ValueBound.spec() = when (this) {
    is ValueBoundType -> GraphSpec(ComponentTypeNodeKind.VALUE_BOUND, ComponentTypeNodeAttributes(boundKind = "type", typeIndex = idx.u()))
    is ValueBoundValue -> GraphSpec(ComponentTypeNodeKind.VALUE_BOUND, ComponentTypeNodeAttributes(boundKind = "value")) { graphChildren(child("value", 0) { type.spec() }) }
}
private fun TypeBound.spec() = when (this) {
    is TypeBoundType -> GraphSpec(ComponentTypeNodeKind.TYPE_BOUND, ComponentTypeNodeAttributes(boundKind = "type", typeIndex = idx.u()))
    is TypeBoundSubResource -> GraphSpec(ComponentTypeNodeKind.TYPE_BOUND, ComponentTypeNodeAttributes(boundKind = "subresource"))
}

private fun AliasTarget.spec(): GraphSpec = when (this) {
    is AliasTarget.Export -> GraphSpec(ComponentTypeNodeKind.ALIAS_TARGET, ComponentTypeNodeAttributes(targetKind = "export", index = index.u(), name = name))
    is AliasTarget.Core -> GraphSpec(ComponentTypeNodeKind.ALIAS_TARGET, ComponentTypeNodeAttributes(targetKind = "core", index = index.u(), name = name))
    is AliasTarget.Outer -> GraphSpec(ComponentTypeNodeKind.ALIAS_TARGET, ComponentTypeNodeAttributes(targetKind = "outer", index = index.u(), outerIndex = outerIndex.u()))
    is AliasTarget.CoreOuter -> GraphSpec(ComponentTypeNodeKind.ALIAS_TARGET, ComponentTypeNodeAttributes(targetKind = "core_outer", index = index.u(), outerIndex = outerIndex.u()))
}

private fun WasmComponentCoreType.specCore(): GraphSpec = when (this) {
    is WasmComponentCoreModuleType -> GraphSpec(ComponentTypeNodeKind.CORE_MODULE, children = { graphChildren("declaration", list) { it.spec() } })
    is WasmTypeRecord -> specWasmType()
    else -> error("Unsupported component core type: ${this::class.simpleName}")
}
private fun ComponentCoreModuleDeclaration.spec(): GraphSpec = when (this) {
    is ComponentCoreModuleImportDeclaration -> GraphSpec(ComponentTypeNodeKind.CORE_IMPORT_DECLARATION, ComponentTypeNodeAttributes(offsetBytes = i.offset.i(), sizeBytes = i.size.i(), prefix = i.prefix, name = i.name)) { graphChildren(child("external_type", 0) { i.type.specExternal() }) }
    is ComponentCoreModuleTypeDeclaration -> GraphSpec(ComponentTypeNodeKind.CORE_TYPE_DECLARATION, children = { graphChildren(child("type", 0) { t.specCore() }) })
    is ComponentCoreModuleAliasDeclaration -> GraphSpec(ComponentTypeNodeKind.CORE_ALIAS_DECLARATION, ComponentTypeNodeAttributes(coreSort = a.componentCoreSort.label())) { graphChildren(child("target", 0) { a.aliasCoreTarget.spec() }) }
    is ComponentCoreModuleExportDeclaration -> GraphSpec(ComponentTypeNodeKind.CORE_EXPORT_DECLARATION, ComponentTypeNodeAttributes(offsetBytes = e.offset.i(), sizeBytes = e.size.i(), name = e.name, index = e.exportIndex(), externType = e.externType.toUByte().toInt()))
}
private fun WasmTypeRecord.specWasmType(): GraphSpec = when (this) {
    is WasmRecursiveTypeRecord -> GraphSpec(ComponentTypeNodeKind.WASM_RECURSIVE_TYPE, ComponentTypeNodeAttributes(offsetBytes = offset.i(), sizeBytes = size.i()), { graphChildren("type", entries) { it.specWasmType() } })
    is WasmArrayTypeRecord -> GraphSpec(ComponentTypeNodeKind.WASM_ARRAY_TYPE, ComponentTypeNodeAttributes(offsetBytes = offset.i(), sizeBytes = size.i(), hasDescriptorClause = hasDescriptorClause), { graphChildren(child("field", 0) { fieldType.specField() }) })
    is WasmStructTypeRecord -> GraphSpec(ComponentTypeNodeKind.WASM_STRUCT_TYPE, ComponentTypeNodeAttributes(offsetBytes = offset.i(), sizeBytes = size.i(), isFinal = isFinal, hasDescriptorClause = hasDescriptorClause),
        { graphChildren("supertype", superTypes) { GraphSpec(ComponentTypeNodeKind.ALIAS_TARGET, ComponentTypeNodeAttributes(typeIndex = it.u())) } + graphChildren("field", fields) { it.specField() } })
    is WasmSectionFunctionTypeRecord -> GraphSpec(ComponentTypeNodeKind.WASM_FUNCTION_TYPE, ComponentTypeNodeAttributes(offsetBytes = offset.i(), sizeBytes = size.i(), hasDescriptorClause = hasDescriptorClause),
        { graphChildren("param", params) { p -> GraphSpec(ComponentTypeNodeKind.WASM_PARAM, children = { graphChildren(child("value_type", 0) { p.specValue() }) }) } + graphChildren("result", retTypes) { r -> GraphSpec(ComponentTypeNodeKind.WASM_RESULT, children = { graphChildren(child("value_type", 0) { r.specValue() }) }) } })
}
private fun WasmFieldType.specField() = GraphSpec(ComponentTypeNodeKind.WASM_FIELD, ComponentTypeNodeAttributes(mutable = isMutable)) { graphChildren(child("value_type", 0) { storageType.specValue() }) }
private fun WasmExternalType.specExternal(): GraphSpec = when (this) {
    is WasmTypeIndexExternalType -> GraphSpec(ComponentTypeNodeKind.EXTERNAL_FUNCTION, ComponentTypeNodeAttributes(externalKind = "function", typeIndex = index))
    is WasmTableExternalType -> GraphSpec(ComponentTypeNodeKind.EXTERNAL_TABLE, ComponentTypeNodeAttributes(externalKind = "table")) { graphChildren(child("reference_type", 0) { referenceType.specReference() }, child("limits", 1) { limits.specLimits() }) }
    is WasmMemType -> GraphSpec(ComponentTypeNodeKind.EXTERNAL_MEMORY, ComponentTypeNodeAttributes(externalKind = "memory")) { graphChildren(child("limits", 0) { limits.specLimits() }) }
    is WasmGlobalType -> GraphSpec(ComponentTypeNodeKind.EXTERNAL_GLOBAL, ComponentTypeNodeAttributes(externalKind = "global", mutable = mutable)) { graphChildren(child("value_type", 0) { valueType.specValue() }) }
    is WasmTagType -> GraphSpec(ComponentTypeNodeKind.EXTERNAL_TAG, ComponentTypeNodeAttributes(externalKind = "tag", typeIndex = index.index))
}
private fun WasmLimits<*>.specLimits(): GraphSpec {
    val shared = when (this) { is WasmOpenI32Limits -> shared; is WasmFixedI32Limits -> shared; is WasmOpenI64Limits -> shared; is WasmFixedI64Limits -> shared }
    val kind = when (this) { is WasmOpenI32Limits -> "open_i32"; is WasmFixedI32Limits -> "fixed_i32"; is WasmOpenI64Limits -> "open_i64"; is WasmFixedI64Limits -> "fixed_i64" }
    fun Number.unsignedLong() = when (this) { is Int -> toUInt().toULong(); is Long -> toULong(); else -> toLong().toULong() }
    return GraphSpec(ComponentTypeNodeKind.WASM_LIMITS, ComponentTypeNodeAttributes(limitsKind = kind, min = min.unsignedLong(), max = max?.unsignedLong(), shared = shared, pageSizeLog2 = pageSizeLog2?.toUInt()?.toLong()))
}
private fun WasmValueType<*>.specValue(): GraphSpec = when (this) {
    WasmI32Type -> GraphSpec(ComponentTypeNodeKind.WASM_VALUE, ComponentTypeNodeAttributes(valueKind = "i32"))
    WasmI64Type -> GraphSpec(ComponentTypeNodeKind.WASM_VALUE, ComponentTypeNodeAttributes(valueKind = "i64"))
    WasmF32Type -> GraphSpec(ComponentTypeNodeKind.WASM_VALUE, ComponentTypeNodeAttributes(valueKind = "f32"))
    WasmF64Type -> GraphSpec(ComponentTypeNodeKind.WASM_VALUE, ComponentTypeNodeAttributes(valueKind = "f64"))
    WasmVecType -> GraphSpec(ComponentTypeNodeKind.WASM_VALUE, ComponentTypeNodeAttributes(valueKind = "v128"))
    WasmPactTypeI8 -> GraphSpec(ComponentTypeNodeKind.WASM_VALUE, ComponentTypeNodeAttributes(valueKind = "i8"))
    WasmPactTypeI16 -> GraphSpec(ComponentTypeNodeKind.WASM_VALUE, ComponentTypeNodeAttributes(valueKind = "i16"))
    is WasmRefType -> GraphSpec(ComponentTypeNodeKind.WASM_VALUE, ComponentTypeNodeAttributes(valueKind = "reference")) {
        graphChildren(child("reference_type", 0) { code.specReference() })
    }
}
private fun WasmReferenceType.specReference() = GraphSpec(
    ComponentTypeNodeKind.WASM_REFERENCE,
    ComponentTypeNodeAttributes(referenceForm = when (this) {
        is WasmNullableReferenceType -> "nullable"
        is WasmNonNullableReferenceType -> "nonnullable"
        is WasmReferenceHeapType -> "abstract"
    }),
) { graphChildren(child("heap", 0) { heapType.specHeap() }) }
private fun WasmHeapType.specHeap() = GraphSpec(ComponentTypeNodeKind.WASM_HEAP, ComponentTypeNodeAttributes(heapKind = when (this) { WasmExnRefHeapType -> "exn"; WasmArrayRefHeapType -> "array"; WasmStructRefHeapType -> "struct"; WasmI31RefHeapType -> "i31"; WasmEqRefHeapType -> "eq"; WasmAnyRefHeapType -> "any"; WasmExternRefHeapType -> "extern"; WasmFuncHeapType -> "func"; WasmNoneHeapType -> "none"; WasmNoExternRefHeapType -> "noextern"; WasmNoFuncHeapType -> "nofunc"; WasmNoExnHeapType -> "noexn"; is WasmIndexHeapType -> "index"; is WasmExactHeapType -> "exact" }, heapType = if (this is WasmIndexHeapType || this is WasmExactHeapType) type else null))

private fun ComponentPrimitiveType.primitiveName() = when (this) { ComponentPrimitiveBoolType -> "bool"; ComponentPrimitiveS8Type -> "s8"; ComponentPrimitiveU8Type -> "u8"; ComponentPrimitiveS16Type -> "s16"; ComponentPrimitiveU16Type -> "u16"; ComponentPrimitiveS32Type -> "s32"; ComponentPrimitiveU32Type -> "u32"; ComponentPrimitiveS64Type -> "s64"; ComponentPrimitiveU64Type -> "u64"; ComponentPrimitiveF32Type -> "f32"; ComponentPrimitiveF64Type -> "f64"; ComponentPrimitiveCharType -> "char"; ComponentPrimitiveString -> "string"; ComponentPrimitiveErrorContext -> "error_context" }
private fun ComponentPrimitiveType.primitiveKind() = when (primitiveName()) { "bool" -> ComponentTypeNodeKind.BOOL; "s8" -> ComponentTypeNodeKind.S8; "u8" -> ComponentTypeNodeKind.U8; "s16" -> ComponentTypeNodeKind.S16; "u16" -> ComponentTypeNodeKind.U16; "s32" -> ComponentTypeNodeKind.S32; "u32" -> ComponentTypeNodeKind.U32; "s64" -> ComponentTypeNodeKind.S64; "u64" -> ComponentTypeNodeKind.U64; "f32" -> ComponentTypeNodeKind.F32; "f64" -> ComponentTypeNodeKind.F64; "char" -> ComponentTypeNodeKind.CHAR; "string" -> ComponentTypeNodeKind.STRING; else -> ComponentTypeNodeKind.ERROR_CONTEXT }
private fun ComponentSort.label() = when (this) { ComponentSort.Func -> "func"; ComponentSort.Value -> "value"; ComponentSort.Type -> "type"; ComponentSort.Component -> "component"; ComponentSort.Instance -> "instance"; is ComponentSortWithCoreSort -> "core" }
private fun ComponentSort.coreLabel() = (this as? ComponentSortWithCoreSort)?.cs?.label()
private fun ComponentCoreSort.label() = when (this) { ComponentCoreSort.Func -> "func"; ComponentCoreSort.Table -> "table"; ComponentCoreSort.Memory -> "memory"; ComponentCoreSort.Global -> "global"; ComponentCoreSort.Type -> "type"; ComponentCoreSort.Module -> "module"; ComponentCoreSort.Instance -> "instance" }
private fun child(role: String, index: Int, spec: () -> GraphSpec) = GraphChild(role, index, spec)
private fun graphChildren(vararg children: GraphChild): Sequence<GraphChild> = children.asSequence()
private fun <T> graphChildren(role: String, values: List<T>, spec: (T) -> GraphSpec): Sequence<GraphChild> =
    values.asSequence().mapIndexed { index, value -> child(role, index) { spec(value) } }
private fun UInt.u() = toLong()
private fun Int.i() = toUInt().toLong()
private fun WasmExportRecordEntry.exportIndex() = when (this) {
    is WasmFunctionExportRecordEntry -> index.u(); is WasmTableExportRecordEntry -> index.u()
    is WasmMemoryExportRecordEntry -> index.u(); is WasmGlobalExportRecordEntry -> index.u()
    is WasmTagExportRecordEntry -> index.u(); is WasmUnsupportedExportRecordEntry -> index.u()
}
