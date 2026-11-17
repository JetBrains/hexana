package org.jetbrains.hexana.shared

import org.jetbrains.hexana.wasm.components.AliasTarget
import org.jetbrains.hexana.wasm.components.CaseLabelValueType
import org.jetbrains.hexana.wasm.components.ComponentAlias
import org.jetbrains.hexana.wasm.components.ComponentAliasDef
import org.jetbrains.hexana.wasm.components.ComponentAliasedType
import org.jetbrains.hexana.wasm.components.ComponentArrayType
import org.jetbrains.hexana.wasm.components.ComponentAsyncFuncResourceType
import org.jetbrains.hexana.wasm.components.ComponentAsyncFunctionType
import org.jetbrains.hexana.wasm.components.ComponentBorrowType
import org.jetbrains.hexana.wasm.components.ComponentCoreAliasDef
import org.jetbrains.hexana.wasm.components.ComponentCoreSort
import org.jetbrains.hexana.wasm.components.ComponentDefType
import org.jetbrains.hexana.wasm.components.ComponentDefValueType
import org.jetbrains.hexana.wasm.components.ComponentEnumType
import org.jetbrains.hexana.wasm.components.ComponentExternComponent
import org.jetbrains.hexana.wasm.components.ComponentExternCoreType
import org.jetbrains.hexana.wasm.components.ComponentExternDescription
import org.jetbrains.hexana.wasm.components.ComponentExternInstance
import org.jetbrains.hexana.wasm.components.ComponentExternType
import org.jetbrains.hexana.wasm.components.ComponentExternTypeBound
import org.jetbrains.hexana.wasm.components.ComponentExternValueBound
import org.jetbrains.hexana.wasm.components.ComponentFlagType
import org.jetbrains.hexana.wasm.components.ComponentFutureType
import org.jetbrains.hexana.wasm.components.ComponentInstanceExpression
import org.jetbrains.hexana.wasm.components.ComponentInstanceInlineExport
import org.jetbrains.hexana.wasm.components.ComponentInstanceType
import org.jetbrains.hexana.wasm.components.ComponentImportedType
import org.jetbrains.hexana.wasm.components.ComponentImportDeclaration
import org.jetbrains.hexana.wasm.components.ComponentInstanceDeclaration
import org.jetbrains.hexana.wasm.components.ComponentListType
import org.jetbrains.hexana.wasm.components.ComponentOptionType
import org.jetbrains.hexana.wasm.components.ComponentOwnType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveBoolType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveCharType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveErrorContext
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveF32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveF64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS16Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS8Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveString
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU16Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU8Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveValueType
import org.jetbrains.hexana.wasm.components.ComponentRecordType
import org.jetbrains.hexana.wasm.components.ComponentResultType
import org.jetbrains.hexana.wasm.components.ComponentRoutineType
import org.jetbrains.hexana.wasm.components.ComponentSort
import org.jetbrains.hexana.wasm.components.ComponentSortIndex
import org.jetbrains.hexana.wasm.components.ComponentSortWithCoreSort
import org.jetbrains.hexana.wasm.components.ComponentStreamType
import org.jetbrains.hexana.wasm.components.ComponentSyncFuncResourceType
import org.jetbrains.hexana.wasm.components.ComponentResourceType
import org.jetbrains.hexana.wasm.components.ComponentTupleType
import org.jetbrains.hexana.wasm.components.ComponentType
import org.jetbrains.hexana.wasm.components.ComponentValueIdxType
import org.jetbrains.hexana.wasm.components.ComponentValueType
import org.jetbrains.hexana.wasm.components.ComponentVariantType
import org.jetbrains.hexana.wasm.components.ComponentVoidType
import org.jetbrains.hexana.wasm.components.CoreInstanceInlineExport
import org.jetbrains.hexana.wasm.components.CorruptInstanceCoreExpression
import org.jetbrains.hexana.wasm.components.ExportDeclaration
import org.jetbrains.hexana.wasm.components.FuncInlineCoreExpression
import org.jetbrains.hexana.wasm.components.FuncInstanceInlineExport
import org.jetbrains.hexana.wasm.components.GlobalInlineCoreExpression
import org.jetbrains.hexana.wasm.components.ImportDeclaration
import org.jetbrains.hexana.wasm.components.InlineInstanceCoreExpression
import org.jetbrains.hexana.wasm.components.InlineInstanceCoreInlineExpression
import org.jetbrains.hexana.wasm.components.InlineInstanceExpression
import org.jetbrains.hexana.wasm.components.InstanceCoreExpression
import org.jetbrains.hexana.wasm.components.InstanceDeclaration
import org.jetbrains.hexana.wasm.components.InstanceExpression
import org.jetbrains.hexana.wasm.components.InstanceInlineCoreExpression
import org.jetbrains.hexana.wasm.components.InstanceInlineExport
import org.jetbrains.hexana.wasm.components.InstanceInlineExportExpression
import org.jetbrains.hexana.wasm.components.MemoryInlineCoreExpression
import org.jetbrains.hexana.wasm.components.ModuleInlineCoreExpression
import org.jetbrains.hexana.wasm.components.ModuleInstanceCoreExpression
import org.jetbrains.hexana.wasm.components.TableInlineCoreExpression
import org.jetbrains.hexana.wasm.components.TypeBoundSubResource
import org.jetbrains.hexana.wasm.components.TypeBoundType
import org.jetbrains.hexana.wasm.components.TypeInlineCoreExpression
import org.jetbrains.hexana.wasm.components.TypeInstanceInlineExport
import org.jetbrains.hexana.wasm.components.UnknownInstanceCoreExpression
import org.jetbrains.hexana.wasm.components.ValueBoundType
import org.jetbrains.hexana.wasm.components.ValueBoundValue
import org.jetbrains.hexana.wasm.components.ValueInstanceInlineExport
import org.jetbrains.hexana.wasm.components.WasmComponentCoreModuleType
import org.jetbrains.hexana.wasm.components.WasmComponentCoreType

