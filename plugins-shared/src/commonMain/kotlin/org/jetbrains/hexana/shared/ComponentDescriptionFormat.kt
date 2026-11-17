package org.jetbrains.hexana.shared

import org.jetbrains.hexana.wasm.components.*

fun componentSortIndexLabel(sortIndex: ComponentSortIndex): String {
    return when (val sort = sortIndex.sort) {
        is ComponentSort.Func -> "Func(${sortIndex.index})"
        is ComponentSort.Value -> "Value(${sortIndex.index})"
        is ComponentSort.Type -> "Type(${sortIndex.index})"
        is ComponentSort.Component -> "Component(${sortIndex.index})"
        is ComponentSort.Instance -> "Instance(${sortIndex.index})"
        is ComponentSortWithCoreSort -> when (sort.cs) {
            is ComponentCoreSort.Func -> "Core:Func(${sortIndex.index})"
            is ComponentCoreSort.Table -> "Core:Table(${sortIndex.index})"
            is ComponentCoreSort.Memory -> "Core:Memory(${sortIndex.index})"
            is ComponentCoreSort.Global -> "Core:Global(${sortIndex.index})"
            is ComponentCoreSort.Type -> "Core:Type(${sortIndex.index})"
            is ComponentCoreSort.Module -> "Core:Module(${sortIndex.index})"
            is ComponentCoreSort.Instance -> "Core:Instance(${sortIndex.index})"
        }
    }
}

fun componentExternDescriptionLabel(desc: ComponentExternDescription): String = when (desc) {
    is ComponentExternCoreType -> "Extern(Core:Type(${desc.idx}))"
    is ComponentExternTypeBound -> "Extern(Type(${typeBoundLabel(desc.bound)}))"
    is ComponentExternValueBound -> "Extern(Value(${valueBoundLabel(desc.bound)}))"
    is ComponentExternComponent -> "Component(${desc.idx})"
    is ComponentExternInstance -> "Instance(${desc.idx})"
    is ComponentExternType -> "Extern(Type(${desc.idx}))"
}

private fun typeBoundLabel(bound: TypeBound): String = when (bound) {
    is TypeBoundType -> "Type(${bound.idx})"
    is TypeBoundSubResource -> "SubResource"
}

private fun valueBoundLabel(bound: ValueBound): String = when (bound) {
    is ValueBoundType -> "Type(${bound.idx})"
    is ValueBoundValue -> "Value(${componentValueTypeLabel(bound.type)})"
}

private fun componentValueTypeLabel(type: ComponentValueType): String = when (type) {
    is ComponentValueIdxType -> "Type(${type.idx})"
    is ComponentPrimitiveValueType -> when (type.type) {
        is ComponentPrimitiveBoolType -> "Bool"
        is ComponentPrimitiveS8Type -> "S8"
        is ComponentPrimitiveU8Type -> "U8"
        is ComponentPrimitiveS16Type -> "S16"
        is ComponentPrimitiveU16Type -> "U16"
        is ComponentPrimitiveS32Type -> "S32"
        is ComponentPrimitiveU32Type -> "U32"
        is ComponentPrimitiveS64Type -> "S64"
        is ComponentPrimitiveU64Type -> "U64"
        is ComponentPrimitiveF32Type -> "F32"
        is ComponentPrimitiveF64Type -> "F64"
        is ComponentPrimitiveString -> "String"
        is ComponentPrimitiveCharType -> "Char"
        is ComponentPrimitiveErrorContext -> "ErrorContext"
    }
}
