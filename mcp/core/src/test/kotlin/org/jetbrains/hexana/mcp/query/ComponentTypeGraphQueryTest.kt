package org.jetbrains.hexana.mcp.query

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.components.ComponentTypeGraphResult
import org.jetbrains.hexana.mcp.model.components.ComponentTypeGraphStage
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.hexana.wasm.sections.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentTypeGraphQueryTest {
    @Test
    fun `maps component families relationships ids and stable JSON`() {
        val types = listOf<ComponentDefType>(
            ComponentAliasedType(ComponentAlias(ComponentSortWithCoreSort(ComponentCoreSort.Func), AliasTarget.Outer(2u, 3u))),
            ComponentImportedType(WasmComponentImport(4, 5, "example:import", "1.2", ComponentExternValueBound(ValueBoundValue(ComponentValueIdxType(9u))))),
            ComponentPrimitiveBoolType,
            ComponentRecordType(listOf(LabelValueType("record-field", ComponentPrimitiveValueType(ComponentPrimitiveString)))),
            ComponentVariantType(listOf(CaseLabelValueType("empty"), CaseLabelValueType("value", ComponentValueIdxType(2u)))),
            ComponentArrayType(ComponentValueIdxType(8u), UInt.MAX_VALUE),
            ComponentResultType(ComponentPrimitiveValueType(ComponentPrimitiveU32Type), ComponentValueIdxType(1u)),
            ComponentFunctionType(listOf(LabelValueType("input", ComponentValueIdxType(7u))), ComponentVoidType),
            ComponentAsyncFuncResourceType(11u, 12u),
            ComponentType(listOf(ComponentImportDeclaration(ImportDeclaration("pkg", ComponentExternTypeBound(TypeBoundSubResource()), "v")))),
            ComponentInstanceType(listOf(ExportDeclaration("out", ComponentExternComponent(4u), "v2"))),
        )

        val graph = componentTypeGraph(types)

        assertEquals((0 until types.size).map { it.toLong() }, graph.items.map { it.index })
        assertEquals(graph.nodes.sortedBy { it.nodeId }, graph.nodes)
        assertTrue(graph.nodes.any { it.nodeId == "type/3/field/0/value/0" && it.kind.name == "VALUE_PRIMITIVE" })
        assertTrue(graph.nodes.any { it.nodeId == "type/7/result/0" && it.kind.name == "VOID_RESULT" })
        assertTrue(graph.nodes.any { it.attributes.length == UInt.MAX_VALUE.toLong() })
        assertEquals(graph, componentTypeGraph(types))
        val json = Json.encodeToString(graph)
        assertTrue(json.contains("\"root_node_id\""))
        assertFalse(json.contains("children"))
    }

    @Test
    fun `maps every primitive definition and value relationship family`() {
        val primitives = listOf<ComponentPrimitiveType>(
            ComponentPrimitiveBoolType, ComponentPrimitiveS8Type, ComponentPrimitiveU8Type, ComponentPrimitiveS16Type,
            ComponentPrimitiveU16Type, ComponentPrimitiveS32Type, ComponentPrimitiveU32Type, ComponentPrimitiveS64Type,
            ComponentPrimitiveU64Type, ComponentPrimitiveF32Type, ComponentPrimitiveF64Type, ComponentPrimitiveCharType,
            ComponentPrimitiveString, ComponentPrimitiveErrorContext,
        )
        val graph = componentTypeGraph(primitives)

        assertEquals(listOf("bool", "s8", "u8", "s16", "u16", "s32", "u32", "s64", "u64", "f32", "f64", "char", "string", "error_context"), graph.items.map { item -> graph.nodes.single { it.nodeId == item.rootNodeId }.attributes.primitive })
        assertTrue(graph.items.all { it.family.name == "PRIMITIVE" })
    }

    @Test
    fun `maps remaining component families declarations aliases externs bounds and sorts`() {
        val values = listOf<ComponentDefType>(
            ComponentListType(ComponentValueIdxType(1u)),
            ComponentTupleType(listOf(ComponentValueIdxType(2u))),
            ComponentFlagType(listOf("flag")),
            ComponentEnumType(listOf("entry")),
            ComponentOptionType(ComponentValueIdxType(3u)),
            ComponentOwnType(4u),
            ComponentBorrowType(5u),
            ComponentStreamType(ComponentValueIdxType(6u)),
            ComponentFutureType(ComponentValueIdxType(7u)),
            ComponentSyncFuncResourceType(8u),
            ComponentAsyncFuncResourceType(9u, 10u),
            ComponentFunctionType(emptyList(), ComponentVoidType),
            ComponentAsyncFunctionType(emptyList(), ComponentVoidType),
        )
        val valueGraph = componentTypeGraph(values)
        assertEquals(
            listOf("LIST", "TUPLE", "FLAGS", "ENUM", "OPTION", "OWN", "BORROW", "STREAM", "FUTURE", "SYNC_FUNC_RESOURCE", "ASYNC_FUNC_RESOURCE", "FUNCTION", "ASYNC_FUNCTION"),
            valueGraph.items.map { item -> valueGraph.nodes.single { it.nodeId == item.rootNodeId }.kind.name },
        )
        assertNode(valueGraph, "type/0/value/0", "type/0", "value", 0L, "VALUE_TYPE_INDEX")
        assertNode(valueGraph, "type/1/element/0", "type/1", "element", 0L, "VALUE_TYPE_INDEX")
        assertEquals(listOf(4L, 5L), valueGraph.nodes.filter { it.kind.name == "OWN" || it.kind.name == "BORROW" }.map { it.attributes.typeIndex })
        assertEquals(10L, valueGraph.nodes.single { it.kind.name == "ASYNC_FUNC_RESOURCE" }.attributes.callbackIndex)

        val component = ComponentType(listOf(
            ImportDeclaration("plain", ComponentExternCoreType(1u), "v1"),
            ComponentImportDeclaration(ImportDeclaration("wrapped", ComponentExternType(2u), "v2")),
            ComponentInstanceDeclaration(ComponentPrimitiveBoolType),
        ))
        val instance = ComponentInstanceType(listOf(
            WasmComponentCoreModuleType(emptyList()),
            ComponentPrimitiveBoolType,
            ComponentAlias(ComponentSort.Component, AliasTarget.Export(3u, "export")),
            ComponentCoreAliasDef(ComponentCoreSort.Module, AliasTarget.CoreOuter(4u, 5u)),
            ExportDeclaration("out", ComponentExternInstance(6u), "v3"),
        ))
        val declarationGraph = componentTypeGraph(listOf(component, instance))
        assertEquals(
            listOf("COMPONENT_IMPORT_DECLARATION", "COMPONENT_IMPORT_DECLARATION", "COMPONENT_INSTANCE_DECLARATION"),
            declarationGraph.nodes.filter { it.nodeId.startsWith("type/0/declaration/") && it.nodeId.split('/').size == 4 }.map { it.kind.name },
        )
        assertEquals(
            listOf("INSTANCE_CORE_TYPE", "INSTANCE_COMPONENT_DEFINITION", "INSTANCE_ALIAS", "INSTANCE_ALIAS", "INSTANCE_EXPORT"),
            declarationGraph.nodes.filter { it.nodeId.startsWith("type/1/declaration/") && it.nodeId.split('/').size == 4 }.map { it.kind.name },
        )
        assertEquals("component", declarationGraph.nodes.single { it.nodeId == "type/1/declaration/2" }.attributes.sort)
        assertEquals("module", declarationGraph.nodes.single { it.nodeId == "type/1/declaration/3" }.attributes.coreSort)

        val componentSorts = listOf(ComponentSort.Func, ComponentSort.Value, ComponentSort.Type, ComponentSort.Component, ComponentSort.Instance)
        val componentAliases = componentSorts.mapIndexed { index, sort ->
            val target = when (index) {
                0 -> AliasTarget.Export(1u, "export")
                1 -> AliasTarget.Core(2u, "core")
                else -> AliasTarget.Outer(index.toUInt(), (index + 1).toUInt())
            }
            ComponentAliasedType(ComponentAlias(sort, target))
        } + ComponentAliasedType(ComponentAlias(ComponentSortWithCoreSort(ComponentCoreSort.Table), AliasTarget.Core(8u, "core")))
        val coreSorts = listOf(ComponentCoreSort.Func, ComponentCoreSort.Table, ComponentCoreSort.Memory, ComponentCoreSort.Global, ComponentCoreSort.Type, ComponentCoreSort.Module, ComponentCoreSort.Instance)
        val coreAliases = coreSorts.mapIndexed { index, sort -> ComponentCoreAliasDef(sort, AliasTarget.CoreOuter(index.toUInt(), 9u)) }
        val aliasGraph = componentTypeGraph(componentAliases + listOf(
            ComponentInstanceType(coreAliases),
            ComponentInstanceType(listOf(WasmComponentCoreModuleType(coreAliases.map { ComponentCoreModuleAliasDeclaration(it) }))),
        ))
        assertEquals(setOf("func", "value", "type", "component", "instance", "core"), aliasGraph.nodes.filter { it.kind.name == "ALIAS" }.mapNotNull { it.attributes.sort }.toSet())
        assertEquals("table", aliasGraph.nodes.single { it.kind.name == "ALIAS" && it.attributes.sort == "core" }.attributes.coreSort)
        assertEquals(setOf("export", "core", "outer", "core_outer"), aliasGraph.nodes.filter { it.kind.name == "ALIAS_TARGET" }.mapNotNull { it.attributes.targetKind }.toSet())
        assertEquals(coreSorts.size, aliasGraph.nodes.count { it.kind.name == "INSTANCE_ALIAS" })
        assertEquals(coreSorts.map { it.labelForTest() }.toSet(), aliasGraph.nodes.filter { it.kind.name == "INSTANCE_ALIAS" }.mapNotNull { it.attributes.coreSort }.toSet())
        assertEquals(coreSorts.size, aliasGraph.nodes.count { it.kind.name == "CORE_ALIAS_DECLARATION" })

        val externs = listOf<ComponentExternDescription>(
            ComponentExternCoreType(1u), ComponentExternType(2u), ComponentExternComponent(3u), ComponentExternInstance(4u),
            ComponentExternValueBound(ValueBoundType(5u)), ComponentExternValueBound(ValueBoundValue(ComponentValueIdxType(6u))),
            ComponentExternTypeBound(TypeBoundType(7u)), ComponentExternTypeBound(TypeBoundSubResource()),
        )
        val externGraph = componentTypeGraph(externs.mapIndexed { index, extern ->
            ComponentImportedType(WasmComponentImport(index, 1, "extern-$index", externDescription = extern))
        })
        assertEquals(setOf("core_type", "type", "component", "instance", "value", "type_bound"), externGraph.nodes.filter { it.kind.name == "EXTERN" }.mapNotNull { it.attributes.externKind }.toSet())
        assertEquals(setOf("type", "value", "subresource"), externGraph.nodes.filter { it.kind.name == "VALUE_BOUND" || it.kind.name == "TYPE_BOUND" }.mapNotNull { it.attributes.boundKind }.toSet())
        assertEquals(7L, externGraph.nodes.single { it.kind.name == "TYPE_BOUND" && it.attributes.boundKind == "type" }.attributes.typeIndex)
    }

    @Test
    fun `maps all core external forms limits values references heaps and exports`() {
        val limits = listOf(
            WasmOpenI32Limits(-1, true, 10), WasmFixedI32Limits(1, 2),
            WasmOpenI64Limits(Long.MAX_VALUE, true), WasmFixedI64Limits(3, 4, false, 12),
        )
        val heapTypes = listOf<WasmHeapType>(
            WasmExnRefHeapType, WasmArrayRefHeapType, WasmStructRefHeapType, WasmI31RefHeapType, WasmEqRefHeapType,
            WasmAnyRefHeapType, WasmExternRefHeapType, WasmFuncHeapType, WasmNoneHeapType, WasmNoExternRefHeapType,
            WasmNoFuncHeapType, WasmNoExnHeapType, WasmIndexHeapType(31), WasmExactHeapType(32),
        )
        val valueTypes = listOf<WasmValueType<*>>(
            WasmI32Type, WasmI64Type, WasmF32Type, WasmF64Type, WasmVecType, WasmPactTypeI8, WasmPactTypeI16,
            WasmRefType(WasmNullableReferenceType(WasmIndexHeapType(1))),
            WasmRefType(WasmNonNullableReferenceType(WasmExactHeapType(2))),
            WasmRefType(WasmReferenceHeapType(WasmAnyRefHeapType)),
        )
        val declarations = limits.mapIndexed { i, limit ->
            ComponentCoreModuleImportDeclaration(WasmImportRecord(i, i + 1, "p$i", "memory$i", WasmMemType(limit)))
        }.toMutableList<ComponentCoreModuleDeclaration>()
        declarations += ComponentCoreModuleImportDeclaration(WasmImportRecord(20, 2, "p", "function", WasmTypeIndexExternalType(Long.MAX_VALUE)))
        declarations += ComponentCoreModuleImportDeclaration(WasmImportRecord(21, 2, "p", "table", WasmTableExternalType(WasmNullableReferenceType(WasmIndexHeapType(7)), WasmFixedI32Limits(1, 2))))
        declarations += ComponentCoreModuleImportDeclaration(WasmImportRecord(22, 2, "p", "global", WasmGlobalType(WasmI32Type, true)))
        declarations += ComponentCoreModuleImportDeclaration(WasmImportRecord(23, 2, "p", "tag", WasmTagType(WasmTypeIndexExternalType(8))))
        declarations += ComponentCoreModuleTypeDeclaration(WasmRecursiveTypeRecord(29, 3, listOf(WasmArrayTypeRecord(30, 4, WasmFieldType(WasmI32Type, false), hasDescriptorClause = true))))
        declarations += ComponentCoreModuleTypeDeclaration(WasmArrayTypeRecord(31, 4, WasmFieldType(WasmI64Type, true), hasDescriptorClause = true))
        declarations += ComponentCoreModuleTypeDeclaration(WasmStructTypeRecord(32, 4, listOf(1u), valueTypes.map { WasmFieldType(it, true) } + heapTypes.map { WasmFieldType(WasmRefType(WasmReferenceHeapType(it)), true) }, false, true))
        declarations += ComponentCoreModuleTypeDeclaration(WasmSectionFunctionTypeRecord(33, 4, valueTypes, valueTypes, hasDescriptorClause = true))
        declarations += ComponentCoreModuleAliasDeclaration(ComponentCoreAliasDef(ComponentCoreSort.Type, AliasTarget.CoreOuter(3u, 4u)))
        declarations += ComponentCoreModuleExportDeclaration(WasmFunctionExportRecordEntry(40, 4, "known", 4u))
        declarations += ComponentCoreModuleExportDeclaration(WasmTableExportRecordEntry(41, 4, "table", 5u))
        declarations += ComponentCoreModuleExportDeclaration(WasmMemoryExportRecordEntry(42, 4, "memory", 6u))
        declarations += ComponentCoreModuleExportDeclaration(WasmGlobalExportRecordEntry(43, 4, "global", 7u))
        declarations += ComponentCoreModuleExportDeclaration(WasmTagExportRecordEntry(44, 4, "tag", 8u))
        declarations += ComponentCoreModuleExportDeclaration(WasmUnsupportedExportRecordEntry(41, 4, "unknown", 0xff.toByte(), 5u))

        val graph = componentTypeGraph(listOf(ComponentInstanceType(listOf(WasmComponentCoreModuleType(declarations)))))
        val attrs = graph.nodes.map { it.attributes }

        assertEquals(setOf("open_i32", "fixed_i32", "open_i64", "fixed_i64"), attrs.mapNotNull { it.limitsKind }.toSet())
        assertEquals(0xff, attrs.single { it.name == "unknown" }.externType)
        assertTrue(attrs.any { it.typeIndex == Long.MAX_VALUE })
        assertEquals(14, attrs.mapNotNull { it.heapKind }.distinct().size)
        assertTrue(graph.nodes.any { it.kind.name == "EXTERNAL_TABLE" })
        assertTrue(graph.nodes.any { it.kind.name == "EXTERNAL_GLOBAL" && it.attributes.mutable == true })
        assertEquals(setOf("function", "table", "memory", "global", "tag"), attrs.mapNotNull { it.externalKind }.toSet())
        assertTrue(setOf("WASM_RECURSIVE_TYPE", "WASM_ARRAY_TYPE", "WASM_STRUCT_TYPE", "WASM_FUNCTION_TYPE", "CORE_ALIAS_DECLARATION").all { kind -> graph.nodes.any { it.kind.name == kind } })
        assertEquals(setOf("i32", "i64", "f32", "f64", "v128", "i8", "i16", "reference"), attrs.mapNotNull { it.valueKind }.toSet())
        assertEquals(setOf("nullable", "nonnullable", "abstract"), attrs.mapNotNull { it.referenceForm }.toSet())
        assertEquals((0..4).toSet() + 255, graph.nodes.filter { it.kind.name == "CORE_EXPORT_DECLARATION" }.mapNotNull { it.attributes.externType }.toSet())
    }

    @Test
    fun `caps preserve one omission per branch root item and parent link`() {
        val wide = ComponentRecordType(
            List(5) { index -> LabelValueType("field-$index", ComponentPrimitiveValueType(ComponentPrimitiveString)) },
        )
        val nodeCapped = componentTypeGraph(listOf(wide, ComponentPrimitiveBoolType), maxDepth = 20, maxNodes = 1)
        val depthCapped = componentTypeGraph(listOf(wide), maxDepth = 1, maxNodes = 50)

        assertEquals(1, nodeCapped.limits!!.visitedNodes)
        assertEquals(11, nodeCapped.limits.omittedNodes)
        assertTrue(nodeCapped.nodes.size > nodeCapped.limits.maxNodes)
        assertEquals(listOf(0L, 1L), nodeCapped.items.map { it.index })
        assertEquals(
            (0 until 5).map { "type/0/field/$it" } + "type/1",
            nodeCapped.nodes.filter { it.kind.name == "OMITTED" }.map { it.nodeId },
        )
        nodeCapped.nodes.filter { it.kind.name == "OMITTED" }.forEachIndexed { index, node ->
            assertEquals("component_type_node_limit", node.attributes.reason)
            assertEquals(if (index < 5) 2 else 1, node.attributes.omittedNodeCount)
            assertEquals(if (index < 5) "type/0" else null, node.parentNodeId)
            assertEquals(if (index < 5) "field" else null, node.childRole)
            assertEquals(if (index < 5) index.toLong() else null, node.childIndex)
        }
        assertEquals(5, depthCapped.nodes.count { it.kind.name == "OMITTED" })
        depthCapped.nodes.filter { it.kind.name == "OMITTED" }.forEachIndexed { index, node ->
            assertEquals("component_type_depth_limit", node.attributes.reason)
            assertEquals(1L, node.attributes.omittedNodeCount)
            assertEquals("type/0/field/$index", node.parentNodeId)
            assertEquals("value", node.childRole)
            assertEquals(0L, node.childIndex)
        }
        assertGraphLinksResolve(nodeCapped)
        assertGraphLinksResolve(depthCapped)
    }

    @Test
    fun `provider type index keeps physical type import alias order`() {
        val header = byteArrayOf(0, 0x61, 0x73, 0x6d, 0x0d, 0, 1, 0)
        val type = byteArrayOf(7, 2, 1, 0x7f)
        val importedType = byteArrayOf(10, 6, 1, 0, 1, 'x'.code.toByte(), 3, 1)
        val alias = byteArrayOf(6, 5, 1, 3, 2, 0, 0)

        val graph = WasmComponentBinaryProvider((header + type + importedType + alias).toCommonByteBuffer()).readComponentTypeGraph()

        assertEquals(listOf("primitive", "imported", "aliased"), graph.items.map { it.family.name.lowercase() })
        assertEquals(listOf("type/0", "type/1", "type/2"), graph.items.map { it.rootNodeId })
        assertEquals(listOf("type/0", "type/1", "type/2"), graph.nodes.filter { it.parentNodeId == null }.map { it.nodeId })
    }

    @Test
    fun `deep and wide caps stay iterative with all roots represented`() {
        var deep: ComponentDefType = ComponentPrimitiveBoolType
        repeat(10_000) { deep = ComponentInstanceType(listOf(deep)) }
        val deepGraph = componentTypeGraph(listOf(deep), maxDepth = 2, maxNodes = 4)
        val roots = componentTypeGraph(List(10) { ComponentPrimitiveBoolType }, maxDepth = 10, maxNodes = 4)
        val wide = componentTypeGraph(
            listOf(ComponentTupleType(List(2_000) { ComponentPrimitiveValueType(ComponentPrimitiveBoolType) })),
            maxDepth = 20,
            maxNodes = 1,
        )

        assertEquals(3, deepGraph.limits!!.visitedNodes)
        assertEquals("component_type_depth_limit", deepGraph.nodes.single { it.kind.name == "OMITTED" }.attributes.reason)
        assertEquals(10, roots.items.size)
        assertEquals(4, roots.limits!!.visitedNodes)
        assertEquals(6, roots.limits.omittedNodes)
        assertEquals((0 until 10).map { "type/$it" }, roots.items.map { it.rootNodeId })
        assertEquals(2_000, wide.nodes.count { it.kind.name == "OMITTED" })
        assertEquals(2_000, wide.limits!!.omittedNodes)
        assertGraphLinksResolve(deepGraph)
        assertGraphLinksResolve(roots)
        assertGraphLinksResolve(wide)
    }

    @Test
    fun `unsigned limits and page sizes retain high bits as JSON numbers`() {
        val graph = componentTypeGraph(listOf(ComponentInstanceType(listOf(WasmComponentCoreModuleType(listOf(
            ComponentCoreModuleImportDeclaration(WasmImportRecord(0, 1, "p", "m32", WasmMemType(WasmFixedI32Limits(-1, Int.MAX_VALUE, pageSizeLog2 = Int.MIN_VALUE)))),
            ComponentCoreModuleImportDeclaration(WasmImportRecord(1, 1, "p", "m64", WasmMemType(WasmFixedI64Limits(Long.MIN_VALUE, -1L, pageSizeLog2 = Int.MIN_VALUE)))),
        ))))))
        val limits = graph.nodes.filter { it.kind.name == "WASM_LIMITS" }.map { it.attributes }
        val json = Json.encodeToString(graph)

        assertEquals(listOf(UInt.MAX_VALUE.toULong(), 9_223_372_036_854_775_808uL), limits.map { it.min })
        assertEquals(listOf(Int.MAX_VALUE.toULong(), ULong.MAX_VALUE), limits.map { it.max })
        assertEquals(listOf(2_147_483_648L, 2_147_483_648L), limits.map { it.pageSizeLog2 })
        assertTrue(json.contains("9223372036854775808"))
        assertTrue(json.contains("18446744073709551615"))
        assertTrue(json.contains("2147483648"))
    }

    @Test
    fun `core provider is not applicable`() {
        val result = WasmComponentBinaryProvider(byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0).toCommonByteBuffer()).readComponentTypeGraph()
        assertEquals(ComponentTypeGraphStage.NOT_APPLICABLE, result.stage)
        assertEquals(null, result.limits)
    }

    private fun assertGraphLinksResolve(graph: ComponentTypeGraphResult) {
        val ids = graph.nodes.map { it.nodeId }.toSet()
        assertTrue(graph.items.all { it.rootNodeId in ids })
        assertTrue(graph.nodes.all { it.parentNodeId == null || it.parentNodeId in ids })
    }

    private fun assertNode(graph: ComponentTypeGraphResult, id: String, parent: String, role: String, index: Long, kind: String) {
        val node = graph.nodes.single { it.nodeId == id }
        assertEquals(parent, node.parentNodeId)
        assertEquals(role, node.childRole)
        assertEquals(index, node.childIndex)
        assertEquals(kind, node.kind.name)
    }

    private fun ComponentCoreSort.labelForTest() = when (this) {
        ComponentCoreSort.Func -> "func"
        ComponentCoreSort.Table -> "table"
        ComponentCoreSort.Memory -> "memory"
        ComponentCoreSort.Global -> "global"
        ComponentCoreSort.Type -> "type"
        ComponentCoreSort.Module -> "module"
        ComponentCoreSort.Instance -> "instance"
    }
}