/**
 * WAT-style one-line renderings for component-model constructs, used by the
 * component-level virtualized WAT view. Follows the wasm-tools print
 * vocabulary without claiming byte-exact parity. Indices are the raw indices
 * carried by the records; cross-section index spaces (imports and aliases
 * also introduce indices) are not resolved.
 */

private const val MAX_JOINED_ITEMS = 8

private fun <T> List<T>.joinBounded(transform: (T) -> String): String {
    val head = take(MAX_JOINED_ITEMS).joinToString(" ", transform = transform)
    return if (size > MAX_JOINED_ITEMS) "$head (; +${size - MAX_JOINED_ITEMS} more ;)" else head
}

fun componentPrimitiveTypeWat(type: ComponentPrimitiveType): String = when (type) {
    ComponentPrimitiveBoolType -> "bool"
    ComponentPrimitiveS8Type -> "s8"
    ComponentPrimitiveU8Type -> "u8"
    ComponentPrimitiveS16Type -> "s16"
    ComponentPrimitiveU16Type -> "u16"
    ComponentPrimitiveS32Type -> "s32"
    ComponentPrimitiveU32Type -> "u32"
    ComponentPrimitiveS64Type -> "s64"
    ComponentPrimitiveU64Type -> "u64"
    ComponentPrimitiveF32Type -> "f32"
    ComponentPrimitiveF64Type -> "f64"
    ComponentPrimitiveCharType -> "char"
    ComponentPrimitiveString -> "string"
    ComponentPrimitiveErrorContext -> "error-context"
}

fun componentValueTypeWat(type: ComponentValueType): String = when (type) {
    is ComponentValueIdxType -> "\$#type${type.idx}"
    is ComponentPrimitiveValueType -> componentPrimitiveTypeWat(type.type)
}

fun componentDefTypeWat(type: ComponentDefType): String = when (type) {
    is ComponentPrimitiveType -> componentPrimitiveTypeWat(type)
    is ComponentDefValueType -> componentDefValueTypeWat(type)
    is ComponentAliasedType -> componentAliasDefWat(type.alias)
    is ComponentImportedType ->
        "(import \"${type.import.importName}${type.import.version.orEmpty()}\" ${componentExternDescriptionWat(type.import.externDescription)})"
    is ComponentRoutineType -> componentRoutineTypeWat(type)
    is ComponentType -> "(component ${type.cd.joinBounded(::componentDeclarationWat)})"
    is ComponentInstanceType -> "(instance ${type.cd.joinBounded(::instanceDeclarationWat)})"
}

