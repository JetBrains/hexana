package org.jetbrains.hexana.mcp.model.components

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ComponentTypeGraphStage { @SerialName("completed") COMPLETED, @SerialName("not_applicable") NOT_APPLICABLE }

@Serializable
enum class ComponentTypeFamily {
    @SerialName("aliased") ALIASED, @SerialName("imported") IMPORTED, @SerialName("primitive") PRIMITIVE,
    @SerialName("value") VALUE, @SerialName("routine") ROUTINE, @SerialName("component") COMPONENT,
    @SerialName("instance") INSTANCE, @SerialName("resource") RESOURCE,
}

@Serializable
enum class ComponentTypeNodeKind {
    @SerialName("alias") ALIAS, @SerialName("imported") IMPORTED, @SerialName("bool") BOOL, @SerialName("s8") S8,
    @SerialName("u8") U8, @SerialName("s16") S16, @SerialName("u16") U16, @SerialName("s32") S32,
    @SerialName("u32") U32, @SerialName("s64") S64, @SerialName("u64") U64, @SerialName("f32") F32,
    @SerialName("f64") F64, @SerialName("char") CHAR, @SerialName("string") STRING,
    @SerialName("error_context") ERROR_CONTEXT, @SerialName("record") RECORD, @SerialName("variant") VARIANT,
    @SerialName("list") LIST, @SerialName("array") ARRAY, @SerialName("tuple") TUPLE, @SerialName("flags") FLAGS,
    @SerialName("enum") ENUM, @SerialName("option") OPTION, @SerialName("result") RESULT, @SerialName("own") OWN,
    @SerialName("borrow") BORROW, @SerialName("stream") STREAM, @SerialName("future") FUTURE,
    @SerialName("function") FUNCTION, @SerialName("async_function") ASYNC_FUNCTION,
    @SerialName("component") COMPONENT, @SerialName("instance") INSTANCE,
    @SerialName("sync_func_resource") SYNC_FUNC_RESOURCE, @SerialName("async_func_resource") ASYNC_FUNC_RESOURCE,
    @SerialName("alias_target") ALIAS_TARGET, @SerialName("extern") EXTERN,
    @SerialName("value_bound") VALUE_BOUND, @SerialName("type_bound") TYPE_BOUND,
    @SerialName("value_primitive") VALUE_PRIMITIVE, @SerialName("value_type_index") VALUE_TYPE_INDEX,
    @SerialName("field") FIELD, @SerialName("case") CASE, @SerialName("flag") FLAG,
    @SerialName("enum_entry") ENUM_ENTRY, @SerialName("param") PARAM, @SerialName("void_result") VOID_RESULT,
    @SerialName("component_import_declaration") COMPONENT_IMPORT_DECLARATION,
    @SerialName("component_instance_declaration") COMPONENT_INSTANCE_DECLARATION,
    @SerialName("instance_component_definition") INSTANCE_COMPONENT_DEFINITION,
    @SerialName("instance_alias") INSTANCE_ALIAS, @SerialName("instance_export") INSTANCE_EXPORT,
    @SerialName("instance_core_type") INSTANCE_CORE_TYPE, @SerialName("omitted") OMITTED,
    @SerialName("core_module") CORE_MODULE, @SerialName("core_import_declaration") CORE_IMPORT_DECLARATION,
    @SerialName("core_type_declaration") CORE_TYPE_DECLARATION, @SerialName("core_alias_declaration") CORE_ALIAS_DECLARATION,
    @SerialName("core_export_declaration") CORE_EXPORT_DECLARATION, @SerialName("wasm_recursive_type") WASM_RECURSIVE_TYPE,
    @SerialName("wasm_array_type") WASM_ARRAY_TYPE, @SerialName("wasm_struct_type") WASM_STRUCT_TYPE,
    @SerialName("wasm_function_type") WASM_FUNCTION_TYPE, @SerialName("wasm_field") WASM_FIELD,
    @SerialName("wasm_param") WASM_PARAM, @SerialName("wasm_result") WASM_RESULT,
    @SerialName("wasm_value") WASM_VALUE, @SerialName("wasm_reference") WASM_REFERENCE,
    @SerialName("wasm_heap") WASM_HEAP, @SerialName("external_function") EXTERNAL_FUNCTION,
    @SerialName("external_table") EXTERNAL_TABLE, @SerialName("external_memory") EXTERNAL_MEMORY,
    @SerialName("external_global") EXTERNAL_GLOBAL, @SerialName("external_tag") EXTERNAL_TAG,
    @SerialName("wasm_limits") WASM_LIMITS,
}

@Serializable
data class ComponentTypeNodeAttributes(
    val prefix: String? = null, val name: String? = null, val version: String? = null, @SerialName("version_suffix") val versionSuffix: String? = null,
    val primitive: String? = null, val sort: String? = null, @SerialName("core_sort") val coreSort: String? = null, @SerialName("target_kind") val targetKind: String? = null,
    @SerialName("bound_kind") val boundKind: String? = null, @SerialName("extern_kind") val externKind: String? = null, @SerialName("external_kind") val externalKind: String? = null,
    @SerialName("limits_kind") val limitsKind: String? = null, @SerialName("value_kind") val valueKind: String? = null, @SerialName("reference_form") val referenceForm: String? = null,
    @SerialName("heap_kind") val heapKind: String? = null, val tag: String? = null, val reason: String? = null,
    @SerialName("offset_bytes") val offsetBytes: Long? = null, @SerialName("size_bytes") val sizeBytes: Long? = null, @SerialName("type_index") val typeIndex: Long? = null, val index: Long? = null,
    @SerialName("outer_index") val outerIndex: Long? = null, val length: Long? = null, @SerialName("function_index") val functionIndex: Long? = null,
    @SerialName("callback_index") val callbackIndex: Long? = null, val min: ULong? = null, val max: ULong? = null,
    @SerialName("page_size_log2") val pageSizeLog2: Long? = null, @SerialName("heap_type") val heapType: Long? = null, @SerialName("omitted_node_count") val omittedNodeCount: Long? = null,
    @SerialName("extern_type") val externType: Int? = null, val mutable: Boolean? = null, val shared: Boolean? = null,
    @SerialName("is_final") val isFinal: Boolean? = null, @SerialName("has_descriptor_clause") val hasDescriptorClause: Boolean? = null,
)

@Serializable
data class ComponentTypeNodeResult(
    @SerialName("node_id") val nodeId: String, @SerialName("parent_node_id") val parentNodeId: String? = null, @SerialName("child_role") val childRole: String? = null, @SerialName("child_index") val childIndex: Long? = null,
    val kind: ComponentTypeNodeKind, val attributes: ComponentTypeNodeAttributes,
)

@Serializable
data class ComponentTypeItemResult(val index: Long, val family: ComponentTypeFamily, @SerialName("root_node_id") val rootNodeId: String)

@Serializable
data class ComponentTypeTraversalLimitsResult(
    @SerialName("max_depth") val maxDepth: Long, @SerialName("max_nodes") val maxNodes: Long, @SerialName("visited_nodes") val visitedNodes: Long, @SerialName("omitted_nodes") val omittedNodes: Long,
)

@Serializable
data class ComponentTypeGraphResult(
    val stage: ComponentTypeGraphStage,
    val limits: ComponentTypeTraversalLimitsResult?,
    val items: List<ComponentTypeItemResult>,
    val nodes: List<ComponentTypeNodeResult>,
)