private fun componentDefValueTypeWat(type: ComponentDefValueType): String = when (type) {
    is ComponentValueType -> componentValueTypeWat(type)
    is ComponentRecordType ->
        "(record ${type.fields.joinBounded { "(field \"${it.label}\" ${componentValueTypeWat(it.value)})" }})"
    is ComponentVariantType -> "(variant ${type.variants.joinBounded(::caseWat)})"
    is ComponentListType -> "(list ${componentValueTypeWat(type.elementType)})"
    is ComponentArrayType -> "(array ${componentValueTypeWat(type.elementType)} ${type.len})"
    is ComponentTupleType -> "(tuple ${type.elementTypes.joinBounded(::componentValueTypeWat)})"
    is ComponentFlagType -> "(flags ${type.flags.joinBounded { "\"$it\"" }})"
    is ComponentEnumType -> "(enum ${type.entries.joinBounded { "\"$it\"" }})"
    is ComponentOptionType -> "(option ${componentValueTypeWat(type.option)})"
    is ComponentResultType -> buildString {
        append("(result")
        type.result?.let { append(" ").append(componentValueTypeWat(it)) }
        type.error?.let { append(" (error ").append(componentValueTypeWat(it)).append(")") }
        append(")")
    }
    is ComponentOwnType -> "(own \$#type${type.idx})"
    is ComponentBorrowType -> "(borrow \$#type${type.idx})"
    is ComponentStreamType -> type.stream?.let { "(stream ${componentValueTypeWat(it)})" } ?: "(stream)"
    is ComponentFutureType -> type.future?.let { "(future ${componentValueTypeWat(it)})" } ?: "(future)"
    is ComponentResourceType -> when (type) {
        is ComponentSyncFuncResourceType ->
            type.funcIdx?.let { "(resource (rep i32) (dtor (func $it)))" } ?: "(resource (rep i32))"
        is ComponentAsyncFuncResourceType -> buildString {
            append("(resource (rep i32) (dtor async (func ${type.funcIdx})")
            type.callbackIdx?.let { append(" (callback (func $it))") }
            append("))")
        }
    }
}

private fun caseWat(case: CaseLabelValueType): String =
    case.value?.let { "(case \"${case.label}\" ${componentValueTypeWat(it)})" } ?: "(case \"${case.label}\")"

private fun componentRoutineTypeWat(type: ComponentRoutineType): String = buildString {
    append("(func")
    if (type is ComponentAsyncFunctionType) append(" async")
    for (p in type.params) append(" (param \"${p.label}\" ${componentValueTypeWat(p.value)})")
    when (val ret = type.ret) {
        is ComponentValueType -> append(" (result ${componentValueTypeWat(ret)})")
        ComponentVoidType -> Unit
        else -> Unit
    }
    append(")")
}

fun componentExternDescriptionWat(desc: ComponentExternDescription): String = when (desc) {
    is ComponentExternCoreType -> "(core type ${desc.idx})"
    is ComponentExternType -> "(type ${desc.idx})"
    is ComponentExternTypeBound -> when (val bound = desc.bound) {
        is TypeBoundType -> "(type (eq ${bound.idx}))"
        is TypeBoundSubResource -> "(type (sub resource))"
    }
    is ComponentExternValueBound -> when (val bound = desc.bound) {
        is ValueBoundType -> "(value (type ${bound.idx}))"
        is ValueBoundValue -> "(value ${componentValueTypeWat(bound.type)})"
    }
    is ComponentExternComponent -> "(component ${desc.idx})"
    is ComponentExternInstance -> "(instance ${desc.idx})"
}

fun componentSortIndexWat(sortIndex: ComponentSortIndex): String =
    "(${componentSortName(sortIndex.sort)} ${sortIndex.index})"

private fun componentSortName(sort: ComponentSort): String = when (sort) {
    ComponentSort.Func -> "func"
    ComponentSort.Value -> "value"
    ComponentSort.Type -> "type"
    ComponentSort.Component -> "component"
    ComponentSort.Instance -> "instance"
    is ComponentSortWithCoreSort -> "core ${coreSortName(sort.cs)}"
}

private fun coreSortName(sort: ComponentCoreSort): String = when (sort) {
    ComponentCoreSort.Func -> "func"
    ComponentCoreSort.Table -> "table"
    ComponentCoreSort.Memory -> "memory"
    ComponentCoreSort.Global -> "global"
    ComponentCoreSort.Type -> "type"
    ComponentCoreSort.Module -> "module"
    ComponentCoreSort.Instance -> "instance"
}

fun componentAliasDefWat(alias: ComponentAliasDef): String = when (alias) {
    is ComponentAlias -> aliasWat(componentSortName(alias.componentSort), alias.aliasTarget)
    is ComponentCoreAliasDef -> aliasWat("core ${coreSortName(alias.componentCoreSort)}", alias.aliasCoreTarget)
}

private fun aliasWat(sortName: String, target: AliasTarget): String = when (target) {
    is AliasTarget.Export -> "(alias export ${target.index} \"${target.name}\" ($sortName))"
    is AliasTarget.Core -> "(alias core export ${target.index} \"${target.name}\" ($sortName))"
    is AliasTarget.Outer -> "(alias outer ${target.index} ${target.outerIndex} ($sortName))"
    is AliasTarget.CoreOuter -> "(alias outer ${target.index} ${target.outerIndex} ($sortName))"
}

private fun componentDeclarationWat(decl: org.jetbrains.hexana.wasm.components.ComponentDeclaration): String = when (decl) {
    is ComponentImportDeclaration ->
        "(import \"${decl.id.importName}${decl.id.versionSuffix.orEmpty()}\" ${componentExternDescriptionWat(decl.id.ed)})"
    is ComponentInstanceDeclaration -> instanceDeclarationWat(decl.id)
    is ImportDeclaration ->
        "(import \"${decl.importName}${decl.versionSuffix.orEmpty()}\" ${componentExternDescriptionWat(decl.ed)})"
}

/** [InstanceDeclaration] is an open interface; unknown implementors degrade to a comment. */
private fun instanceDeclarationWat(decl: InstanceDeclaration): String = when (decl) {
    is ExportDeclaration ->
        "(export \"${decl.exportName}${decl.versionSuffix.orEmpty()}\" ${componentExternDescriptionWat(decl.ed)})"
    is ComponentAliasDef -> componentAliasDefWat(decl)
    is ComponentDefType -> "(type ${componentDefTypeWat(decl)})"
    is WasmComponentCoreType -> coreTypeWat(decl)
    else -> "(; declaration ;)"
}

fun coreTypeWat(type: WasmComponentCoreType): String = when (type) {
    is WasmComponentCoreModuleType -> "(core type (module (; ${type.list.size} declarations ;)))"
    else -> "(core type)"
}

fun instanceExpressionWat(expression: InstanceExpression): String = when (expression) {
    is ComponentInstanceExpression ->
        "(instantiate (component ${expression.index})${
            expression.args.joinToString("") { " (with \"${it.name}\" ${componentSortIndexWat(it.sortIndex)})" }
        })"
    is InlineInstanceExpression -> "(instance ${expression.e.joinBounded(::inlineExportWat)})"
}

private fun inlineExportWat(export: InstanceInlineExport): String = when (export) {
    is CoreInstanceInlineExport -> "(export \"${export.name}\" (core ${coreSortName(export.cs)} ${export.index}))"
    is FuncInstanceInlineExport -> "(export \"${export.name}\" (func ${export.index}))"
    is ValueInstanceInlineExport -> "(export \"${export.name}\" (value ${export.index}))"
    is TypeInstanceInlineExport -> "(export \"${export.name}\" (type ${export.index}))"
    is ComponentInstanceInlineExport -> "(export \"${export.name}\" (component ${export.index}))"
    is InstanceInlineExportExpression -> "(export \"${export.name}\" (instance ${export.index}))"
}

fun instanceCoreExpressionWat(expression: InstanceCoreExpression): String = when (expression) {
    is ModuleInstanceCoreExpression ->
        "(instantiate (module ${expression.moduleIndex})${
            expression.instantiateArgs.joinToString("") { " (with \"${it.name}\" (instance ${it.instanceIndex}))" }
        })"
    is InlineInstanceCoreExpression -> "(core instance ${expression.e.joinBounded(::coreInlineExportWat)})"
    is UnknownInstanceCoreExpression -> "(; unknown core-instance tag 0x${expression.tag.toHexString()} ;)"
    is CorruptInstanceCoreExpression -> "(; corrupt core-instance entry: ${expression.message} ;)"
}

private fun coreInlineExportWat(export: InlineInstanceCoreInlineExpression): String = when (export) {
    is FuncInlineCoreExpression -> "(export \"${export.name}\" (func ${export.index}))"
    is TableInlineCoreExpression -> "(export \"${export.name}\" (table ${export.index}))"
    is MemoryInlineCoreExpression -> "(export \"${export.name}\" (memory ${export.index}))"
    is GlobalInlineCoreExpression -> "(export \"${export.name}\" (global ${export.index}))"
    is TypeInlineCoreExpression -> "(export \"${export.name}\" (type ${export.index}))"
    is ModuleInlineCoreExpression -> "(export \"${export.name}\" (module ${export.index}))"
    is InstanceInlineCoreExpression -> "(export \"${export.name}\" (instance ${export.index}))"
}